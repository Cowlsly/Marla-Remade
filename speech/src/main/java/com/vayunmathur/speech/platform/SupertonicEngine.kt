package com.vayunmathur.speech.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.library.ml.SupertonicSynthesizer
import com.vayunmathur.speech.domain.SupertonicVoices
import java.io.File

/**
 * The one Supertonic handle the TTS service speaks through.
 *
 * Replaced `PiperEngine`, which kept an access-ordered LRU of two `PiperSynthesizer`s because a
 * Piper voice *was* a set of networks — changing language meant closing one 80 MB model and
 * opening another. Supertonic has one set of networks for all 31 languages, and a voice is two
 * small style tensors, so there is one handle for the life of the service and switching voices
 * uploads nothing. That is what deleted the cache, the eviction, the per-voice sample rates and
 * the directory resolution along with it.
 *
 * # Threading
 *
 * [SupertonicSynthesizer] is not thread-safe, so every call here is synchronised on this object.
 * `TextToSpeechService` serialises `onSynthesizeText` itself, but [preload] runs on a background
 * thread from `onCreate` and [close] arrives from `onDestroy`, so the three can overlap.
 */
class SupertonicEngine(private val context: Context) {
    private val lock = Any()
    private var synthesizer: SupertonicSynthesizer? = null
    private var loaded: SupertonicSynthesizer? = null
    private var voice: String = SupertonicVoices.DEFAULT_VOICE

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int get() = SupertonicSynthesizer.SAMPLE_RATE

    /**
     * Bring the four networks up, returning whether they came up. Idempotent and cheap after the
     * first call, so the service can call it on every utterance as well as warming it at startup.
     *
     * Slow the first time: ~105 MB of weights are streamed out of the APK into GPU memory, and four
     * command buffers are recorded. That is why `onCreate` warms it off the main thread.
     */
    fun preload(): Boolean = synchronized(lock) { ensure() != null }

    /**
     * Switch to `style`, returning whether it took.
     *
     * A 25 KB read and an input swap, not a reload, so this is safe to call per utterance. An
     * unknown style is refused rather than ignored: silently reading every voice in `F1` would be
     * indistinguishable from the voice picker not working.
     */
    fun voice(style: String): Boolean = synchronized(lock) {
        if (style !in SupertonicVoices.VOICES) return false
        if (style == voice) return ensure() != null
        val engine = ensure() ?: return false
        if (!engine.voice(style)) return false
        voice = style
        true
    }

    /**
     * Synthesise `text` and hand the samples to `onChunk` in pieces, stopping early if it returns
     * false.
     *
     * Chunked rather than returned whole because the framework wants audio as it is produced and a
     * paragraph of 44.1 kHz mono is megabytes. The whole utterance is synthesised before the first
     * chunk is delivered — Supertonic's sampler is one pass over the entire sequence, so there is no
     * streaming to be had inside it — so this splits the finished waveform rather than pipelining.
     *
     * There is no `speed`: [SupertonicSynthesizer.synthesize] takes none, and the only ways to fake
     * one on the host are to resample the PCM, which shifts the pitch, or to time-stretch it, which
     * is a signal-processing feature rather than part of a port. Rate control therefore does not
     * work in this engine; changing it in the system settings has no effect. Doing it properly means
     * scaling the duration predictor's frame count natively, which is where Piper's `length_scale`
     * did it too.
     */
    fun synthesize(text: String, onChunk: (FloatArray) -> Boolean): Boolean = synchronized(lock) {
        val engine = ensure() ?: return false
        val samples = try {
            engine.synthesize(text)
        } catch (e: Throwable) {
            Log.e(TAG, "synthesis failed", e)
            return false
        }
        if (samples.isEmpty()) return false
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(CHUNK_SAMPLES, samples.size - offset)
            if (!onChunk(samples.copyOfRange(offset, offset + n))) return false
            offset += n
        }
        true
    }

    /** Free the four networks. Idempotent. */
    fun close() = synchronized(lock) {
        synthesizer?.close()
        synthesizer = null
        loaded = null
    }

    /**
     * The handle, built on first use.
     *
     * A failure is not retried: `SupertonicSynthesizer`'s constructor never throws, and the reasons
     * it can come up unavailable — no `libmodelrunner.so` for this ABI, no Vulkan device with fp16
     * compute, a missing asset — are all properties of the install rather than transient. Retrying
     * would re-read the bundle on every utterance to fail the same way.
     */
    private fun ensure(): SupertonicSynthesizer? {
        loaded?.let { return it }
        if (synthesizer != null) return null
        val built = SupertonicSynthesizer.inAssets(context.assets, voice = voice)
        synthesizer = built
        if (!built.isAvailable) {
            Log.e(TAG, "the Supertonic bundle is not usable on this device")
            return null
        }
        loaded = built
        return built
    }

    private companion object {
        const val TAG = "SupertonicEngine"

        /**
         * Samples per chunk handed to the callback.
         *
         * The service splits these again against `SynthesisCallback.maxBufferSize`, which is what
         * actually bounds a write, so this only decides how much PCM is converted at a time. 4,096
         * floats is 8 KB of PCM16 and 93 ms of audio at 44.1 kHz.
         */
        const val CHUNK_SAMPLES = 4096
    }
}

/**
 * Whether the Supertonic bundle is in the APK, without bringing up a GPU or reading a weight.
 *
 * This is what became of `PiperVoiceRegistry.isExtracted`. That answered a *per-language* question
 * — is this voice's 80 MB on disk — and six of the eleven `TextToSpeechService` overrides consulted
 * it, which is most of what made that service as long as it was. One bundle serves all 31 languages
 * now, so the question has one answer for the whole install, and it collapses to this.
 *
 * It is still worth asking rather than assuming true. The bundle is ~105 MB of assets, and an
 * install that somehow lacks it should advertise no languages at all rather than advertise 31 and
 * fail on the first utterance — a TTS engine that is selectable but silent has no error surface
 * anywhere the user can see.
 *
 * Cheap: `AssetManager.list` on one directory, no asset opened. Cached, because the answer cannot
 * change while the process lives.
 */
object SupertonicBundle {
    @Volatile private var present: Boolean? = null

    fun isPresent(context: Context): Boolean {
        present?.let { return it }
        val found = try {
            val entries = context.assets.list(SupertonicSynthesizer.ASSET_PATH)?.toSet().orEmpty()
            REQUIRED.all { it in entries }
        } catch (e: Throwable) {
            Log.e(TAG, "cannot list the Supertonic assets", e)
            false
        }
        present = found
        return found
    }

    /**
     * Delete the `piper/` tree an upgraded install still has under `getExternalFilesDir`.
     *
     * Up to **1.8 GB** of voices that nothing reads any more: Piper downloaded and unzipped one
     * model per language there, and removing the code that managed them does not remove them. An
     * upgrade would otherwise keep that space allocated until the user found it themselves.
     *
     * Best effort and silent. It runs off the main thread from the TTS service's `onCreate`, once
     * per process, and a failure means the files stay — there is nothing useful to tell the user
     * about a directory they did not know existed.
     */
    fun deleteOrphanedPiperVoices(context: Context) {
        val root = File(context.getExternalFilesDir(null), "piper")
        if (!root.isDirectory) return
        val freed = runCatching {
            val bytes = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            root.deleteRecursively()
            bytes
        }.getOrNull() ?: return
        Log.i(TAG, "removed ${freed / (1024 * 1024)} MB of Piper voices that nothing reads")
    }

    private const val TAG = "SupertonicBundle"

    /**
     * The four plans, the codepoint table and the default voice.
     *
     * The other nine voices are not required: a bundle missing one of those loses a voice, while a
     * bundle missing a plan cannot speak at all, and only the second should stop the engine being
     * advertised.
     */
    private val REQUIRED = listOf(
        "supertonic_dp.maml",
        "supertonic_ttl.maml",
        "supertonic_ve.maml",
        "supertonic_voc.maml",
        "unicode_indexer.bin",
        SupertonicSynthesizer.styleName(SupertonicVoices.DEFAULT_VOICE),
    )
}
