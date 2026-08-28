package com.vayunmathur.library.ml

import android.util.Log
import java.io.File
import java.text.Normalizer

/**
 * On-device text-to-speech: Supertonic 3 on the Vulkan compute runtime.
 *
 * Replaces [PiperSynthesizer]. One ~198 MB fp16 bundle covers 30 languages and 10 voices, where
 * Piper needed a separate model and phoneme dictionary per language. Four networks, all on the
 * GPU and all checked numerically against onnxruntime — see
 * `library/ml/src/main/rust/src/nets/supertonic_*.rs`:
 *
 * - **Duration predictor** returns one number, the utterance's length in seconds, which fixes
 *   every later shape.
 * - **Text encoder** turns the characters into a `[256, chars]` conditioning.
 * - **Flow-matching sampler** is the expensive one: 16 steps, each running two guidance branches,
 *   so 32 passes of 64 million parameters for one sentence.
 * - **ConvNeXt vocoder** turns the latent into 44,100 Hz samples.
 *
 * # There is no phonemiser
 *
 * The front end is a flat 65,536-entry codepoint table, not espeak-ng, so nothing has to be
 * generated per language on the build machine. The model does expect **NFD**-decomposed text,
 * which [synthesize] does itself through `java.text.Normalizer`: precomposed accents are unmapped
 * while combining marks are first-class tokens, so skipping it would quietly drop characters.
 *
 * # Voices
 *
 * A voice is two small style tensors, not a model, so [voice] switches without re-uploading
 * anything. That is why a voice change is a method here and was a new object in Piper.
 *
 * # Availability
 *
 * The constructor never throws. [isAvailable] is false when `libmodelrunner.so` is missing for
 * this ABI, when a file is absent, or when the device cannot give us a Vulkan device with fp16
 * compute — and then [synthesize] returns an empty array.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [synthesize], [voice] and [close]: the handle
 * is freed by [close] and reading it afterwards is a use-after-free.
 */
class SupertonicSynthesizer(
    private val directory: File,
    voice: String = DEFAULT_VOICE,
) : AutoCloseable {
    private var handle: Long = 0L

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int = SAMPLE_RATE

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                MlNative.createSupertonic(
                    read(directory, "supertonic_dp.maml"),
                    read(directory, "supertonic_ttl.maml"),
                    read(directory, "supertonic_ve.maml"),
                    read(directory, "supertonic_voc.maml"),
                    read(directory, "unicode_indexer.bin"),
                    read(directory, styleName(voice)),
                )
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the Supertonic bundle in $directory", e)
                0L
            }
        }
    }

    /** True if all four networks came up, the codepoint table is the right size and a voice read. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Switch to another voice, returning false if it could not be read.
     *
     * Cheap: the four networks stay on the GPU and only the two style tensors are replaced, so
     * this is a 25 KB read rather than a 198 MB one.
     */
    fun voice(name: String): Boolean {
        if (handle == 0L) return false
        return try {
            MlNative.setSupertonicVoice(handle, read(directory, styleName(name)))
        } catch (e: Throwable) {
            Log.e(TAG, "cannot read the voice $name in $directory", e)
            false
        }
    }

    /**
     * Synthesise [text] and return mono samples in `-1..1` at [sampleRate].
     *
     * Returns an empty array when there is nothing in the model's vocabulary, when the engine is
     * unavailable, or when the pass failed — all three are "no audio" to a caller, and the reason
     * is in logcat under `ModelRunner`.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but every
     * shape here scales with the utterance and the sampler runs 32 passes over all of it.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    fun synthesize(text: String): FloatArray {
        if (handle == 0L || text.isBlank()) return FloatArray(0)
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return MlNative.synthesizeSupertonic(handle, decomposed) ?: FloatArray(0)
    }

    /** Free all four networks. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroySupertonic(live)
    }

    private companion object {
        private const val TAG = "SupertonicSynthesizer"

        /** The vocoder's rate: 44,100 Hz, at 3,072 samples per latent frame. */
        const val SAMPLE_RATE = 44_100

        /** The one voice style that ships with the bundle; the other nine are downloaded. */
        const val DEFAULT_VOICE = "F1"

        fun styleName(voice: String): String = "style_$voice.bin"

        fun read(directory: File, name: String): ByteArray {
            val file = File(directory, name)
            require(file.isFile) { "$name is missing from $directory" }
            return file.readBytes()
        }
    }
}
