package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.google.GooglePoiDiscovery
import com.vayunmathur.maps.data.google.GooglePoiPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

    // Offline ambient-pin refresh (P29), separate from the retired Google-scrape
    // pipeline above. Cancelled on each new viewport so a fast pan debounces.
    private var offlineJob: Job? = null

    /**
     * Publish ambient pins from the OFFLINE POI index (P29) for the
     * [north]..[south] × [west]..[east] box. Debounced; views wider than [MAX_LAT_SPAN] and empty
     * results KEEP the previous pins so a fast pan / wide zoom never blinks the
     * overlay to nothing (P23 no-clear). The index query + name decode run on
     * [Dispatchers.IO]; [PoiIndex.initialize] is idempotent so calling it here is
     * cheap after the first map.
     */
    fun onViewportOffline(north: Double, east: Double, south: Double, west: Double) {
        if (abs(north - south) > MAX_LAT_SPAN) return
        val app = getApplication<Application>()
        offlineJob?.cancel()
        offlineJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val pins = withContext(Dispatchers.IO) {
                runCatching {
                    PoiIndex.initialize(app)
                    PoiIndex.inViewport(west, south, east, north, cap = OFFLINE_CAP)
                        .map { it.toPin() }
                }.getOrDefault(emptyList())
            }
            if (pins.isNotEmpty()) _pins.value = pins
        }
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
        // Derive a rough zoom band from the viewport's latitude span (we're only
        // fed the bbox). At close zoom, query the ACTUAL (tight) viewport with
        // little padding so a POI you zoomed next to isn't washed out by a wider,
        // prominence-ranked box, and keep many more of the returned pins so
        // local/small POIs (restaurants) survive. Farther out, prefetch a padded
        // box so small pans are pre-covered.
        val closeZoom = abs(vp.north - vp.south) <= CLOSE_LAT_SPAN
        val prefetchScale = if (closeZoom) TIGHT_SCALE else PREFETCH_SCALE
        val cap = if (closeZoom) MAX_PINS_CLOSE else MAX_PINS_FAR

        // The (padded, per-zoom) ground span the fan-out should request — the
        // vertical extent of the box scaled by the prefetch pad, in metres. Vela
        // tightens `!1d` to this so a strip mall's small POIs come back instead of
        // only the ~20 most prominent over a baked ~25 km window.
        val spanMeters = (vp.north - vp.south) * prefetchScale * METERS_PER_DEG_LAT

        val pins = withContext(Dispatchers.IO) {
            GooglePoiDiscovery.nearby(vp.lat, vp.lon, spanMeters = spanMeters, maxPins = cap)
        }
        lastFetchCenter = vp.lat to vp.lon
        lastFetchAt = System.currentTimeMillis()
        // Empty result (scrape miss / bot-degraded): keep the existing pins so
        // the overlay never blinks to empty.
        if (pins.isEmpty()) return

        // Prune shown pins to the (padded, per-zoom) box so old areas don't
        // accumulate; at close zoom the pad is ~0 so we track the tight viewport.
        val padLat = (vp.north - vp.south) * (prefetchScale - 1.0) / 2.0
        val padLon = (vp.east - vp.west) * (prefetchScale - 1.0) / 2.0
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

    /** Map an offline index record to the pin model [pins] publishes; the
     *  category label drives its data-driven pin colour (P29). */
    private fun PoiIndex.PoiRecord.toPin(): GooglePoiPin = GooglePoiPin(
        id = "poi:$latE7:$lonE7",
        name = name,
        lat = lat,
        lng = lon,
        category = PoiCategories.label(type),
    )

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
        // Used at medium/far zoom; close zoom uses the tight box below.
        const val PREFETCH_SCALE = 1.8

        // Close zoom: query the actual viewport with only a hair of padding so a
        // POI you zoomed next to isn't dropped in favour of wider, more prominent
        // ones.
        const val TIGHT_SCALE = 1.1

        // Latitude span (~4.4 km) at/under which we treat the view as "close" and
        // switch to the tight box + higher cap.
        const val CLOSE_LAT_SPAN = 0.04

        // Pin caps: keep a modest cap when zoomed out, but raise it a lot at
        // close zoom so local/small POIs (restaurants) from the category fan-out
        // aren't filtered away. Raised from the old 60/200 now that the fetch
        // returns a comprehensive Google-like pool rather than ~20 prominent
        // names (Vela parity).
        const val MAX_PINS_FAR = 120
        const val MAX_PINS_CLOSE = 350

        // ~16 km of latitude — roughly city-zoom; wider views skip the scrape.
        const val MAX_LAT_SPAN = 0.15

        // Cap on ambient offline pins per viewport (P29) so a dense city box
        // stays bounded; matches the ambient overlay's close-zoom feel.
        const val OFFLINE_CAP = 300

        // Degrees of latitude → metres, for turning the viewport's lat span into
        // the ground span the discovery fetch tightens its `!1d` window to.
        const val METERS_PER_DEG_LAT = 111_320.0
    }
}
