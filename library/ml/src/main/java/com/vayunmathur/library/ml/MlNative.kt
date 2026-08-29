package com.vayunmathur.library.ml

/**
 * The JNI surface of the Vulkan compute runtime in `library/ml/src/main/rust`.
 *
 * Deliberately narrow: create a network from its weights, hand it a bitmap's pixels, get
 * a mask back, destroy it. Preprocessing, the whole forward pass and the readback happen
 * on the native side, so the boundary is crossed three times per inference rather than
 * once per pixel.
 *
 * [handle] values are opaque pointers owned by the native side. Every method tolerates
 * `0`, which is what the create functions return on failure — so a device without Vulkan
 * fp16 compute degrades to a disabled feature rather than a crash. See [isAvailable].
 *
 * Nothing here is thread-safe. Callers hold a lock across [segment] and [destroy]; see
 * [SelfieSegmenter] and [SubjectSegmenter], which do.
 */
internal object MlNative {

    /**
     * Whether `libmodelrunner.so` loaded.
     *
     * False on a device whose ABI we did not build for. It says nothing about whether
     * Vulkan or fp16 is available — that is only known once a create call is attempted,
     * because it needs a `VkDevice` to ask.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary("modelrunner")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Bring up MediaPipe Selfie Segmentation from a `.maml` blob. Returns 0 on failure.
     *
     * Failure is expected and normal on a device without `VK_KHR_shader_float16_int8`,
     * which is a Vulkan 1.2 promotion and so optional at the 1.1 floor minSdk 31
     * guarantees. The reason is written to logcat under the `ModelRunner` tag.
     */
    external fun createSelfie(weights: ByteArray): Long

    /** Bring up U²-Net portable. Returns 0 on failure, as [createSelfie]. */
    external fun createU2netp(weights: ByteArray): Long

    /** Bring up SCRFD 500M face detection. Returns 0 on failure, as [createSelfie]. */
    external fun createScrfd(weights: ByteArray): Long

    /** Bring up MobileFaceNet face embedding. Returns 0 on failure, as [createSelfie]. */
    external fun createMobilefacenet(weights: ByteArray): Long
    /**
     * Bring up both PP-OCRv5 networks and the character table. Returns 0 on failure.
     *
     * Three assets because they are three files: DBNet detection, CTC recognition, and the
     * 836-line dictionary the labels index. Detection runs at a fixed 960x960 square and
     * recognition at a fixed 48x320, so each records its command buffer once.
     *
     * The returned handle is **not** interchangeable with the others: it is freed by
     * [destroyOcr], not [destroy].
     */
    external fun createPpocr(detection: ByteArray, recognition: ByteArray, keys: String): Long
    /**
     * Recognise every line in [pixels] and return them tab-separated, or null on failure.
     *
     * One region per line of the result: the text, then eight quad coordinates in
     * source-bitmap pixels, then the confidence, then `1` or `0` for vertical - ten
     * tab-separated fields after the text.
     *
     * Detection, the rotated crops, recognition of each, the CTC collapse and reading
     * order all happen natively, so this crosses the boundary once per bitmap rather than
     * once per line. An empty string means no text, which is not a failure.
     *
     * Packing text and geometry into one string is safe rather than lucky: the dictionary
     * holds 836 single non-whitespace characters plus a space, so no decoded line can
     * contain a tab or a newline.
     */
    external fun recognizeText(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): String?

    /**
     * Bring up Supertonic 3 from its four `.maml` files, the codepoint table and one voice.
     * Returns 0 on failure.
     *
     * No shape arguments: every Supertonic plan is utterance-shaped, so each net is re-recorded per
     * sentence rather than compiled once at a padded width. There is also no phoneme dictionary -
     * phoneme dictionary - the front end is [indexer], a flat 65,536-entry codepoint table, which is
     * why [synthesizeSupertonic] insists on NFD.
     *
     * The four plans arrive as **file descriptors** rather than byte arrays. A `ByteArray` would
     * allocate the model three times over - the Java array, the `Vec<u8>` JNI hands Rust, and the
     * reader's own copy of the data section - which for a ~105 MB bundle is ~300 MB of transient
     * heap and an out-of-memory kill rather than a slow load. Native reads the header and tensor
     * table only, then streams the weights into the GPU through a fixed-size staging buffer.
     *
     * [fds], [offsets] and [lengths] are parallel and in a fixed order: duration predictor, text
     * encoder, sampler, vocoder. All three are needed because an `AssetFileDescriptor` describes a
     * *range of the APK* rather than a file of its own; for a file on disk the offset is 0 and the
     * length is the file's size.
     *
     * **Each descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close them itself. An asset also has
     * to be stored uncompressed for `openFd` to work at all - `noCompress += "maml"`.
     *
     * [indexer] and [style] stay byte arrays: 128 KB and 25 KB, where streaming saves nothing.
     * [style] is separate from the plans and swappable through [setSupertonicVoice].
     *
     * Freed by [destroySupertonic], not [destroy] or [destroyOcr].
     */
    external fun createSupertonic(
        fds: IntArray,
        offsets: LongArray,
        lengths: LongArray,
        indexer: ByteArray,
        style: ByteArray,
    ): Long

    /**
     * Point a live handle at another voice, returning false on failure.
     *
     * Cheap, and the reason a voice change does not mean a new handle: the four networks stay
     * uploaded and only the two style tensors are replaced.
     */
    external fun setSupertonicVoice(handle: Long, style: ByteArray): Boolean

    /**
     * Synthesise [text] and return mono samples in `-1..1` at 44,100 Hz, or null on failure.
     *
     * [text] **must already be NFD**-decomposed - use `java.text.Normalizer.normalize(text,
     * Form.NFD)`. The model has no precomposed accents: `é` is unmapped while `e` and the
     * combining acute are both first-class tokens, so precomposed text silently loses
     * characters. Doing the decomposition natively would mean carrying Unicode tables in the
     * APK when the platform already has them.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    external fun synthesizeSupertonic(handle: Long, text: String): FloatArray?

    /**
     * Bring up SMaLL-100 from its one `.maml` and its tokenizer table. Returns 0 on failure.
     *
     * One graph, not two: the 128,112-row embedding is **tied** — it is the encoder's input table,
     * the decoder's input table and the logits kernel — so an encoder file and a decoder file would
     * upload 125 MiB of it twice. Native selects between the encoder pass, the decode step and the
     * logits projection by re-recording one net.
     *
     * The plan arrives as a **file descriptor** for the reason [createSupertonic] gives, and more
     * so: at 318 MiB a `ByteArray` would allocate it three times over and be killed for it. Native
     * reads the header and tensor table only, then streams the weights into the GPU. It also keeps
     * the descriptor open for the life of the handle, because the tied embedding is gathered a row
     * at a time on the host rather than uploaded a second time.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close it itself. [offset] is 0 and
     * [length] the file's size for a file on disk; only an asset needs a real range.
     *
     * [tokenizer] stays a byte array: 1.7 MB, where streaming saves nothing.
     *
     * Freed by [destroySmall100], not [destroy], [destroyOcr] or [destroySupertonic].
     */
    external fun createSmall100(
        fd: Int,
        offset: Long,
        length: Long,
        tokenizer: ByteArray,
    ): Long

    /**
     * Translate [text] into the language [targetToken] names, or null on failure.
     *
     * [text] **must already be NFKC** - use `java.text.Normalizer.normalize(text, Form.NFKC)`. The
     * model's normaliser is `nmt_nfkc` with a 237 KB precompiled charsmap, and reproducing that
     * natively would mean carrying Unicode tables the platform already has.
     *
     * [targetToken] goes on the **source** side, which is the one thing about SMaLL-100 that is easy
     * to get backwards: its distillation gives the *encoder* the target language and starts the
     * decoder from `</s>`. Backwards it produces fluent output in the wrong language rather than an
     * error. `Small100Model.LANG_ID` is the table.
     *
     * Greedy decoding, capped at 128 tokens. An empty string means the text had nothing to
     * translate, which is not a failure.
     */
    external fun translateSmall100(handle: Long, text: String, targetToken: Int): String?

    /**
     * Free SMaLL-100's network, its open weights file and its tokenizer table.
     * Exactly once per non-zero handle from [createSmall100].
     */
    external fun destroySmall100(handle: Long)

    /**
     * Detect faces in [pixels] and return them flattened, nine floats per face.
     *
     * The layout per face is `left, top, right, bottom, leftEyeX, leftEyeY, rightEyeX,
     * rightEyeY, score`, every coordinate a fraction of the bitmap. Letterboxing, the
     * forward pass, the anchor decode and non-maximum suppression all happen natively, so
     * this crosses the boundary once per bitmap rather than once per proposal.
     *
     * An empty array means no faces — the common case, and not a failure. Null is a
     * failure.
     */
    external fun detectFaces(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): FloatArray?

    /**
     * Run the network over [pixels] and return the mask, or null on failure.
     *
     * [pixels] is `ARGB_8888` as `Bitmap.getPixels` produces it, [width] × [height] long.
     * The bitmap is resized and normalised natively, so any size is accepted; the
     * returned mask is always [maskWidth] × [maskHeight], row-major, in `0..1`.
     */
    external fun segment(handle: Long, pixels: IntArray, width: Int, height: Int): FloatArray?

    /** The mask's width, so callers need not know either network's input size. */
    external fun maskWidth(handle: Long): Int

    /** The mask's height. */
    external fun maskHeight(handle: Long): Int

    /**
     * Free everything the handle owns, after waiting for the GPU to go idle.
     *
     * Exactly once per non-zero handle. When it is the last live network the shared
     * `VkDevice` is destroyed with it, so nothing stays resident once the feature is
     * closed.
     */
    external fun destroy(handle: Long)
    /**
     * Free both PP-OCRv5 networks and the dictionary. Exactly once per non-zero handle
     * from [createPpocr].
     *
     * Separate from [destroy] because the handle is a different native type. Passing one to
     * the other is undefined, which is why there are two functions rather than one that
     * guesses.
     */
    external fun destroyOcr(handle: Long)

    /**
     * Free Supertonic's four networks, its conditioning, its codepoint table and its voice.
     * Exactly once per non-zero handle from [createSupertonic].
     *
     * A third destroy function rather than one that guesses: the handle types own different things
     * and passing one to the wrong destroy is undefined.
     */
    external fun destroySupertonic(handle: Long)
}
