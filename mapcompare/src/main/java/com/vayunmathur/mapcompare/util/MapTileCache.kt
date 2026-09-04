package com.vayunmathur.mapcompare.util

import kotlin.time.Duration.Companion.hours
import android.content.Context
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.net.toUri
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.LibraryLoaderProvider
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.http.HttpIdentifier
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpRequestUrl
import org.maplibre.android.http.HttpResponder
import org.maplibre.android.module.loader.LibraryLoaderProviderImpl
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Properties
import javax.net.ssl.SSLException

/**
 * Disk cache for the streamed protomaps basemap tiles.
 *
 * The basemap is streamed live from [BASEMAP_PMTILES_URL], and our overlays from
 * [OVERLAY_PMTILES_URL], via pmtiles-over-HTTP range requests. MapLibre routes
 * every HTTP resource load through the
 * [HttpRequest] produced by its [ModuleProvider]; we install our own provider
 * via [MapLibre.setModuleProvider] so the whole map stack runs on
 * `library:network` (HttpURLConnection) instead of MapLibre's bundled OkHttp
 * implementation, and cache the range responses for the pmtiles host on the way
 * through.
 *
 * Cache policy (per requested byte range = one tile/directory/header chunk):
 *  - A cached range is kept on disk indefinitely and served whenever the device
 *    is offline, so previously-viewed areas keep working with no network.
 *  - A range is only re-fetched when the device is online AND it is next
 *    requested at least [REFRESH_INTERVAL_MS] after it was last fetched.
 *  - A cached range is never evicted for being stale; it is only overwritten on
 *    a *successful* online refetch. A failed refetch falls back to the cache.
 */
object MapTileCache {
    /**
     * The single source of truth for the streamed basemap PMTiles URL.
     *
     * Base schema ONLY — the Protomaps layers `style.json` draws. Our own overlays
     * live in [OVERLAY_PMTILES_URL], a separate archive, because at planet scale the
     * base is ~127 GB and the overlays are a couple of GB: joining them would make
     * the merge impossible for the sake of a file 97% of which never changes.
     * See `scripts/maps/build_v5_pmtiles.sh --no-base`.
     */
    const val BASEMAP_PMTILES_URL =
        "pmtiles://https://data.vayunmathur.com/v4.pmtiles"

    /**
     * Our overlay archive: `safety`, `roads`, `transit_lines`, `ma_pois`,
     * `transit_stops` and the three `admin_*` levels, with no base layers.
     *
     * Read by the road overlay and its posted-limit probe
     * ([com.vayunmathur.maps.ui.RoadsSource]), the safety overlay
     * ([com.vayunmathur.maps.ui.SafetyLayersSource]), the ambient POI layer, the
     * transit line/stop overlays and the admin search-highlight.
     *
     * Planet-wide: 36.8 M tiles over z0-16, built by
     * `build_v5_pmtiles.sh --no-base` and streamed by range request like the base.
     */
    const val OVERLAY_PMTILES_URL =
        "pmtiles://https://data.vayunmathur.com/v5-overlay.pmtiles"

    internal const val TILE_HOST = "data.vayunmathur.com"
    private val REFRESH_INTERVAL_MS = 24.hours.inWholeMilliseconds

    internal const val CACHE_DIR_NAME = "tilecache"

    internal const val TAG = "MapTileCache"

    /**
     * Marker written into the cache dir. Entries are wiped when it changes, so it
     * carries the origin host, a format revision, AND both pmtiles URLs: if either
     * is repointed (or its bytes are regenerated under the same name), every cached
     * range keyed off the old files is dropped so we can never serve a stale/short
     * chunk from a previous build. v3 also only ever stores validated 206 partials
     * (see [CachingHttpRequest.load]).
     *
     * BOTH urls have to be in here. The overlays are the half that gets rebuilt —
     * the base is republished almost never — so a marker naming only the basemap
     * would leave a republished overlay serving byte ranges from the previous
     * build's directory, which is the exact failure this marker exists to prevent.
     */
    private const val CACHE_ORIGIN =
        "$TILE_HOST/v3/$BASEMAP_PMTILES_URL|$OVERLAY_PMTILES_URL"

    @Volatile private var installed = false

    /**
     * Install the caching HTTP stack into MapLibre. Idempotent; must run before
     * the first map request (i.e. before the map composable is created).
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        // Prefer external files (excluded from the 25 MB cloud-backup quota,
        // like the downloaded zone pmtiles) and fall back to internal files.
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val cacheDir = File(root, CACHE_DIR_NAME).apply { mkdirs() }
        // Cache migration: entries are keyed by SHA-256(URL+Range) and their
        // meta format is tied to CACHE_ORIGIN. Clear stale entries when the
        // marker differs (host change, or the v1 -> v2 meta format change).
        try {
            val originFile = File(cacheDir, ".origin")
            val currentOrigin = originFile.takeIf { it.exists() }?.readText()?.trim()
            if (currentOrigin != CACHE_ORIGIN) {
                cacheDir.listFiles()?.forEach { f ->
                    if (f.name != ".origin") f.deleteRecursively()
                }
                originFile.writeText(CACHE_ORIGIN)
            }
        } catch (_: Exception) {
            // Best-effort migration; a failure must not break map init.
        }
        MapLibre.setModuleProvider(CachingModuleProvider(appContext, cacheDir))
        installed = true
    }

    /**
     * MapLibre constructs one [HttpRequest] per resource load, so the scope is
     * shared here. Core already bounds how many resource loads are in flight,
     * so [Dispatchers.IO] needs no extra throttling.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val userAgent: String by lazy {
        val identifier = try { HttpIdentifier.getIdentifier() } catch (_: Throwable) { "" }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        toHumanReadableAscii("$identifier MapLibre Android/${Build.VERSION.SDK_INT} ($abi)")
    }

    /** Header values must be ASCII; package/version names are not guaranteed to be. */
    private fun toHumanReadableAscii(s: String): String =
        buildString { for (c in s) if (c.code in 0x20..0x7e) append(c) }.trim()

    private class CachingModuleProvider(
        private val context: Context,
        private val cacheDir: File,
    ) : ModuleProvider {
        override fun createHttpRequest(): HttpRequest = CachingHttpRequest(context, cacheDir)
        override fun createLibraryLoaderProvider(): LibraryLoaderProvider = LibraryLoaderProviderImpl()
    }

    /** The header set [HttpResponder.onResponse] takes, plus the body. */
    private class Loaded(
        val code: Int,
        val eTag: String?,
        val lastModified: String?,
        val cacheControl: String?,
        val expires: String?,
        val retryAfter: String?,
        val xRateLimitReset: String?,
        val body: ByteArray,
    )

    private class CachingHttpRequest(
        private val context: Context,
        private val cacheDir: File,
    ) : HttpRequest {

        @Volatile private var job: Job? = null

        override fun executeRequest(
            responder: HttpResponder,
            nativePtr: Long,
            resourceUrl: String,
            dataRange: String,
            etag: String,
            modified: String,
            offlineUsage: Boolean,
        ) {
            job = scope.launch {
                val loaded = try {
                    load(resourceUrl, dataRange, etag, modified, offlineUsage)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "FAILURE url=$resourceUrl range=$dataRange etag=$etag modified=$modified " +
                            "type=${failureType(e)} ex=${e.javaClass.name} msg=${e.message}",
                        e,
                    )
                    responder.handleFailure(
                        failureType(e),
                        e.message ?: "Error processing the request",
                    )
                    return@launch
                }
                responder.onResponse(
                    loaded.code,
                    loaded.eTag,
                    loaded.lastModified,
                    loaded.cacheControl,
                    loaded.expires,
                    loaded.retryAfter,
                    loaded.xRateLimitReset,
                    loaded.body,
                )
            }
        }

        override fun cancelRequest() {
            // Expected for tiles that were prefetched but are no longer needed.
            job?.cancel()
        }

        private suspend fun load(
            resourceUrl: String,
            dataRange: String,
            etag: String,
            modified: String,
            offlineUsage: Boolean,
        ): Loaded {
            val uri = runCatching { resourceUrl.toUri() }.getOrNull()
            val host = uri?.host?.lowercase().orEmpty()
            val querySize = runCatching { uri?.queryParameterNames?.size ?: 0 }.getOrDefault(0)
            val url = HttpRequestUrl.buildResourceUrl(host, resourceUrl, querySize, offlineUsage)

            // Only cache PMTiles range requests — TILE_HOST also serves
            // amenities.db, road_names.bin etc which must NOT be cached here.
            val cacheable = host == TILE_HOST && uri?.encodedPath?.contains(".pmtiles") == true
            if (!cacheable) return fetch(url, dataRange, etag, modified)

            val expectedLen = expectedRangeLength(dataRange)

            val key = keyFor(url, dataRange)
            val dataFile = File(cacheDir, "$key.data")
            val metaFile = File(cacheDir, "$key.meta")
            val cached = dataFile.exists() && metaFile.exists()
            val fresh = cached &&
                System.currentTimeMillis() - dataFile.lastModified() < REFRESH_INTERVAL_MS

            // Serve from cache without touching the network when the entry is
            // still fresh, or whenever we're offline (stale-but-usable).
            if (cached && (fresh || !isOnline())) {
                readCache(dataFile, metaFile)?.let {
                    if (isValidRangeBody(it, expectedLen)) {
                        Log.d(TAG, "cache HIT url=$url range=$dataRange status=${it.code} bytes=${it.body.size} expected=$expectedLen fresh=$fresh")
                        return it
                    }
                    // Corrupt/short cached entry: drop it and fall through to network.
                    Log.w(TAG, "cache DROP (short/invalid) url=$url range=$dataRange status=${it.code} bytes=${it.body.size} expected=$expectedLen")
                    dataFile.delete(); metaFile.delete()
                }
            }

            val networkResponse = try {
                fetch(url, dataRange, etag, modified)
            } catch (e: IOException) {
                // Network error (e.g. went offline mid-session): fall back to
                // the stale cache if we have it, otherwise propagate.
                if (cached) readCache(dataFile, metaFile)?.let { return it }
                throw e
            }

            if (networkResponse.code !in 200..299) {
                // Server error / 304: keep serving the existing cache rather
                // than replacing it.
                if (cached) readCache(dataFile, metaFile)?.let {
                    if (isValidRangeBody(it, expectedLen)) return it
                }
                return networkResponse
            }

            // Only cache a body we trust: a 206 partial whose length matches the
            // requested range. A 200 (whole-file) reply to a range request, or a
            // byte count that doesn't match, must never be stored — that is what
            // produced the "Prefix string too short" pmtiles header failures.
            if (isValidRangeBody(networkResponse, expectedLen)) {
                writeCache(dataFile, metaFile, networkResponse)
            } else {
                Log.w(TAG, "skip-cache (unexpected body) url=$url range=$dataRange status=${networkResponse.code} bytes=${networkResponse.body.size} expected=$expectedLen")
            }
            return networkResponse
        }

        private suspend fun fetch(
            url: String,
            dataRange: String,
            etag: String,
            modified: String,
        ): Loaded {
            val headers = buildMap {
                put("User-Agent", userAgent)
                if (dataRange.isNotEmpty()) put("Range", dataRange)
                if (etag.isNotEmpty()) {
                    put("If-None-Match", etag)
                } else if (modified.isNotEmpty()) {
                    put("If-Modified-Since", modified)
                }
            }
            val response = NetworkClient.execute(url, "GET", headers)
            Log.d(
                TAG,
                "network url=$url range=$dataRange status=${response.status} " +
                    "bytes=${response.bytes.size} contentRange=${response.header("Content-Range")}",
            )
            return Loaded(
                code = response.status,
                eTag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                cacheControl = response.header("Cache-Control"),
                expires = response.header("Expires"),
                retryAfter = response.header("Retry-After"),
                xRateLimitReset = response.header("x-rate-limit-reset"),
                body = response.bytes,
            )
        }

        /**
         * Number of bytes a `bytes=start-end` range asks for, or null when the
         * range is open-ended / unparseable (then we can't length-check).
         */
        private fun expectedRangeLength(dataRange: String): Long? {
            val spec = dataRange.substringAfter("bytes=", "").substringBefore(",").trim()
            if (spec.isEmpty()) return null
            val start = spec.substringBefore("-").toLongOrNull() ?: return null
            val end = spec.substringAfter("-", "").toLongOrNull() ?: return null
            if (end < start) return null
            return end - start + 1
        }

        /**
         * A cacheable pmtiles range reply is only trustworthy when the server
         * honoured the range: HTTP 206 and (when we know the requested length)
         * exactly that many bytes. A 200 whole-file reply or a truncated body is
         * rejected so it is never stored or served as a tile/header chunk.
         */
        private fun isValidRangeBody(loaded: Loaded, expectedLen: Long?): Boolean {
            if (loaded.code != 206) return false
            if (loaded.body.isEmpty()) return false
            if (expectedLen != null && loaded.body.size.toLong() != expectedLen) return false
            return true
        }

        private fun readCache(dataFile: File, metaFile: File): Loaded? {
            return try {
                val props = Properties()
                metaFile.inputStream().use { props.load(it) }
                Loaded(
                    code = props.getProperty(KEY_CODE, "200").toIntOrNull() ?: 200,
                    eTag = props.getProperty(KEY_ETAG),
                    lastModified = props.getProperty(KEY_LAST_MODIFIED),
                    cacheControl = props.getProperty(KEY_CACHE_CONTROL),
                    expires = props.getProperty(KEY_EXPIRES),
                    retryAfter = props.getProperty(KEY_RETRY_AFTER),
                    xRateLimitReset = props.getProperty(KEY_RATE_LIMIT_RESET),
                    body = dataFile.readBytes(),
                )
            } catch (_: Exception) {
                // Unreadable/corrupt entry: fall through to the network.
                null
            }
        }

        private fun writeCache(dataFile: File, metaFile: File, loaded: Loaded) {
            try {
                val props = Properties().apply {
                    setProperty(KEY_CODE, loaded.code.toString())
                    loaded.eTag?.let { setProperty(KEY_ETAG, it) }
                    loaded.lastModified?.let { setProperty(KEY_LAST_MODIFIED, it) }
                    loaded.cacheControl?.let { setProperty(KEY_CACHE_CONTROL, it) }
                    loaded.expires?.let { setProperty(KEY_EXPIRES, it) }
                    loaded.retryAfter?.let { setProperty(KEY_RETRY_AFTER, it) }
                    loaded.xRateLimitReset?.let { setProperty(KEY_RATE_LIMIT_RESET, it) }
                }
                // Write meta first, then data, each via temp+rename so a reader
                // never sees a half-written file. Presence of the data file then
                // implies the meta file is already in place.
                val metaTmp = File.createTempFile("meta", null, cacheDir)
                metaTmp.outputStream().use { props.store(it, null) }
                metaTmp.renameTo(metaFile)

                val dataTmp = File.createTempFile("data", null, cacheDir)
                dataTmp.outputStream().use { it.write(loaded.body) }
                dataTmp.renameTo(dataFile)
                dataFile.setLastModified(System.currentTimeMillis())
            } catch (_: Exception) {
                // Caching is best-effort; a write failure must not break the map.
            }
        }

        private fun isOnline(): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun keyFor(url: String, range: String?): String {
            val raw = url + "\n" + (range ?: "")
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Mirrors MapLibre's own HttpRequestImpl classification. */
        private fun failureType(e: Exception): Int = when {
            e is NoRouteToHostException || e is UnknownHostException ||
                e is SocketException || e is ProtocolException || e is SSLException ->
                HttpRequest.CONNECTION_ERROR
            e is InterruptedIOException -> HttpRequest.TEMPORARY_ERROR
            // library:network wraps connect failures in a plain IOException.
            e is IOException && e.cause.let {
                it is UnknownHostException || it is SocketException || it is NoRouteToHostException
            } -> HttpRequest.CONNECTION_ERROR
            else -> HttpRequest.PERMANENT_ERROR
        }
    }

    private const val KEY_CODE = "code"
    private const val KEY_ETAG = "etag"
    private const val KEY_LAST_MODIFIED = "lastModified"
    private const val KEY_CACHE_CONTROL = "cacheControl"
    private const val KEY_EXPIRES = "expires"
    private const val KEY_RETRY_AFTER = "retryAfter"
    private const val KEY_RATE_LIMIT_RESET = "rateLimitReset"
}
