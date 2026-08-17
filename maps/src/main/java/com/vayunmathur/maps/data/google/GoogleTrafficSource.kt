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
 * basemap when Traffic is turned on. Like the other `data/google` scrapes there
 * is NO API key: the request is authorised by a browser-like `User-Agent` +
 * google.com `Referer`, and served from Google's public map-tiles ("mapstiles")
 * hosts `mt0-3.google.com`.
 *
 * Two ways to consume it:
 *  - [TILE_URLS] / [tileTemplate] — plug straight into a maplibre `RasterSource`
 *    so MapLibre fetches + caches tiles itself (what `MyMapLayers` uses).
 *  - [tile] (z,x,y) → PNG bytes — a manual keyless fetch mirroring
 *    [StreetViewDataSource.tile], for callers that need the bytes directly.
 *
 * ASSUMPTION (endpoint): the transparent traffic overlay is requested with
 * `lyrs=traffic` on the mt hosts. This is the long-standing keyless form a
 * logged-out browser / Leaflet-style clients use; if Google reshapes it, swap
 * [LAYER_SPEC] (e.g. to `h@159000000,traffic|seconds_into_week:-1`). A
 * bad/blocked tile degrades to a transparent gap, never a crash.
 *
 * NOTE (on-device): live tiles need a device with network — they can't be
 * exercised at compile time.
 */
object GoogleTrafficSource {

    // Layer spec selecting Google's live-traffic overlay (transparent elsewhere).
    private const val LAYER_SPEC = "traffic"

    // mapstiles hosts; MapLibre round-robins the list, spreading tile load.
    private val HOSTS = listOf("mt0", "mt1", "mt2", "mt3")

    /** Google mt tiles are 256 px (vs the 512 px vector basemap). */
    const val TILE_SIZE = 256

    /** `{x}`/`{y}`/`{z}` raster template for one host — MapLibre substitutes the
     *  tokens per requested tile. */
    fun tileTemplate(host: String = "mt1"): String =
        "https://$host.google.com/vt?lyrs=$LAYER_SPEC&x={x}&y={y}&z={z}"

    /** All four mapstiles hosts as maplibre `RasterSource` tile URLs. */
    val TILE_URLS: List<String> = HOSTS.map { tileTemplate(it) }

    val available: Boolean get() = TILE_URLS.isNotEmpty()

    // Browser-like identity — these headers ARE the credential (no key). The tile
    // host also wants the Google referer, exactly like the other scrapes.
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
     * error / empty body. Never throws. The host is chosen per-tile so load
     * spreads across mt0-3 the same way the raster source does.
     */
    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        val host = HOSTS[Math.floorMod(x + y, HOSTS.size)]
        val url = "https://$host.google.com/vt?lyrs=$LAYER_SPEC&x=$x&y=$y&z=$z"
        val (status, bytes) = runCatching {
            NetworkClient.performRequestBytes(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return@withContext null
        if (status in 200..299 && bytes.isNotEmpty()) bytes else null
    }
}
