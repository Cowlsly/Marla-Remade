package com.vayunmathur.translate.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.library.ml.Small100Handle
import com.vayunmathur.translate.domain.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TranslationEngine] backed by [Small100Handle] — SMaLL-100 on `:library:ml`'s Vulkan runtime.
 *
 * The model is loaded lazily from [Small100Model.modelDir] the first time it is needed, and only if
 * the two files have been downloaded; until then [isAvailable] is false and the UI shows a download
 * prompt. Language detection is unnecessary — SMaLL-100 needs only the target language — so
 * [detectLanguage] returns null.
 *
 * # The lock is not only for the handle
 *
 * A translation re-records the network once per decoded token, so two concurrent calls would
 * interleave recordings of the same command buffer. The mutex therefore covers the whole of
 * [translate] rather than just the load, and [close] too.
 */
class Small100Translator(private val context: Context) : TranslationEngine {

    private val lock = Mutex()
    private var model: Small100Handle? = null
    private var attempts = 0
    private var closed = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        lock.withLock { ensure() != null }
    }

    override suspend fun detectLanguage(text: String): String? = null

    override suspend fun translate(text: String, from: String?, to: String): String? =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext ""
            val target = Small100Model.LANG_ID[to] ?: return@withContext null
            lock.withLock {
                val handle = ensure() ?: return@withContext null
                try {
                    handle.translate(text, target)?.ifBlank { null }
                } catch (t: Throwable) {
                    Log.e(TAG, "translate failed", t)
                    null
                }
            }
        }

    /** Call after a successful model download so the next use loads it. */
    suspend fun reset() = lock.withLock { attempts = 0 }

    /**
     * Free the model. Terminal: nothing loads again afterwards, and it is idempotent.
     *
     * Deliberately **not** `suspend`, because the only caller is `ViewModel.onCleared`, which is
     * not. That means it does not take [lock], so it must not run while a translation is in flight —
     * which `onCleared` guarantees: `viewModelScope` is cancelled before it runs and the UI cannot
     * ask for another translation once the view model is cleared.
     */
    fun close() {
        closed = true
        model?.close()
        model = null
    }

    /**
     * The loaded model, loading it if the files are present.
     *
     * Retries are capped at [MAX_ATTEMPTS] rather than latched on the first failure, because the
     * one recoverable cause — a download that finished between two calls — is exactly the case a
     * latch would make permanent. Anything else fails twice and stays failed.
     */
    private fun ensure(): Small100Handle? {
        model?.let { return it }
        if (closed || attempts >= MAX_ATTEMPTS) return null
        if (!Small100Model.isDownloaded(context)) return null
        attempts++
        val reclaimed = Small100Model.deleteRetired(context)
        if (reclaimed > 0) {
            Log.i(TAG, "reclaimed $reclaimed bytes of retired ncnn weights")
        }
        val handle = Small100Handle.inDirectory(Small100Model.modelDir(context))
        if (!handle.isAvailable) {
            handle.close()
            return null
        }
        model = handle
        return handle
    }

    companion object {
        private const val TAG = "Small100Translator"

        /** One retry, for a download that completed between two calls. See `ensure`. */
        private const val MAX_ATTEMPTS = 2
    }
}
