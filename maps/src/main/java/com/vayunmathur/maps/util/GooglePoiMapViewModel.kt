package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.google.GooglePoiDiscovery
import com.vayunmathur.maps.data.google.GooglePoiPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Drives the custom Google-POI overlay: turns viewport boxes into a debounced,
 * LRU-cached list of [GooglePoiPin]s to render (Decisions D3/D7, snappier P23).
 *
 * [onViewport] is fed from `MapPage` as the camera MOVES (not only on idle). To
 * keep the overlay feeling live without hammering the keyless scrape, the driver:
 *  - keeps the previous pins on screen until a *new, non-empty* fetch returns
 *    (an empty/degraded scrape never blinks the overlay to nothing);
 *  - fetches with a short debounce when the centre jumps past [BIG_MOVE_M] and a
 *    gentler one otherwise, floored by a [MIN_INTERVAL_MS] min-interval;
 *  - prefetches a padded ([PREFETCH_SCALE]) bbox so a small pan is already
 *    covered, and merges results by pin id (pruning ones panned out of the padded
 *    box) so shared pins stay stable instead of flickering;
 *  - still skips wide views (> ~16 km, [MAX_LAT_SPAN]) where POI pins are
 *    meaningless. All network is on [Dispatchers.IO].
 */
class GooglePoiMapViewModel(application: Application) : AndroidViewModel(application) {

    private val _pins = MutableStateFlow<List<GooglePoiPin>>(emptyList())
    val pins: StateFlow<List<GooglePoiPin>> = _pins.asStateFlow()

    private data class Viewport(
        val lat: Double,
        val lon: Double,
        val north: Double,
        val east: Double,
        val south: Double,
        val west: Double,
    )

    // Latest requested viewport; null suppresses fetching (zoomed out) while
    // leaving the current pins on screen.
    private val _viewport = MutableStateFlow<Viewport?>(null)

    // Pins currently shown, keyed by id so a refresh keeps shared pins stable
    // (no blink) and merely adds/removes the delta. Guarded by synchronized.
    private val shown = LinkedHashMap<String, GooglePoiPin>()

    private var lastFetchCenter: Pair<Double, Double>? = null
    private var lastFetchAt = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start() {
        viewModelScope.launch {
            _viewport.filterNotNull().collectLatest { vp ->
                // Snappier on a big jump (short debounce); gentle on small nudges.
                // collectLatest cancels this delay when a newer viewport arrives,
                // giving the debounce for free.
                val movedM = lastFetchCenter?.let {
                    metersBetween(it.first, it.second, vp.lat, vp.lon)
                } ?: Double.MAX_VALUE
                delay(if (movedM >= BIG_MOVE_M) SHORT_DEBOUNCE_MS else DEBOUNCE_MS)
                // Min-interval floor so a fast pan can't machine-gun the scrape.
                val since = System.currentTimeMillis() - lastFetchAt
                if (since < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - since)
                fetch(vp)
            }
        }
    }

    init {
        start()
    }

    /**
     * Feed a viewport (from `queryVisibleBoundingBox()`). Views wider than
     * [MAX_LAT_SPAN] suppress fetching but KEEP the last pins (no clear);
     * otherwise the box drives the next (debounced, min-intervalled) fetch.
     */
    fun onViewport(north: Double, east: Double, south: Double, west: Double) {
        if (abs(north - south) > MAX_LAT_SPAN) {
            _viewport.value = null
            return
        }
        _viewport.value = Viewport(
            lat = (north + south) / 2.0,
            lon = (east + west) / 2.0,
            north = north,
            east = east,
            south = south,
            west = west,
        )
    }

    private suspend fun fetch(vp: Viewport) {
        val pins = withContext(Dispatchers.IO) {
            GooglePoiDiscovery.nearby(vp.lat, vp.lon, radiusScale = PREFETCH_SCALE)
        }
        lastFetchCenter = vp.lat to vp.lon
        lastFetchAt = System.currentTimeMillis()
        // Empty result (scrape miss / bot-degraded): keep the existing pins so
        // the overlay never blinks to empty.
        if (pins.isEmpty()) return

        // Padded (prefetch) bbox: keep in-view pins across the refresh, drop ones
        // we've panned away from so old areas don't accumulate forever.
        val padLat = (vp.north - vp.south) * (PREFETCH_SCALE - 1.0) / 2.0
        val padLon = (vp.east - vp.west) * (PREFETCH_SCALE - 1.0) / 2.0
        val north = vp.north + padLat
        val south = vp.south - padLat
        val east = vp.east + padLon
        val west = vp.west - padLon

        synchronized(shown) {
            val iter = shown.entries.iterator()
            while (iter.hasNext()) {
                val p = iter.next().value
                if (p.lat !in south..north || p.lng !in west..east) iter.remove()
            }
            for (p in pins) shown[p.id] = p
            _pins.value = shown.values.toList()
        }
    }

    /** Rough planar metres between two lat/lon points — enough for a move
     *  threshold; no need for full haversine at these small spans. */
    private fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val mPerDegLat = 111_320.0
        val dLat = (bLat - aLat) * mPerDegLat
        val dLon = (bLon - aLon) * mPerDegLat * cos(Math.toRadians((aLat + bLat) / 2.0))
        return hypot(dLat, dLon)
    }

    private companion object {
        // Idle-ish refresh for small nudges.
        const val DEBOUNCE_MS = 350L

        // Faster refresh once the centre jumps a meaningful distance.
        const val SHORT_DEBOUNCE_MS = 120L

        // Never fetch more often than this, however fast the camera moves.
        const val MIN_INTERVAL_MS = 700L

        // "Big move" threshold (metres) that earns the short debounce.
        const val BIG_MOVE_M = 1_200.0

        // Fetch a padded bbox (~1.8x the viewport) so small pans are pre-covered.
        const val PREFETCH_SCALE = 1.8

        // ~16 km of latitude — roughly city-zoom; wider views skip the scrape.
        const val MAX_LAT_SPAN = 0.15
    }
}
