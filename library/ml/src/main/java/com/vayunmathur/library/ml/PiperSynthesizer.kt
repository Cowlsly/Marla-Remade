package com.vayunmathur.library.ml

import android.util.Log
import java.io.File

/**
 * A Piper voice's inference settings, from its `config.json`.
 *
 * [noise] and [durationNoise] are what make two readings of the same sentence differ; VITS
 * samples both its prior and its durations. [length] is the speaking rate, above one being
 * slower - note that [PiperSynthesizer.synthesize]'s `speed` is the other way round, because
 * a caller asking to go faster should pass a number above one.
 */
data class PiperConfig(
    val sampleRate: Int,
    val noise: Float = 0.667f,
    val length: Float = 1.0f,
    val durationNoise: Float = 0.8f,
)

/**
 * On-device text-to-speech: Piper (VITS) on the Vulkan compute runtime.
 *
 * Replaces the ncnn `Vits` path. The model is four parts, and where each runs is a
 * deliberate choice rather than a convenience:
 *
 * - **Text encoder**, **normalising flow** and **HiFi-GAN vocoder** are compiled plans on the
 *   GPU. Together they are 15 million parameters and effectively all of the arithmetic.
 * - **Stochastic duration predictor** runs on the **CPU**. It is 4% of the parameters and
 *   produces one number per phoneme, but its work is a ten-bin search and a quadratic solve
 *   per position - data-dependent branching, which is what a GPU is worst at.
 *
 * All four were checked numerically against onnxruntime; see
 * `library/ml/src/main/rust/src/nets/vits_*.rs`.
 *
 * # Phonemes
 *
 * espeak-ng is **not** on the device and never was. `scripts/speech/generate_piper_dict.py`
 * runs it on the build machine to turn a word list into a `<lang>-word_id.bin` lookup table,
 * and that table ships with the voice. A word the table lacks is spelled out letter by letter.
 *
 * # Availability
 *
 * The constructor never throws. [isAvailable] is false when `libmodelrunner.so` is missing for
 * this ABI, when a file is absent, or when the device cannot give us a Vulkan device with fp16
 * compute - and then [synthesize] returns an empty array.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [synthesize] and [close]: the handle is
 * freed by [close] and reading it afterwards is a use-after-free.
 */
class PiperSynthesizer(
    directory: File,
    voice: String,
    config: PiperConfig,
    private val maxPhonemes: Int = DEFAULT_MAX_PHONEMES,
    chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
) : AutoCloseable {
    private var handle: Long = 0L

    /** The voice's output rate, so a caller cannot play it at the wrong pitch. */
    val sampleRate: Int = config.sampleRate

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                val encoder = read(directory, "${voice}_enc_p.maml")
                val flow = read(directory, "${voice}_flow.maml")
                val vocoder = read(directory, "${voice}_dec.maml")
                val durations = read(directory, "${voice}_dp.maml")
                val dictionary = dictionaryOf(directory)
                MlNative.createPiper(
                    encoder,
                    flow,
                    vocoder,
                    durations,
                    dictionary,
                    maxPhonemes,
                    chunkFrames,
                    config.sampleRate,
                    config.noise,
                    config.length,
                    config.durationNoise,
                )
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the Piper voice in $directory", e)
                0L
            }
        }
    }

    /** True if all four networks came up and the dictionary parsed. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Synthesise [text] at [speed], where above one is faster.
     *
     * Returns an empty array when there is nothing pronounceable, when the engine is
     * unavailable, or when the pass failed - all three are "no audio" to a caller, and the
     * reason is in logcat under `ModelRunner`.
     *
     * Long text should be split into sentences first: the encoder is compiled for
     * [maxPhonemes] and a longer utterance is refused rather than truncated, because
     * truncating would silently drop the end of a paragraph.
     */
    fun synthesize(text: String, speed: Float = 1.0f): FloatArray {
        if (handle == 0L || text.isBlank()) return FloatArray(0)
        return MlNative.synthesize(handle, text, speed) ?: FloatArray(0)
    }

    /** Free all four networks. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyPiper(live)
    }

    private companion object {
        private const val TAG = "PiperSynthesizer"

        /**
         * Phonemes the encoder is compiled for.
         *
         * Attention is quadratic in this, but the arena at 400 phonemes measured 2.10 MiB, so
         * the ceiling is set by how long a sentence a caller may pass rather than by memory.
         * 400 phonemes is a long sentence; a paragraph should be split.
         */
        const val DEFAULT_MAX_PHONEMES = 400

        /**
         * Latent frames per vocoder pass, matching `post::speech::CHUNK_FRAMES`.
         *
         * The vocoder's arena grows linearly with the utterance - 7.37 MiB per second of audio
         * - so it runs in chunks. The overlap is exact rather than approximate: the network is
         * convolutional with a bounded receptive field, so every kept sample is identical to
         * what a whole-utterance pass would have produced.
         */
        const val DEFAULT_CHUNK_FRAMES = 128

        fun read(directory: File, name: String): ByteArray {
            val file = File(directory, name)
            require(file.isFile) { "$name is missing from $directory" }
            return file.readBytes()
        }

        /**
         * The voice's `*-word_id.bin`, whatever language it is for.
         *
         * Matched by suffix rather than by name: a voice bundle carries its own language's
         * dictionary, and older bundles also carry the English one for compatibility. Either
         * works, so the first match wins.
         */
        fun dictionaryOf(directory: File): ByteArray {
            val found = directory.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") }
            requireNotNull(found) { "no *-word_id.bin in $directory" }
            return found.readBytes()
        }
    }
}
