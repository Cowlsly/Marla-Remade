package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Backward-compat shim over [PiperVoiceRegistry]. The app previously had a single
 * hard-coded voice `piper/voice` (Amy medium, voice3.zip, 22050 Hz, 125k dict).
 *
 * After multilingual expansion the canonical location is
 * `piper/voices/<bcp47>/<id>/` (e.g. `piper/voices/en-US/en_US-amy-medium`).
 * This object now delegates to the default English entry in the registry and runs
 * legacy migration (rename `piper/voice` -> new path) so existing installs keep
 * working without re-download.
 *
 * New code should use [PiperVoiceRegistry] directly. This shim remains for
 * [com.vayunmathur.speech.service.PiperTtsService] and
 * [com.vayunmathur.speech.tts.CheckVoiceDataActivity] during the transition and for
 * any external callers that still reference `PiperModel`.
 */
object PiperModel {
    const val DIR = PiperVoiceRegistry.DIR
    const val DICT = "en-word_id.bin"
    const val CONFIG = "config.json"

    private const val ENCODER_SUFFIX = "_enc_p.maml"
    private val REQUIRED_NETS = listOf("_enc_p", "_dp", "_flow", "_dec")
    private const val BASE = PiperVoiceRegistry.BASE
    const val REMOTE_ARCHIVE = PiperVoiceRegistry.LEGACY_REMOTE_ARCHIVE
    private const val ARCHIVE = PiperVoiceRegistry.LEGACY_ARCHIVE

    /** Single downloadable archive (deprecated alias) — use [PiperVoiceRegistry] for new voices. */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}${REMOTE_ARCHIVE}",
            ARCHIVE,
            "Piper voice (TTS)",
            "49a18080c2e97b066854d2a5360443275ef3041c7524fcc023b7efdcb063952c",
        ),
    )

    private fun rootDir(context: Context): File? =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun archive(context: Context): File {
        val root = rootDir(context) ?: return File(ARCHIVE)
        return File(root, ARCHIVE)
    }

    /** Extracted voice dir — now delegates to registry default en (+ legacy fallback). */
    fun voiceDir(context: Context): File {
        // Prefer new location if migrated; otherwise legacy.
        val newDir = PiperVoiceRegistry.voiceDir(context, PiperVoiceRegistry.DEFAULT)
        return if (newDir.isDirectory && PiperVoiceRegistry.isExtracted(context, PiperVoiceRegistry.DEFAULT)) {
            newDir
        } else {
            val legacy = PiperVoiceRegistry.legacyVoiceDir(context)
            if (legacy.isDirectory) legacy else newDir
        }
    }

    fun voicePrefix(context: Context): String? {
        val def = PiperVoiceRegistry.DEFAULT
        val prefix = PiperVoiceRegistry.voicePrefix(context, def)
        if (prefix != null) return prefix
        // Legacy fallback
        val files = PiperVoiceRegistry.legacyVoiceDir(context).listFiles() ?: return null
        val encoder = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return null
        return encoder.name.removeSuffix(ENCODER_SUFFIX)
    }

    /** True if default en voice is extracted (new or legacy location). */
    fun isExtracted(context: Context): Boolean {
        if (PiperVoiceRegistry.isExtracted(context, PiperVoiceRegistry.DEFAULT)) return true
        // Legacy dir check (forces migration eligibility)
        return PiperVoiceRegistry.let { registry ->
            try {
                val legacyDir = registry.legacyVoiceDir(context)
                if (!legacyDir.isDirectory) return@let false
                val files = legacyDir.listFiles() ?: return@let false
                if (files.any { it.name.endsWith(".onnx") }) return@let false
                val dictFile = File(legacyDir, DICT)
                if (dictFile.exists() && dictFile.length() < 1_000_000L) return@let false
                val enc = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return@let false
                val pref = enc.name.removeSuffix(ENCODER_SUFFIX)
                REQUIRED_NETS.all { net ->
                    File(legacyDir, "$pref$net.maml").exists()
                } && dictFile.exists() && File(legacyDir, CONFIG).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun isReady(context: Context): Boolean {
        val dir = PiperVoiceRegistry.legacyVoiceDir(context)
        if (dir.isDirectory) {
            val legacy = dir.listFiles()?.any { it.name.endsWith(".onnx") } == true ||
                File(dir, "tokens.txt").exists() ||
                File(dir, "espeak-ng-data").isDirectory ||
                File(dir, DICT).let { it.exists() && it.length() < 1_000_000L }
            if (legacy) {
                Log.d(TAG, "deleting legacy/broken voice at $dir for migration to ncnn full dict")
                dir.deleteRecursively()
            }
        }
        // Migrate if needed so new path becomes truth.
        PiperVoiceRegistry.migrateLegacyIfNeeded(context)
        return PiperVoiceRegistry.isExtracted(context, PiperVoiceRegistry.DEFAULT) ||
            isExtracted(context)
    }

    suspend fun download(context: Context, ds: DataStoreUtils) {
        PiperVoiceRegistry.migrateLegacyIfNeeded(context)
        PiperVoiceRegistry.download(context, ds, listOf("en"))
    }

    fun progress(ds: DataStoreUtils): Float =
        PiperVoiceRegistry.progress(ds, PiperVoiceRegistry.DEFAULT)

    @Synchronized
    fun installIfNeeded(context: Context): Boolean {
        PiperVoiceRegistry.migrateLegacyIfNeeded(context)
        if (PiperVoiceRegistry.isExtracted(context, PiperVoiceRegistry.DEFAULT)) return true
        if (isExtracted(context) && PiperVoiceRegistry.legacyVoiceDir(context).isDirectory) {
            // Migrate legacy to new location
            PiperVoiceRegistry.migrateLegacyIfNeeded(context)
            if (PiperVoiceRegistry.isExtracted(context, PiperVoiceRegistry.DEFAULT)) return true
        }
        return PiperVoiceRegistry.installIfNeeded(context, PiperVoiceRegistry.DEFAULT)
    }

    private const val TAG = "PiperModel"
}
