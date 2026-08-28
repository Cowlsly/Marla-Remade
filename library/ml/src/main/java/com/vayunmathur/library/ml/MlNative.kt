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
     * Bring up Piper (VITS) from its four `.maml` files plus a phoneme dictionary. Returns 0
     * on failure.
     *
     * Five assets because the model is four networks and a lookup table: the text encoder,
     * the normalising flow and the HiFi-GAN vocoder run on the GPU, and the stochastic
     * duration predictor runs on the CPU because it is a bin search and a quadratic solve per
     * phoneme. [dictionary] is the voice's `<lang>-word_id.bin`, built from espeak-ng on the
     * build machine so nothing on-device needs it.
     *
     * [phonemesWide] and [frames] fix the two compiled shapes: a plan records its command
     * buffer once, so the encoder is built for the longest utterance a request may hold and
     * the flow and vocoder for one chunk. The three scales come from the voice's
     * `config.json`.
     *
     * Freed by [destroyPiper], not [destroy] or [destroyOcr].
     */
    external fun createPiper(
        encoder: ByteArray,
        flow: ByteArray,
        vocoder: ByteArray,
        durations: ByteArray,
        dictionary: ByteArray,
        phonemesWide: Int,
        frames: Int,
        sampleRate: Int,
        noise: Float,
        length: Float,
        durationNoise: Float,
    ): Long
    /**
     * Synthesise [text] and return mono samples in `-1..1`, or null on failure.
     *
     * One call per utterance: the phoneme lookup, the encoder, the duration predictor, the
     * alignment, the flow and the chunked vocoder all happen natively.
     *
     * [speed] above one is faster. An empty array means nothing pronounceable, which is not a
     * failure - a string of emoji should say nothing.
     *
     * Two calls with the same text differ. VITS samples both its durations and its prior, and
     * that is what stops it sounding mechanical.
     */
    external fun synthesize(handle: Long, text: String, speed: Float): FloatArray?

    /**
     * Bring up Supertonic 3 from its four `.maml` files, the codepoint table and one voice.
     * Returns 0 on failure.
     *
     * Six assets, and no shape arguments: unlike [createPiper], every Supertonic plan is
     * utterance-shaped, so each net is re-recorded per sentence rather than compiled once at a
     * padded width. There is also no phoneme dictionary - the front end is [indexer], a flat
     * 65,536-entry codepoint table, which is why [synthesizeSupertonic] insists on NFD.
     *
     * [style] is one voice's `style_<name>.bin`. It is separate from the plans and swappable
     * through [setSupertonicVoice], because a voice is 25 KB against the plans' 198 MB.
     *
     * Freed by [destroySupertonic], not [destroy], [destroyOcr] or [destroyPiper].
     */
    external fun createSupertonic(
        duration: ByteArray,
        text: ByteArray,
        sampler: ByteArray,
        vocoder: ByteArray,
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
     * Free Piper's three networks, its duration weights and its dictionary. Exactly once per
     * non-zero handle from [createPiper].
     *
     * A third destroy function rather than one that guesses: the three handle types own
     * different things, and passing one to the wrong destroy is undefined.
     */
    external fun destroyPiper(handle: Long)

    /**
     * Free Supertonic's four networks, its conditioning, its codepoint table and its voice.
     * Exactly once per non-zero handle from [createSupertonic].
     *
     * A fourth destroy function, for the same reason there is a third: the handle types own
     * different things and passing one to the wrong destroy is undefined.
     */
    external fun destroySupertonic(handle: Long)
}
