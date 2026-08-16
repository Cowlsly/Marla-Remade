package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.google.GooglePoiDiscovery
import com.vayunmathur.maps.data.google.GooglePoiPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

/**
 * Drives the custom Google-POI overlay: turns camera-idle viewport boxes into a
 * debounced, LRU-cached list of [GooglePoiPin]s to render (Decisions D3/D7).
 *
 * [onViewport] is fed from `MapPage`'s camera effect (which already computes the
 * visible bbox via `queryVisibleBoundingBox()`); the centre is rounded and the
 * trigger debounced so panning doesn't hammer the keyless scrape, and
 * [GooglePoiDiscovery] caches per centre so a pan-back is instant. Only
 * sufficiently zoomed-in views fetch — a continent-wide box is skipped (POI pins
 * are meaningless there and it'd be pure quota). All network is on
 * [Dispatchers.IO].
 */
class GooglePoiMapViewModel(application: Application) : AndroidViewModel(application) {

    // Rounded viewport centre (lat, lon); null suppresses fetching (zoomed out).
    private val _center = MutableStateFlow<Pair<Double, Double>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val pins: StateFlow<List<GooglePoiPin>> = _center
        .filterNotNull()
        .debounce(DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { (lat, lon) -> GooglePoiDiscovery.nearby(lat, lon) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Feed a camera-idle viewport (from `queryVisibleBoundingBox()`). Views wider
     * than [MAX_LAT_SPAN] clear the pins instead of fetching; otherwise the box
     * centre (rounded by [GooglePoiDiscovery]) becomes the next fetch key.
     */
    fun onViewport(north: Double, east: Double, south: Double, west: Double) {
        if (abs(north - south) > MAX_LAT_SPAN) {
            _center.value = null
            return
        }
        _center.value = ((north + south) / 2.0) to ((east + west) / 2.0)
    }

    private companion object {
        const val DEBOUNCE_MS = 600L

        // ~16 km of latitude — roughly city-zoom; wider views skip the scrape.
        const val MAX_LAT_SPAN = 0.15
    }
}
