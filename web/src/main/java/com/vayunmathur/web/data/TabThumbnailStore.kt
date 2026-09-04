package com.vayunmathur.web.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.mutableStateMapOf
import com.vayunmathur.web.platform.captureThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TabThumbnailStore"
private const val DIR_NAME = "tab-thumbs"
private const val EXT = ".webp"
private const val QUALITY = 70

/** Tab ids are UUIDs; anything else is refused rather than turned into a file path. */
private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * Page thumbnails, keyed by tab id.
 *
 * Process-wide rather than per-[com.vayunmathur.web.platform.WebViewModel] because the cache
 * directory is shared by every window and has to be reaped from `MainActivity`, before any
 * view model exists.
 *
 * Private tabs and incognito windows are memory-only, mirroring `persistTabsSync`.
 */
object TabThumbnailStore {

    /**
     * Snapshot state so a capture that lands later recomposes the grid. A null value means
     * "looked on disk, nothing there" and stops [get] from re-reading on every recomposition.
     */
    private val cache = mutableStateMapOf<String, Bitmap?>()

    /** Disk reads in flight, so a grid of tiles asking at once only reads each file once. */
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The thumbnail for [tabId], or null when there isn't one yet — a tab restored from a cold
     * start has no WebView to draw until it is visited. Compose-observable; a miss starts a
     * disk read that recomposes the caller if it finds something. Main thread only.
     */
    fun get(tabId: String): Bitmap? {
        if (cache.containsKey(tabId)) return cache[tabId]
        loadFromDisk(tabId)
        return null
    }

    /**
     * Draws [webView] and stores the result. The draw has to happen on the main thread and on
     * the caller's frame — that is the whole point, it is what makes the tile match what the
     * user is looking at — but compressing and writing it are handed off.
     */
    fun capture(tabId: String, webView: WebView, isPrivate: Boolean) {
        val bitmap = captureThumb(webView) ?: return
        cache[tabId] = bitmap
        if (isPrivate) return
        val file = fileFor(tabId) ?: return
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
            }.onFailure { Log.w(TAG, "thumbnail write failed", it) }
        }
    }

    /** Drops a closed tab's thumbnail from memory and disk. */
    fun remove(tabId: String) {
        cache.remove(tabId)
        val file = fileFor(tabId) ?: return
        scope.launch { runCatching { file.delete() } }
    }

    /**
     * Deletes cached thumbnails for tabs that no longer exist in any window. Without this the
     * directory keeps a file per tab the user has ever opened.
     */
    fun retainOnly(liveTabIds: Set<String>) {
        val dir = dir() ?: return
        scope.launch {
            runCatching {
                dir.listFiles()?.forEach { file ->
                    if (file.name.removeSuffix(EXT) !in liveTabIds) file.delete()
                }
            }.onFailure { Log.w(TAG, "thumbnail reap failed", it) }
        }
    }

    private fun loadFromDisk(tabId: String) {
        // Bail before recording anything if there is no usable file path: caching a negative
        // here would make the entry permanently absent, even after [init] later supplies a
        // context.
        val file = fileFor(tabId) ?: return
        if (!loading.add(tabId)) return
        scope.launch {
            val bitmap = runCatching {
                file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                // A capture may have landed while the read was in flight; it is newer than
                // anything on disk, so it wins.
                if (!cache.containsKey(tabId)) cache[tabId] = bitmap
                loading.remove(tabId)
            }
        }
    }

    private fun dir(): File? = appContext?.let { File(it.cacheDir, DIR_NAME) }

    private fun fileFor(tabId: String): File? {
        if (!SAFE_ID.matches(tabId)) return null
        return dir()?.let { File(it, tabId + EXT) }
    }
}
