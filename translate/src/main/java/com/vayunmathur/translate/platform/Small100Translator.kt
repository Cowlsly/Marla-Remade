package com.vayunmathur.translate.util

import android.content.Context
import android.util.Log
import com.vayunmathur.ncnn.Small100
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TranslationEngine] backed by the ncnn AAR's [Small100] (on-device SMaLL-100).
 * The model is loaded lazily from [Small100Model.modelDir] the first time it's
 * needed and only if the files have been downloaded; until then [isAvailable] is
 * false and the UI shows a download prompt. Language detection is unnecessary —
 * SMaLL-100 only needs the target language — so [detectLanguage] returns null.
 */
class Small100Translator(private val context: Context) : TranslationEngine {

    private val lock = Mutex()
    private var model: Small100? = null
    private var loadFailed = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        lock.withLock { ensure() }
    }

    override suspend fun detectLanguage(text: String): String? = null

    override suspend fun translate(text: String, from: String?, to: String): String? =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext ""
            val tgtId = Small100Model.LANG_ID[to] ?: return@withContext null
            lock.withLock {
                if (!ensure()) return@withContext null
                try {
                    model?.translate(text, tgtId)?.ifBlank { null }
                } catch (t: Throwable) {
                    Log.e(TAG, "translate failed", t)
                    null
                }
            }
        }

    /** Call after a successful model download so the next use loads it. */
    suspend fun reset() = lock.withLock { loadFailed = false }

    fun close() {
        model?.close()
        model = null
    }

    /** Load the ncnn model once, if the files are present. Returns whether it's ready. */
    private fun ensure(): Boolean {
        model?.let { return true }
        if (loadFailed) return false
        if (!Small100Model.isDownloaded(context)) return false
        return try {
            val m = Small100(Small100Model.modelDir(context).absolutePath)
            if (m.isAvailable) {
                model = m
                true
            } else {
                m.close()
                loadFailed = true
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Small100 load failed", t)
            loadFailed = true
            false
        }
    }

    companion object {
        private const val TAG = "Small100Translator"
    }
}
