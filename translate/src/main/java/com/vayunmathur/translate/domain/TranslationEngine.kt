package com.vayunmathur.translate.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device translation, behind a clean interface so a real neural backend can
 * be dropped in without touching the UI. All calls are safe to invoke from a
 * coroutine and run off the main thread.
 */
interface TranslationEngine {
    /** True if a translation model is loaded and translation will actually work. */
    suspend fun isAvailable(): Boolean

    /** Best-effort ISO-639-1 language code for [text], or null if unknown. */
    suspend fun detectLanguage(text: String): String?

    /**
     * Translate [text] to [to]. [from] is the source language code, or null to
     * auto-detect. Returns null if the engine is unavailable (caller shows a
     * "model not installed" state) — never a fabricated translation.
     */
    suspend fun translate(text: String, from: String?, to: String): String?
}
