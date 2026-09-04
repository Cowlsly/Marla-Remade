package com.vayunmathur.maps.data.google

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keyless Google Maps live-traffic raster tiles.
 *
 * Replaces the old on-device OfflineRouter loopback vector-traffic tile server
 * (`http://localhost/traffic/{z}/{x}/{y}`) with Google's own colored congestion
 * overlay — the same transparent raster tiles maps.google.com layers over its
 * basemap when Traffic is turned on.
 *
 * ENDPOINT: the web map's own `/maps/vt` tile on `www.google.com` — a public,
 * keyless PNG on the same host the other `data/google` scrapes already use. The
 * tile is selected by a trimmed protobuf `pb` param (no map-version epoch, so it
 * doesn't rot):
 *  - `!1m4!1m3!1i{z}!2i{x}!3i{y}` — standard XYZ tile coords (z, then x, then y);
 *  - `!2m9!1e2!2straffic` — `!1e2` = overlay layer, `!2straffic` = the traffic
 *    layer (transparent everywhere there's no congestion data);
 *  - the trailing `!4m2!1sincidents…` block enables incident markers, and the
 *    `!3m8!2sen!3sus…!2sRoadmap` block pins language/region + roadmap styling.
 * This is the exact tile URL the logged-out web map fetches, so it needs no API
 * key, session token, or referer — a plain GET returns the PNG. A bad/blocked
 * tile degrades to a transparent gap, never a crash.
 *
 * Two ways to consume it:
 *  - [TILE_URLS] / [tileTemplate] — the `{x}`/`{y}`/`{z}` raster template for a
 *    raster layer source, once the renderer can draw one (currently a GAP: the
 *    phone map keeps this as a no-op and the toggle plumbing survives).
 *  - [tile] (z,x,y) → PNG bytes — a manual keyless fetch mirroring
 *    [StreetViewDataSource.tile], for callers that need the bytes directly.
 *
 * NOTE (on-device): live tiles need a device with network — they can't be
 * exercised at compile time.
 */
object GoogleTrafficSource {

    /** Google's `/maps/vt` PNG tiles are 256 px (vs the 512 px vector basemap). */
    const val TILE_SIZE = 256

    // The trimmed `pb` spec selecting Google's live-traffic overlay tile. Kept as
    // a template with `{z}`/`{x}`/`{y}` tokens (order matters: `!1i{z}!2i{x}!3i{y}`)
    // so a future raster-layer source and the manual [tile] fetch build the same URL.
    private const val TILE_PB =
        "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
            "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"

    /** `{x}`/`{y}`/`{z}` raster template — a raster-layer source substitutes the
    *  tokens per requested tile, once the renderer can draw one. */
    fun tileTemplate(): String = TILE_PB

    /** The traffic tile URL as a single-element raster-source tile list. */
    val TILE_URLS: List<String> = listOf(TILE_PB)

    val available: Boolean get() = TILE_URLS.isNotEmpty()

    // Browser-like identity for the manual [tile] path. The `/maps/vt` endpoint is
    // keyless and serves the PNG to a plain GET, but sending the same headers as
    // the other google scrapes keeps the direct-bytes fetch consistent.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val REQUEST_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.google.com/maps/",
    )

    /**
     * Fetch one traffic tile's PNG bytes, or null on any non-2xx / transport
     * error / empty body. Never throws.
     */
    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = TILE_PB
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        val (status, bytes) = runCatching {
            NetworkClient.performRequestBytes(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return@withContext null
        if (status in 200..299 && bytes.isNotEmpty()) bytes else null
    }
}
