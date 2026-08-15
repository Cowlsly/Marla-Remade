package com.vayunmathur.web.platform.shields

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.brotli.dec.BrotliInputStream
import java.io.File

private const val TAG = "ShieldsEngine"
private const val ASSET_DIR = "shields"
private const val CACHE_DIR = "shields"
private const val CACHE_FILE = "engine.dat"
private const val CACHE_VERSION_FILE = "engine.version"

/** Outcome of [ShieldsEngine.check] for one network request. */
@Serializable
data class ShieldsCheck(
    val blocked: Boolean = false,
    val important: Boolean = false,
    val exception: Boolean = false,
    /** `data:` URL holding the uBO resource body named by a `$redirect` rule. */
    val redirect: String? = null,
    /** The URL with `$removeparam` parameters stripped. Only meaningful when not blocked. */
    val rewritten: String? = null,
)

/** Everything needed to prepare a page before it starts loading. */
@Serializable
data class CosmeticResources(
    val hide: List<String> = emptyList(),
    val procedural: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val script: String = "",
    @SerialName("generichide") val genericHide: Boolean = false,
)

/**
 * Process-wide handle on Brave's adblock engine.
 *
 * Parsing ~8 MB of filter lists takes seconds, so [load] deserializes a cached snapshot
 * whenever the bundled assets have not changed, and only reparses (then rewrites the
 * snapshot) after an asset update.
 *
 * Until [ready] flips true every query returns null and callers must fail open:
 * `shouldInterceptRequest` runs on the render thread and blocking it stalls the page.
 */
object ShieldsEngine {

    @Volatile
    private var handle: Long = 0

    @Volatile
    private var loading = false

    val ready: Boolean get() = handle != 0L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(context: Context) {
        if (ready || loading || !ShieldsNative.isAvailable) return
        loading = true
        try {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val version = app.assets.open("$ASSET_DIR/version.txt").use {
                    it.readBytes().decodeToString().trim()
                }
                val resources = readBrotliAsset(app, "resources.json")
                val cacheDir = File(app.filesDir, CACHE_DIR)
                val cache = File(cacheDir, CACHE_FILE)
                val cachedVersion = File(cacheDir, CACHE_VERSION_FILE)

                var built = 0L
                if (cache.isFile && cachedVersion.isFile && cachedVersion.readText().trim() == version) {
                    built = ShieldsNative.nativeCreateFromCache(cache.readBytes(), resources)
                    if (built == 0L) Log.w(TAG, "engine cache rejected, reparsing lists")
                }
                if (built == 0L) {
                    built = ShieldsNative.nativeCreate(readBrotliAsset(app, "filters.txt"), resources)
                    if (built != 0L) writeCache(cacheDir, cache, cachedVersion, built, version)
                }
                handle = built
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed — shields stay open", e)
        } finally {
            loading = false
        }
    }

    private fun writeCache(dir: File, cache: File, versionFile: File, handle: Long, version: String) {
        try {
            val snapshot = ShieldsNative.nativeSerialize(handle) ?: return
            dir.mkdirs()
            cache.writeBytes(snapshot)
            versionFile.writeText(version)
        } catch (e: Exception) {
            Log.w(TAG, "could not cache engine snapshot", e)
        }
    }

    private fun readBrotliAsset(context: Context, name: String): String =
        BrotliInputStream(context.assets.open("$ASSET_DIR/$name.br")).use {
            it.readBytes().decodeToString()
        }

    /**
     * Should the request for [url], issued by the page at [sourceUrl], be blocked?
     * Returns null while the engine is still loading, which means "allow".
     */
    fun check(url: String, sourceUrl: String, requestType: String): ShieldsCheck? {
        val h = handle
        if (h == 0L) return null
        val raw = ShieldsNative.nativeCheck(h, url, sourceUrl, requestType) ?: return null
        return runCatching { json.decodeFromString<ShieldsCheck>(raw) }.getOrNull()
    }

    fun cosmetic(url: String): CosmeticResources? {
        val h = handle
        if (h == 0L) return null
        val raw = ShieldsNative.nativeCosmeticResources(h, url) ?: return null
        return runCatching { json.decodeFromString<CosmeticResources>(raw) }.getOrNull()
    }

    /** Generic hide rules matching classes and ids that only appeared after page load. */
    fun hiddenClassIdSelectors(
        classes: List<String>,
        ids: List<String>,
        exceptions: List<String>,
    ): List<String> {
        val h = handle
        if (h == 0L) return emptyList()
        val raw = ShieldsNative.nativeHiddenClassIdSelectors(
            h,
            json.encodeToString(classes),
            json.encodeToString(ids),
            json.encodeToString(exceptions),
        ) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }
}
