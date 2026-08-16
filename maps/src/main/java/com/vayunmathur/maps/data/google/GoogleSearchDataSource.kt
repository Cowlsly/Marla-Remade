package com.vayunmathur.maps.data.google

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URLEncoder

/**
 * One search / reverse-geocode hit. Unlike [GooglePoiInfo] (the bottom-sheet
 * enrichment, which carries no id/name/position because the caller already knew
 * the place), a search result must be self-describing: it drops a pin on the map
 * and, on tap, re-selects the place — so it carries a stable [id], [name],
 * position and a [category]/[address] for the row and pin styling.
 */
data class GoogleSearchResult(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String? = null,
    val address: String? = null,
)

/**
 * Keyless Google text search + reverse-geocode, replacing the removed
 * amenities.db FTS search and address geocoder (Decision D2: search is now
 * online-only, no offline fallback).
 *
 * This is the list-and-geocode sibling of [GooglePoiDataSource] (single-place
 * enrichment) and [GooglePoiDiscovery] (viewport ambient pins): it hits the same
 * undocumented keyless `search?tbm=map` endpoint a logged-out browser uses, and
 * parses the guard-prefixed positional JSON array via the shared [GoogleResponse]
 * helpers. Every path is fragile — a Google reshape makes fields go null rather
 * than throw, and keyless responses are bot-degraded. All network runs on
 * [Dispatchers.IO]; callers ([MapsSearchViewModel]) debounce the query (D7).
 *
 * NOTE (on-device): the live scrape needs a device with network — it can't be
 * exercised at compile time. See [GooglePoiDataSource] for the cookie-warming
 * device-verification caveat.
 */
object GoogleSearchDataSource {

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

    /** Cap the result list so a broad query ("coffee") doesn't return a huge
     *  list — matches the ~20 rows a Vela search surfaces. */
    private const val MAX_RESULTS = 20

    @Volatile private var sessionWarmed = false

    /**
     * Free-text search for [query], biased toward [nearLat],[nearLon] (the map
     * centre). Never throws — returns an empty list when the scrape fails or the
     * positional paths drift. Runs on [Dispatchers.IO].
     */
    suspend fun search(query: String, nearLat: Double, nearLon: Double): List<GoogleSearchResult> {
        if (query.isBlank()) return emptyList()
        return runCatching { fetchSearch(query, nearLat, nearLon) }.getOrDefault(emptyList())
    }

    /**
     * Reverse-geocode a coordinate to the nearest addressable place (the
     * replacement for the removed address FTS geocoder). Returns null when there
     * is no confident match or the scrape fails. Runs on [Dispatchers.IO].
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): GoogleSearchResult? =
        runCatching { fetchReverse(lat, lon) }.getOrNull()

    private suspend fun fetchSearch(query: String, lat: Double, lon: Double): List<GoogleSearchResult> =
        withContext(Dispatchers.IO) {
            warmSession()
            val pb = buildPb(query, lat, lon)
            val url = "$SEARCH_ENDPOINT&q=${query.enc()}&pb=${pb.enc()}"
            val body = get(url) ?: return@withContext emptyList()
            val root = GoogleResponse.parseOrNull(body) ?: return@withContext emptyList()
            parseResults(root)
        }

    private suspend fun fetchReverse(lat: Double, lon: Double): GoogleSearchResult? =
        withContext(Dispatchers.IO) {
            warmSession()
            // A "lat,lon" query geocodes the point; Google answers with either a
            // focused list or a single geocoded node.
            val query = "$lat,$lon"
            val pb = buildPb(query, lat, lon)
            val url = "$SEARCH_ENDPOINT&q=${query.enc()}&pb=${pb.enc()}"
            val body = get(url) ?: return@withContext null
            val root = GoogleResponse.parseOrNull(body) ?: return@withContext null
            parseResults(root).firstOrNull() ?: parseSingle(root)
        }

    /**
     * The list-returning parse: walk every entry at [Paths.RESULTS] and pull the
     * result fields. Entries missing a name or position are dropped; the rest are
     * de-duped by feature id and capped at [MAX_RESULTS].
     */
    private fun parseResults(root: JsonElement): List<GoogleSearchResult> {
        val list = root.at(*Paths.RESULTS).arr() ?: return emptyList()
        return list.mapNotNull { entry -> entryToResult(entry) }
            .distinctBy { it.id }
            .take(MAX_RESULTS)
    }

    /** A single geocoded node → wrap as [null, node] so the entry-relative paths
     *  (place node at [1]) resolve unchanged, then map it. */
    private fun parseSingle(root: JsonElement): GoogleSearchResult? {
        val node = root.at(*Paths.SINGLE) ?: return null
        return entryToResult(JsonArray(listOf(JsonNull, node)))
    }

    private fun entryToResult(entry: JsonElement): GoogleSearchResult? {
        val name = entry.at(*Paths.NAME).str() ?: return null
        val plat = entry.at(*Paths.LAT).dbl() ?: return null
        val plng = entry.at(*Paths.LNG).dbl() ?: return null
        return GoogleSearchResult(
            id = entry.at(*Paths.FEATURE_ID).str() ?: "$plat,$plng",
            name = name,
            lat = plat,
            lng = plng,
            category = entry.at(*Paths.CATEGORY).str(),
            address = entry.at(*Paths.ADDRESS).str()
                ?: entry.at(*Paths.ADDRESS_LINES).arr()
                    ?.mapNotNull { it.str() }?.joinToString(", ")?.ifBlank { null },
        )
    }

    // --- HTTP plumbing (mirrors GooglePoiDataSource / GooglePoiDiscovery) ----

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

    private fun buildPb(query: String, lat: Double, lon: Double): String =
        SEARCH_PB_TEMPLATE
            .replace("{QUERY}", query.replace('!', ' ').trim())
            .replace("{LNG}", lon.toString())
            .replace("{LAT}", lat.toString())

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    // Same calibrated pb template as GooglePoiDataSource — the `!2d<lng>!3d<lat>`
    // block centres the search and results come back at [Paths.RESULTS].
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

    // Subset of GooglePoiDataSource.Paths needed for a search row (entry-relative;
    // the place node is [1]; RESULTS/SINGLE are response-root-relative).
    private object Paths {
        val RESULTS = intArrayOf(64)
        val SINGLE = intArrayOf(0, 1, 0, 14)
        val NAME = intArrayOf(1, 11)
        val LAT = intArrayOf(1, 9, 2)
        val LNG = intArrayOf(1, 9, 3)
        val CATEGORY = intArrayOf(1, 13, 0)
        val FEATURE_ID = intArrayOf(1, 10)
        val ADDRESS = intArrayOf(1, 18)
        val ADDRESS_LINES = intArrayOf(1, 2)
    }
}
