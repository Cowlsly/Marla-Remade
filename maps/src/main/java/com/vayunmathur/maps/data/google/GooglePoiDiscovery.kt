package com.vayunmathur.maps.data.google

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import kotlin.math.ln

/**
 * Viewport / near discovery of Google POIs for the custom overlay layer.
 *
 * This generalises [GooglePoiDataSource]'s single-place `pickEntry`/`parsePlace`
 * (which collapse the scraped result set to the one nearest match): instead we
 * keep the WHOLE list and map each entry to a [GooglePoiPin] (id + lat/lng +
 * name + category + rating) so the pins can be drawn and tapped.
 *
 * Same keyless google.com/maps scrape caveats as [GooglePoiDataSource]: it calls
 * the same undocumented `search?tbm=map` endpoint a logged-out browser hits (no
 * API key), the response is a guard-prefixed positional JSON array parsed by the
 * shared [GoogleResponse] helpers, every path is fragile (a Google reshape makes
 * fields go null rather than throw), and keyless responses are bot-degraded. All
 * network runs on [Dispatchers.IO].
 *
 * Debounced + LRU-cached (D3/D7): the ViewModel debounces the camera-idle
 * trigger, and results are cached here per viewport centre rounded to ~100 m so
 * a small pan back into a visited area doesn't refetch.
 *
 * NOTE (on-device): the live scrape needs a device with network — it can't be
 * exercised at compile time. See [GooglePoiDataSource] for the cookie-warming
 * device-verification caveat.
 */
object GooglePoiDiscovery {

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

    /** Broad default so a viewport comes back populated with mixed POIs rather
     *  than filtered to a single business type. */
    private const val DEFAULT_QUERY = "points of interest"

    /** Cap pins per fetch so a dense downtown viewport doesn't push hundreds of
     *  symbols onto the map (kept by descending [GooglePoiPin.prominence]). */
    private const val MAX_PINS = 60

    @Volatile private var sessionWarmed = false

    // Bounded access-ordered LRU keyed on rounded centre + query + radius bucket.
    // Guarded by synchronized(cache).
    private data class Key(val lat: Double, val lon: Double, val query: String, val scale: Int)
    private val cache = object : LinkedHashMap<Key, List<GooglePoiPin>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<GooglePoiPin>>) = size > 32
    }

    /**
     * Discover POIs around [lat],[lon]. Never throws — returns an empty list when
     * the scrape fails or the positional paths drift. LRU-cached on the centre
     * rounded to ~100 m so re-visiting an area is instant.
     *
     * [radiusScale] widens the requested viewport (1.0 = the calibrated default);
     * the caller passes > 1 to prefetch a padded box so a small pan is already
     * covered. It's bucketed into the cache key so different scales don't collide.
     */
    suspend fun nearby(
        lat: Double,
        lon: Double,
        query: String = DEFAULT_QUERY,
        radiusScale: Double = 1.0,
    ): List<GooglePoiPin> {
        val key = Key(round3(lat), round3(lon), query, (radiusScale * 10).toInt())
        synchronized(cache) { cache[key]?.let { return it } }
        val pins = runCatching { fetch(lat, lon, query, radiusScale) }.getOrDefault(emptyList())
        synchronized(cache) { cache[key] = pins }
        return pins
    }

    private suspend fun fetch(
        lat: Double,
        lon: Double,
        query: String,
        radiusScale: Double,
    ): List<GooglePoiPin> =
        withContext(Dispatchers.IO) {
            warmSession()
            val pb = buildViewportPb(query, lat, lon, radiusScale)
            val url = "$SEARCH_ENDPOINT&q=${query.enc()}&pb=${pb.enc()}"
            val body = get(url) ?: return@withContext emptyList()
            val root = GoogleResponse.parseOrNull(body) ?: return@withContext emptyList()
            parsePins(root)
        }

    /**
     * The list-returning generalisation of `pickEntry`/`parsePlace`: walk every
     * result entry at [Paths.RESULTS] and pull the pin fields. Entries missing a
     * name or position are dropped; the rest are de-duped by feature id and
     * ranked by [prominenceOf] before the [MAX_PINS] cap.
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
            .distinctBy { it.id }
            .sortedByDescending { it.prominence }
            .take(MAX_PINS)
    }

    /** Cheap stand-in for Vela's `ambientProminence`: rating weighted by the log
     *  of the review count, so a well-reviewed 4.5 outranks a lone 5.0. */
    private fun prominenceOf(rating: Double?, reviews: Int): Double {
        val r = rating ?: return 0.0
        return r * (1.0 + ln(1.0 + reviews.coerceAtLeast(0)))
    }

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

    private fun buildViewportPb(query: String, lat: Double, lon: Double, radiusScale: Double): String =
        SEARCH_PB_TEMPLATE
            .replace("{QUERY}", query.replace('!', ' ').trim())
            .replace("{ALT}", (BASE_ALTITUDE * radiusScale).toString())
            .replace("{LNG}", lon.toString())
            .replace("{LAT}", lat.toString())

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    // The camera "altitude" in the pb `!1d…` slot: larger = more zoomed out =
    // wider ground coverage. Scaling it (radiusScale) is how a padded prefetch
    // box is requested. This is the calibrated default the template shipped with.
    private const val BASE_ALTITUDE = 25229.167291701906

    // Same calibrated pb template as GooglePoiDataSource — the `!2d<lng>!3d<lat>`
    // block centres the viewport, `!1d<alt>` sets its span, and results come back
    // at [Paths.RESULTS].
    private const val SEARCH_PB_TEMPLATE =
        "!1s{QUERY}!4m8!1m3!1d{ALT}!2d{LNG}!3d{LAT}!3m2!1i1024!2i768!4f13.1!7i20" +
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
