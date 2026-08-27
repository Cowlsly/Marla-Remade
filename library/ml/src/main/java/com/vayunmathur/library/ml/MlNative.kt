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
     * Bring up MediaPipe Selfie Segmentation from a `.vkml` blob. Returns 0 on failure.
     *
     * Failure is expected and normal on a device without `VK_KHR_shader_float16_int8`,
     * which is a Vulkan 1.2 promotion and so optional at the 1.1 floor minSdk 31
     * guarantees. The reason is written to logcat under the `ModelRunner` tag.
     */
    external fun createSelfie(weights: ByteArray): Long

    /** Bring up U²-Net portable. Returns 0 on failure, as [createSelfie]. */
    external fun createU2netp(weights: ByteArray): Long

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
}
