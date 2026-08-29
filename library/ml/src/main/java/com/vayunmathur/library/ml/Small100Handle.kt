package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.text.Normalizer

/**
 * On-device translation between any two of 100 languages: SMaLL-100 on the Vulkan compute runtime.
 *
 * M2M-100 distilled to a 3-layer decoder — 12 encoder layers, `d_model` 1024, 16 heads, a 128,112
 * entry vocabulary — at 332.7 million parameters. **Direct** translation, with no English pivot, so
 * every pair is a single hop. See `library/ml/src/main/rust/src/nets/small100.rs`.
 *
 * # Two files, 320 MB, downloaded rather than bundled
 *
 * `small100.maml` is 318.3 MiB and `tokenizer.bin` 1.7 MB, which is far too much to ship inside an
 * APK — hence [inDirectory] and no asset path. Everything but the layer norms and the biases is
 * int8, quantised per output channel from the fp32 checkpoint; the worst per-tensor correlation
 * against fp32 is 0.9998.
 *
 * The ncnn build this replaces downloaded **1.14 GB** across seven files. It was fp16 because ncnn
 * could not quantise the embedding, and an int4 attempt crashed with a tagged-memory fault inside
 * `libncnn_android.so`.
 *
 * # The target language goes on the source side
 *
 * SMaLL-100 does not use `forced_bos_token_id`. The target-language token is prepended to the
 * **source** and the decoder starts from `</s>`. Backwards, it produces fluent output in the wrong
 * language rather than an error — which is why [translate] takes a token rather than a code and
 * leaves the table to the caller that already has it.
 *
 * # Normalisation
 *
 * The model's normaliser is `nmt_nfkc` with a 237 KB precompiled charsmap, so [translate] applies
 * `java.text.Normalizer` **NFKC** first and native does the rest — the same split
 * [SupertonicSynthesizer] uses for NFD.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for this
 * ABI, when either file is absent or malformed, or when the device cannot give us a Vulkan device
 * with fp16 compute — and then [translate] returns null.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than the other handles here: a translation re-records the
 * network once per decoded token, so two concurrent calls would interleave recordings of the same
 * command buffer. A caller must hold a lock across [translate] and [close].
 */
class Small100Handle private constructor(private val directory: File) : AutoCloseable {
    private var handle: Long = 0L

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                create(directory)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the SMaLL-100 model in $directory", e)
                0L
            }
        }
    }

    /** True if the graph came up and the tokenizer table is the vocabulary the model was built on. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Translate [text] into the language [targetToken] names, or null on failure.
     *
     * An empty string means there was nothing to translate, which is not a failure — blank input and
     * input the tokenizer maps to nothing both come back empty. Null means the engine is unavailable
     * or the pass failed, and the reason is in logcat under `ModelRunner`.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but decoding is
     * capped at 128 tokens and every step attends over the whole source.
     */
    fun translate(text: String, targetToken: Int): String? {
        if (handle == 0L) return null
        if (text.isBlank()) return ""
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return MlNative.translateSmall100(handle, normalised, targetToken)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroySmall100(live)
    }

    override fun toString(): String = "SMaLL-100 in $directory"

    companion object {
        private const val TAG = "Small100Handle"

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "small100.maml"

        /** `scripts/ml/small100_tokenizer.py`'s output: 128,112 pieces and their merge ranks. */
        const val TOKENIZER = "tokenizer.bin"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(GRAPH, TOKENIZER)

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart, unlike [SupertonicSynthesizer]: at 320 MB this is a runtime
         * download to `getExternalFilesDir`, so there is no APK entry to open.
         */
        fun inDirectory(directory: File): Small100Handle = Small100Handle(directory)

        /**
         * Open the graph, read the tokenizer table, and hand the descriptor over.
         *
         * The two `finally`s are what make the descriptor safe rather than usually safe. A raw
         * descriptor has no destructor, so every path out of here has to close what it opened: the
         * outer one covers a missing or unreadable file, and the inner one covers the window after
         * the descriptor has been given up but before native has adopted it.
         */
        private fun create(directory: File): Long {
            val graph = File(directory, GRAPH)
            require(graph.isFile) { "$GRAPH is missing from $directory" }
            val tokenizer = File(directory, TOKENIZER)
            require(tokenizer.isFile) { "$TOKENIZER is missing from $directory" }

            // Offset 0 and the whole file: only an asset needs a range, because only an asset shares
            // its descriptor with the rest of the APK.
            val fd = ParcelFileDescriptor.open(graph, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { it.detachFd() }
            var handed = false
            try {
                val handle =
                    MlNative.createSmall100(fd, 0L, graph.length(), tokenizer.readBytes())
                handed = true
                return handle
            } finally {
                if (!handed) closeFd(fd)
            }
        }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from Kotlin.
         * Failures are swallowed because the caller is already on an error path.
         */
        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
