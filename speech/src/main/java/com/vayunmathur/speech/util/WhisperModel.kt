package com.vayunmathur.speech.util

import android.content.Context

/**
 * Asset location of the offline **Whisper-base** (multilingual, ~99 languages) recogniser.
 *
 * These files used to be a 117 MB runtime download of the ncnn conversion. They are now the int8
 * ONNX export bundled in the APK under `assets/`[DIR] and read by [WhisperOnnxEngine], so there
 * is no download, no progress and no mirror involved — see
 * `scripts/speech/fetch_whisper_onnx.sh` for how the vendored copies are refreshed.
 */
object WhisperModel {
    const val DIR = "whisper-base"

    /**
     * True if the bundled models are readable. Barring a corrupt install this is always true;
     * it exists so the setup screen can report a broken build rather than silently failing to
     * transcribe.
     */
    fun isReady(context: Context): Boolean = try {
        context.assets.list(DIR)?.contains("encoder_model_int8.onnx") == true
    } catch (_: Throwable) {
        false
    }
}
