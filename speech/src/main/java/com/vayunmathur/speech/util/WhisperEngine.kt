package com.vayunmathur.speech.util

import android.content.Context

/**
 * Speech-to-text entry point for [com.vayunmathur.speech.service.WhisperRecognitionService].
 *
 * Kept as a thin seam over [WhisperOnnxEngine] so the recognition service is agnostic about the
 * backend: swapping the int8 ONNX path back for the ncnn AAR (or forward to a larger model) is a
 * change to this one file. Not thread-safe — call [transcribe] from a single worker thread.
 */
class WhisperEngine(context: Context) {

    private val engine = WhisperOnnxEngine(context.applicationContext)

    /**
     * Whether the recogniser can run. The models ship in the APK, so this is now only false if
     * they fail to load — never because something still needs downloading.
     */
    fun isModelPresent(): Boolean = engine.isAvailable()

    /** Load the model now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = engine.isAvailable()

    /**
     * Transcribe [pcm16k] (16 kHz mono). [language] is ISO-639-1 or null/"auto" for automatic
     * detection. Returns the text, or null if the model isn't ready/failed.
     */
    fun transcribe(pcm16k: ShortArray, language: String?): String? =
        engine.transcribe(pcm16k, language)

    fun close() = engine.close()
}
