package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.vayunmathur.cast.domain.streaming.StreamSelection

private const val TAG = "MirrorGeometry"

/** The capture size, the density to mirror at, and the bitrate that size needs. */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitRate: Int,
)

/**
 * Decides what size to capture the screen at.
 *
 * Separate from [ScreenCapture] because the answer is needed **before** the OFFER goes out: the
 * OFFER advertises a `resolutions` list, and a receiver told to expect one shape while being sent
 * another has been misinformed. It needs no projection, only display metrics, so it runs before
 * consent is even asked for.
 *
 * The screen's **native resolution** is used, not a downscale. The receiver answers
 * `scaling: "sender"` against its own (often 4K) panel, so it will not letterbox for us and sending
 * fewer pixels than the phone has just throws detail away for nothing. The only reason to reduce it
 * is an encoder that refuses the size, which is what [EncoderSupport.clampToEncoder] handles.
 */
object MirrorGeometry {

    /**
     * Bits per pixel per second.
     *
     * 0.1 bpp at 30 fps is a common desktop-sharing figure - screen content is mostly static with
     * sharp edges, so it needs far less than camera video at the same size but punishes a bitrate
     * too low to keep text legible. On a 1344x2992 phone this lands near 12 Mbit/s.
     */
    private const val BITS_PER_PIXEL = 0.1

    /** openscreen's `kDefaultVideoMinBitRate`, as a floor for a very small display. */
    private const val MIN_BITRATE = 300_000

    /**
     * Above this a phone encoder starts dropping frames rather than degrading quality, and a home
     * network starts losing packets. Not a resolution limit - only a rate one.
     */
    private const val MAX_BITRATE = 20_000_000

    fun forDisplay(context: Context): CaptureGeometry {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService<WindowManager>()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val realWidth = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val realHeight = metrics.heightPixels.takeIf { it > 0 } ?: 1920
        // 4:2:0 chroma subsampling cannot represent an odd width or height, and some encoders fail
        // outright rather than rounding for you.
        val (width, height) = EncoderSupport.clampToEncoder(realWidth.even(), realHeight.even())
        val bitRate = (
            width.toLong() * height * StreamSelection.VIDEO_MAX_FRAME_RATE * BITS_PER_PIXEL
            )
            .toLong()
            .coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong())
            .toInt()
        if (width != realWidth || height != realHeight) {
            Log.i(TAG, "capturing at ${width}x$height rather than ${realWidth}x$realHeight")
        }
        return CaptureGeometry(
            width = width,
            height = height,
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }

    private fun Int.even(): Int = this / 2 * 2
}
