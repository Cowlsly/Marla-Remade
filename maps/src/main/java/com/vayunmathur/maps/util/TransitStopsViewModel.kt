package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TransitousDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Drives the P10 public-transit UI: the nearby-stops overlay and the departure
 * board (both online via [TransitousDataSource]).
 *
 * Two independent flows:
 *  - [stops] — a debounced, LRU-cached list of [TransitStop]s for the current
 *    viewport (mirrors [GooglePoiMapViewModel]); wider-than-city views clear the
 *    overlay since stops there are meaningless and dense.
 *  - [departures] — the board for the [openStop]-selected stop, re-fetched on
 *    [refresh]. The live countdown itself is computed client-side in the sheet
 *    from each departure's epoch time, so no polling is needed here.
 *
 * All network is on [Dispatchers.IO], and is skipped entirely when
 * [ConnectivityMonitor] reports no validated internet — otherwise every offline
 * lookup pays a full HTTP timeout. The departure board is offline-first: the
 * baked `.transit` pack supplies the schedule and MOTIS supplies the realtime
 * overlay on top of it.
 */
class TransitStopsViewModel(application: Application) : AndroidViewModel(application) {

    private data class Bbox(val north: Double, val east: Double, val south: Double, val west: Double)

    // Rounded viewport box; null suppresses fetching (zoomed out / too wide).
    private val _bbox = MutableStateFlow<Bbox?>(null)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val stops: StateFlow<List<TransitStop>> = _bbox
        .filterNotNull()
        .debounce(DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { b ->
            if (!ConnectivityMonitor.isOnline(getApplication())) emptyList()
            else TransitousDataSource.stopsInBbox(b.south, b.west, b.north, b.east)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Feed a camera-idle viewport (from `queryVisibleBoundingBox()`). Views
     * wider than [MAX_LAT_SPAN] clear the stops; otherwise the rounded box drives
     * the next fetch.
     */
    fun onViewport(north: Double, east: Double, south: Double, west: Double) {
        if (abs(north - south) > MAX_LAT_SPAN) {
            _bbox.value = null
            return
        }
        _bbox.value = Bbox(round3(north), round3(east), round3(south), round3(west))
    }

    // --- Departure board ---------------------------------------------------

    private val _selected = MutableStateFlow<TransitStop?>(null)
    val selected: StateFlow<TransitStop?> = _selected.asStateFlow()

    // Bumped by refresh() to force a re-fetch of the same stop.
    private val _refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val departures: StateFlow<DeparturesState> =
        combine(_selected, _refreshTick) { stop, tick -> stop to tick }
            .flatMapLatest { (stop, tick) ->
                if (stop == null) {
                    flowOf(DeparturesState.Idle)
                } else {
                    flow {
                        emit(DeparturesState.Loading(stop))
                        val force = tick > 0
                        // Offline-first now that the baked board folds MOTIS
                        // realtime in as an overlay: the pack has the complete
                        // schedule, the overlay supplies the live delays. Fall
                        // back to the pure online board only when no pack covers
                        // this stop.
                        val offline = runCatching {
                            OfflineRouter.getStopDeparturesOffline(
                                getApplication(), stop.lat, stop.lon,
                            )
                        }.getOrDefault(emptyList())
                        val deps = offline.ifEmpty {
                            if (ConnectivityMonitor.isOnline(getApplication())) {
                                TransitousDataSource.departures(stop.id, force = force)
                            } else {
                                emptyList()
                            }
                        }
                        emit(DeparturesState.Loaded(stop, deps))
                    }.flowOn(Dispatchers.IO)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeparturesState.Idle)

    /** Open the board for [stop] (called from the map tap). */
    fun openStop(stop: TransitStop) {
        _refreshTick.value = 0
        _selected.value = stop
    }

    /**
     * Resolve the nearest Transitous stop to [lat],[lon] (a tapped station POI
     * carries no stop id) and open its board. Queries a small bbox and picks the
     * closest stop; no-op if none are found nearby.
     */
    fun openNearestStop(lat: Double, lon: Double) {
        viewModelScope.launch {
            val d = 0.01 // ~1.1km search box around the tapped station
            val stops = withContext(Dispatchers.IO) {
                TransitousDataSource.stopsInBbox(lat - d, lon - d, lat + d, lon + d)
            }
            val nearest = stops.minByOrNull { s ->
                val dLat = s.lat - lat
                val dLon = s.lon - lon
                dLat * dLat + dLon * dLon
            } ?: return@launch
            openStop(nearest)
        }
    }

    /** Close the board. */
    fun closeStop() {
        _selected.value = null
    }

    /** Force a live re-fetch of the current stop's board. */
    fun refresh() {
        if (_selected.value != null) _refreshTick.value += 1
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private companion object {
        const val DEBOUNCE_MS = 500L

        // ~9 km of latitude — transit stops are dense, so keep the fetch to a
        // tighter zoom than the Google-POI overlay.
        const val MAX_LAT_SPAN = 0.08
    }
}

/** UI state for the departure board. */
sealed interface DeparturesState {
    data object Idle : DeparturesState
    data class Loading(val stop: TransitStop) : DeparturesState
    data class Loaded(val stop: TransitStop, val departures: List<Departure>) : DeparturesState
}
