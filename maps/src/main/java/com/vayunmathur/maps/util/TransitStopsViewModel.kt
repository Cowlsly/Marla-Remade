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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the P10 public-transit UI: the departure board for a tapped stop.
 *
 * The stop *pins* are no longer this class's business — they render straight from
 * the baked `transit_stops` basemap layer (see
 * [com.vayunmathur.maps.ui.TransitStopsLayer]), since stops are static data and a
 * per-viewport fetch on every camera idle bought nothing.
 *
 * [departures] is the board for the [openStop]-selected stop, re-fetched on
 * [refresh]. The live countdown itself is computed client-side in the sheet from
 * each departure's epoch time, so no polling is needed here.
 *
 * All network is on [Dispatchers.IO], and is skipped entirely when
 * [ConnectivityMonitor] reports no validated internet — otherwise every offline
 * lookup pays a full HTTP timeout. The departure board is offline-first: the
 * baked `.transit` pack supplies the schedule and MOTIS supplies the realtime
 * overlay on top of it.
 */
class TransitStopsViewModel(application: Application) : AndroidViewModel(application) {

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
     * Resolve the stop nearest [lat],[lon] and open its board. Reached from a
     * tapped `ma_pois` station POI, which carries no stop id of its own.
     *
     * Resolved from the baked `.transit` pack rather than over the network: the
     * pack already holds every stop's position and MOTIS id, so this needs no
     * connectivity and works for the same coverage the offline board does. No-op
     * when no pack covers the point.
     */
    fun openNearestStop(lat: Double, lon: Double) {
        viewModelScope.launch {
            val stop = withContext(Dispatchers.IO) {
                OfflineRouter.nearestStop(getApplication(), lat, lon)
            } ?: return@launch
            openStop(stop)
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
}

/** UI state for the departure board. */
sealed interface DeparturesState {
    data object Idle : DeparturesState
    data class Loading(val stop: TransitStop) : DeparturesState
    data class Loaded(val stop: TransitStop, val departures: List<Departure>) : DeparturesState
}
