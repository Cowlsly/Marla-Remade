package com.vayunmathur.maps.data.google

import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2

/**
 * Viewport / near discovery of Google POIs for the custom overlay layer.
 *
 * This generalises [GooglePoiDataSource]'s single-place `pickEntry`/`parsePlace`
 * (which collapse the scraped result set to the one nearest match): instead we
 * keep the WHOLE list and map each entry to a [GooglePoiPin] (id + lat/lng +
 * name + category + rating) so the pins can be drawn and tapped.
 *
 * COVERAGE (ported from Vela `GoogleMapsDataSource.nearbyPlaces`, 2026-06): a
 * single wide "points of interest" query returns only the ~20 most prominent
 * places over a ~25 km baked window, so a strip mall shows almost none of its
 * small businesses. To match what Google Maps actually renders we instead:
 *  - TIGHTEN the requested viewport to the caller's real span (`!1d` = span in
 *    metres, `!4f` = the matching zoom) instead of the baked ~25 km net;
 *  - FAN OUT across category terms ("restaurants", "coffee", "gas station", …)
 *    and merge — one "places" query is biased to prominent food/shops and misses
 *    whole tiers (a nail salon, a plumber, a small taqueria), so the fan-out
 *    roughly doubles local coverage;
 *  - ask for a DEEPER pool per term (`!7i60`, up from 20) so the take-N cap can
 *    reach smaller POIs;
 *  - dedup by feature id and rank prominence-first (Vela `ambientProminence`),
 *    so recognizable landmarks win the label slot but the small restaurant you
 *    zoomed next to still survives the (raised) cap.
 *
 * Same keyless google.com/maps scrape caveats as [GooglePoiDataSource]: it calls
 * the same undocumented `search?tbm=map` endpoint a logged-out browser hits (no
 * API key), the response is a guard-prefixed positional JSON array parsed by the
 * shared [GoogleResponse] helpers, every path is fragile (a Google reshape makes
 * fields go null rather than throw), and keyless responses are bot-degraded. All
 * network runs on [Dispatchers.IO].
 *
 * Quota-safe (D3/D7/P23): the ViewModel debounces the camera trigger and floors
 * a min-interval, results are LRU-cached here per viewport centre + span so a
 * small pan back into a visited area doesn't refetch, and the per-fetch category
 * fan-out is bounded by [FANOUT] permits so a fresh viewport fires only a few
 * requests at a time rather than all terms at once.
 *
 * NOTE (on-device): the live scrape needs a device with network — it can't be
 * exercised at compile time. See [GooglePoiDataSource] for the cookie-warming
 * device-verification caveat.
 */
object GooglePoiDiscovery {

    private const val TAG = "GooglePoiDiscovery"

    // Same calibrated endpoints/identity as GooglePoiDataSource (Vela, 2026-06).
    private const val SEARCH_ENDPOINT =
        "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us"
    private const val SESSION_WARM_URL = "https://www.google.com/maps?hl=en&gl=us"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val REQUEST_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.google.com/maps/",
    )

    /**
     * Category fan-out terms (Vela `nearbyPlaces.allTerms`). "places" is the
     * broad ambient query; the rest pull the tiers a single prominent-biased
     * query under-returns so the map shows a Google-like MIX (a gas station, a
     * gym, a grocer, a small restaurant) rather than only the few big names.
     * Low-signal extras a term drags in sink under the prominence sort.
     */
    private val FANOUT_TERMS = listOf(
        "places", "restaurants", "coffee", "stores", "shopping", "services",
        "beauty salon", "fast food", "grocery store", "gas station", "gym",
        "bar", "pharmacy", "school", "park",
    )

    /** Caps how many category requests run AT ONCE per fetch, so a fresh viewport
     *  doesn't fire all [FANOUT_TERMS] at once (request burst + transient parse
     *  heap). Shared across calls so a pan mid-load can't double the burst. */
    private val FANOUT = Semaphore(4)

    /** Default cap on pins per fetch. The full ranked pool is cached, so a
     *  close-zoom caller can pass a higher cap to keep smaller POIs without a
     *  refetch (see [GooglePoiMapViewModel]). Raised well above the old 60 so the
     *  fan-out's comprehensive pool isn't filtered back down to a few names. */
    private const val MAX_PINS = 120

    /** Per-term result pool. Deeper than the old !7i20 so zooming in can reach
     *  down the prominence rank to small/local POIs. */
    private const val POOL_SIZE = 60

    /** Clamp for the requested ground span (`!1d`). Floored so a very tight zoom
     *  still asks a sensible window; capped so a wide view doesn't request a
     *  continent. */
    private const val MIN_SPAN_M = 500.0
    private const val MAX_SPAN_M = 40_000.0

    @Volatile private var sessionWarmed = false

    // Bounded access-ordered LRU keyed on rounded centre + span bucket. The FULL
    // ranked pool is stored so a higher-cap caller reuses it. Guarded by
    // synchronized(cache).
    private data class Key(val lat: Double, val lon: Double, val spanBucket: Int)
    private val cache = object : LinkedHashMap<Key, List<GooglePoiPin>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<GooglePoiPin>>) = size > 32
    }

    /**
     * Discover POIs around [lat],[lon] within a viewport of [spanMeters] ground
     * span. Never throws — returns an empty list when the scrape fails or the
     * positional paths drift. LRU-cached on the centre (rounded to ~100 m) plus
     * a span bucket so re-visiting an area is instant.
     *
     * [maxPins] caps how many of the ranked pins are returned. The full ranked
     * pool is cached, so a close-zoom caller can ask for many more without a
     * refetch — easing the prominence filter so smaller POIs aren't cut.
     */
    suspend fun nearby(
        lat: Double,
        lon: Double,
        spanMeters: Double,
        maxPins: Int = MAX_PINS,
    ): List<GooglePoiPin> {
        val span = spanMeters.coerceIn(MIN_SPAN_M, MAX_SPAN_M)
        val key = Key(round3(lat), round3(lon), (span / 250.0).toInt())
        synchronized(cache) { cache[key]?.let { return it.take(maxPins) } }
        val pins = runCatching { fetch(lat, lon, span) }.getOrDefault(emptyList())
        synchronized(cache) { cache[key] = pins }
        Log.i(
            TAG,
            "nearby lat=$lat lon=$lon span=${span.toInt()}m terms=${FANOUT_TERMS.size} " +
                "pool=${pins.size} returned=${minOf(pins.size, maxPins)} (cap=$maxPins)",
        )
        return pins.take(maxPins)
    }

    /**
     * Fan out the [FANOUT_TERMS] across the viewport, merge, dedup by feature id
     * and rank prominence-first. Each term is a separate keyless `search?tbm=map`
     * hit with the viewport tightened to [span]; any single term failing/​drifting
     * yields an empty list for that term and never sinks the others.
     */
    private suspend fun fetch(lat: Double, lon: Double, span: Double): List<GooglePoiPin> =
        withContext(Dispatchers.IO) {
            warmSession()
            // Match the zoom to the tightened window (Vela: span 25229 ↔ zoom 13.1).
            val zoom = (13.1 + log2(25229.0 / span)).coerceIn(13.0, 17.5)
            val pool = coroutineScope {
                FANOUT_TERMS.map { term ->
                    async { runCatching { fetchTerm(term, lat, lon, span, zoom) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }
            rank(pool, lat, lon)
        }

    private suspend fun fetchTerm(
        term: String,
        lat: Double,
        lon: Double,
        span: Double,
        zoom: Double,
    ): List<GooglePoiPin> = FANOUT.withPermit {
        val pb = buildViewportPb(term, lat, lon, span, zoom)
        val url = "$SEARCH_ENDPOINT&q=${term.enc()}&pb=${pb.enc()}"
        val body = get(url) ?: return@withPermit emptyList()
        val root = GoogleResponse.parseOrNull(body) ?: return@withPermit emptyList()
        parsePins(root)
    }

    /**
     * The list-returning generalisation of `pickEntry`/`parsePlace`: walk every
     * result entry at [Paths.RESULTS] and pull the pin fields. Entries missing a
     * name or position are dropped.
     */
    private fun parsePins(root: JsonElement): List<GooglePoiPin> {
        val list = root.at(*Paths.RESULTS).arr() ?: return emptyList()
        return list.mapNotNull { entry ->
            val name = entry.at(*Paths.NAME).str() ?: return@mapNotNull null
            val plat = entry.at(*Paths.LAT).dbl() ?: return@mapNotNull null
            val plng = entry.at(*Paths.LNG).dbl() ?: return@mapNotNull null
            val rating = entry.at(*Paths.RATING).dbl()
            val reviews = entry.at(*Paths.REVIEW_COUNT).int() ?: 0
            GooglePoiPin(
                id = entry.at(*Paths.FEATURE_ID).str() ?: "$plat,$plng",
                name = name,
                lat = plat,
                lng = plng,
                category = entry.at(*Paths.CATEGORY).str(),
                rating = rating,
                prominence = prominenceOf(rating, reviews),
            )
        }
    }

    /**
     * Dedup by feature id (the same place returned under several terms) then rank
     * for the map: prominence-first, exact distance from the viewport centre only
     * as a tiebreak (Vela `rankAmbientPlaces`). The recognizable landmarks lead
     * and win the label slot; the low-signal junk the fan-out drags in sinks and
     * is dropped by the caller's take-N cap.
     */
    private fun rank(pool: List<GooglePoiPin>, lat: Double, lon: Double): List<GooglePoiPin> =
        pool.distinctBy { it.id }
            .sortedWith(
                compareByDescending<GooglePoiPin> { it.prominence }
                    .thenBy { metersBetween(lat, lon, it.lat, it.lng) },
            )

    /** Vela's `ambientProminence`: review count dominates (log-compressed so a
     *  mega-chain doesn't utterly bury everything), nudged by rating so among
     *  similarly-popular places the better-rated wins. */
    private fun prominenceOf(rating: Double?, reviews: Int): Double =
        ln(reviews.coerceAtLeast(0) + 1.0) * (0.6 + (rating ?: 3.5) / 10.0)

    // --- HTTP plumbing (mirrors GooglePoiDataSource) ------------------------

    private suspend fun warmSession() {
        if (sessionWarmed) return
        runCatching {
            NetworkClient.performRequest(
                url = SESSION_WARM_URL,
                headers = REQUEST_HEADERS + ("Accept" to "text/html,application/xhtml+xml"),
                useSystemTrust = true,
            )
        }
        sessionWarmed = true
    }

    private suspend fun get(url: String): String? {
        val resp = runCatching {
            NetworkClient.performRequest(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return null
        return if (resp.isSuccess) resp.body else null
    }

    /** Build the viewport `pb`: substitute the query + centre, then tighten the
     *  baked span/zoom/pool tokens to this fetch (Vela `nearbyPlaces`): `!1d` =
     *  ground span in metres, `!4f` = matching zoom, `!7i` = the deeper pool. */
    private fun buildViewportPb(
        query: String,
        lat: Double,
        lon: Double,
        span: Double,
        zoom: Double,
    ): String =
        SEARCH_PB_TEMPLATE
            .replace("{QUERY}", query.replace('!', ' ').trim())
            .replace("{LNG}", lon.toString())
            .replace("{LAT}", lat.toString())
            .replaceFirst(Regex("!1d[0-9.]+"), "!1d${span.toInt()}")
            .replaceFirst(Regex("!4f[0-9.]+"), "!4f${String.format(java.util.Locale.US, "%.1f", zoom)}")
            .replaceFirst(Regex("!7i\\d+"), "!7i$POOL_SIZE")

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    /** Rough planar metres between two lat/lon points — enough for a rank
     *  tiebreak at viewport scale; no need for full haversine here. */
    private fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val mPerDegLat = 111_320.0
        val dLat = (bLat - aLat) * mPerDegLat
        val dLon = (bLon - aLon) * mPerDegLat * cos(Math.toRadians((aLat + bLat) / 2.0))
        return hypot(dLat, dLon)
    }

    // Same calibrated pb template as GooglePoiDataSource — the `!2d<lng>!3d<lat>`
    // block centres the viewport; the `!1d`/`!4f`/`!7i` tokens are rewritten
    // per-fetch (see buildViewportPb) and results come back at [Paths.RESULTS].
    private const val SEARCH_PB_TEMPLATE =
        "!1s{QUERY}!4m8!1m3!1d25229.167291701906!2d{LNG}!3d{LAT}!3m2!1i1024!2i768!4f13.1!7i20" +
            "!10b1!12m52!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m25!32i1!49b1!63m0!66b1" +
            "!85b1!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1" +
            "!250b1!253b1!260b1!266b1!273b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m3!5e2" +
            "!6b1!14b1!46m1!1b0!96b1!99b1!19m4!2m3!1i360!2i120!4i8!20m57!2m2!1i203!2i100!3m2!2i4!5b1" +
            "!6m6!1m2!1i86!2i86!1m2!1i408!2i240!7m33!1m3!1e1!2b0!3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3" +
            "!1m3!1e8!2b0!3e3!1m3!1e10!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9!2b1!3e2!2b1" +
            "!9b0!15m8!1m7!1m2!1m1!1e2!2m2!1i195!2i195!3i20!15i9937!24m107!1m25!13m9!2b1!3b1!4b1!6i1" +
            "!8b1!9b1!14b1!20b1!25b1!18m14!3b1!4b1!5b1!6b1!13b1!14b1!17b1!21b1!22b1!32b1!33m1!1b1" +
            "!34b1!36e2!10m1!8e3!11m1!3e1!17b1!20m2!1e3!1e6!24b1!25b1!26b1!27b1!29b1!30m1!2b1!36b1" +
            "!37b1!39m3!2m2!2i1!3i1!43b1!52b1!54m1!1b1!55b1!56m1!1b1!61m2!1m1!1e1!65m5!3m4!1m3!1m2" +
            "!1i224!2i298!72m22!1m8!2b1!5b1!7b1!12m4!1b1!2b1!4m1!1e1!4b1!8m10!1m6!4m1!1e1!4m1!1e3" +
            "!4m1!1e4!3sother_user_google_review_posts__and__hotel_and_vr_partner_review_posts!6m1" +
            "!1e1!9b1!89b1!90m2!1m1!1e2!98m3!1b1!2b1!3b1!103b1!113b1!114m3!1b1!2m1!1b1!117b1!122m1" +
            "!1b1!126b1!127b1!128m1!1b0!26m4!2m3!1i80!2i92!4i8!30m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768" +
            "!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748" +
            "!2m2!1i1024!2i768!34m19!2b1!3b1!4b1!6b1!8m6!1b1!3b1!4b1!5b1!6b1!7b1!9b1!12b1!14b1!20b1" +
            "!23b1!25b1!26b1!31b1!37m1!1e81!42b1!49m10!3b1!6m2!1b1!2b1!7m2!1e3!2b1!8b1!9b1!10e2!50m3" +
            "!2e2!3m1!3b1!61b1!67m5!7b1!10b1!14b1!15m1!1b0!69i782!77b1"

    // Subset of GooglePoiDataSource.Paths needed for a pin (entry-relative; the
    // place node is [1], RESULTS is response-root-relative).
    private object Paths {
        val RESULTS = intArrayOf(64)
        val NAME = intArrayOf(1, 11)
        val LAT = intArrayOf(1, 9, 2)
        val LNG = intArrayOf(1, 9, 3)
        val CATEGORY = intArrayOf(1, 13, 0)
        val RATING = intArrayOf(1, 4, 7)
        val REVIEW_COUNT = intArrayOf(1, 4, 8)
        val FEATURE_ID = intArrayOf(1, 10)
    }
}
