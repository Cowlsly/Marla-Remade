package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.StreamConstants

private const val TAG = "MirrorGeometry"

/** The frame size to encode, the density to mirror at, and the bitrate that size needs. */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitRate: Int,
)

/**
 * Decides what size frame to send, from the TV's own limits.
 *
 * **This used to force 1920x1080 landscape, and getting to stop is the clearest single gain from
 * owning the receiver.** The old reasoning was sound for a Cast receiver and every part of it is now
 * obsolete:
 *
 *  - Chrome caps mirroring at 1920x1080, but that is openscreen's *mirroring policy*
 *    (`kMaxResolution` in `mirror_settings.cc`, and `"Currently mirroring only supports 1080P"` in
 *    `capture_recommendations.h`) rather than anything a decoder requires. Our receiver reports what
 *    it actually decodes, so the ceiling is the real one.
 *  - A Cast receiver answers `scaling: "sender"` and will not letterbox, so a portrait phone had to be
 *    scaled into a landscape frame here - spending most of the encoded pixels on black bars. Our
 *    receiver pads instead, so the phone sends its own shape and every pixel carries picture.
 *
 * What remains is arithmetic: keep the phone's aspect ratio, fit inside the TV's limits, and round to
 * something the encoder will accept.
 */
object MirrorGeometry {

    /**
     * Bits per pixel per second.
     *
     * 0.1 bpp at 30 fps is a common screen-sharing figure: screen content is mostly static with sharp
     * edges, so it needs far less than camera video of the same size, but too low a rate makes text
     * unreadable.
     */
    private const val BITS_PER_PIXEL = 0.1

    /** openscreen's `kDefaultVideoMinBitRate`, as a floor. */
    private const val MIN_BITRATE = 300_000

    /** What an app gets if it asks for nonsense; 720p is a size every decoder takes. */
    private const val DEFAULT_CONTENT_WIDTH = 1280
    private const val DEFAULT_CONTENT_HEIGHT = 720

    /**
     * The frame to send to a TV that reported [limits].
     *
     * The phone's real screen shape, scaled down only as far as the TV's limits and this device's own
     * encoder require. [limits] null means the handshake has not happened yet, in which case the
     * phone's native size is used unclamped and the encoder check alone decides.
     */
    fun forDisplay(context: Context, limits: DecoderLimits? = null): CaptureGeometry {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService<WindowManager>()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val screenHeight = metrics.heightPixels.takeIf { it > 0 } ?: 1920

        val (fittedWidth, fittedHeight) = fitWithin(screenWidth, screenHeight, limits)
        // 4:2:0 chroma subsampling cannot represent an odd width or height, and some encoders fail
        // outright rather than rounding for you.
        val (width, height) = EncoderSupport.clampToEncoder(fittedWidth, fittedHeight)

        val frameRate = frameRateFor(limits)
        val bitRate = bitRateFor(width, height, frameRate, limits)

        Log.i(
            TAG,
            "sending ${width}x$height @ ${frameRate}fps at ${bitRate / 1_000_000.0} Mbit/s; " +
                "the screen is ${screenWidth}x$screenHeight and the TV will letterbox it",
        )
        return CaptureGeometry(
            width = width,
            height = height,
            // The phone's own density, so text scales the way it does on the screen rather than being
            // rendered for a notional tablet.
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }

    /**
     * The frame to send for an app that asked for [requestedWidth] x [requestedHeight].
     *
     * Same clamping as [forDisplay], different starting point: an SDK session's shape is the content's
     * own - a 16:9 video, not the phone's screen - and the app is told what it actually got, because an
     * app that laid out for the size it asked for would be stretched.
     *
     * No density: nothing renders a `VirtualDisplay` here, the client draws into the surface directly.
     */
    fun forContent(
        requestedWidth: Int,
        requestedHeight: Int,
        limits: DecoderLimits? = null,
    ): CaptureGeometry {
        val safeWidth = requestedWidth.takeIf { it > 0 } ?: DEFAULT_CONTENT_WIDTH
        val safeHeight = requestedHeight.takeIf { it > 0 } ?: DEFAULT_CONTENT_HEIGHT
        val (fittedWidth, fittedHeight) = fitWithin(safeWidth, safeHeight, limits)
        val (width, height) = EncoderSupport.clampToEncoder(fittedWidth, fittedHeight)
        val frameRate = frameRateFor(limits)
        val bitRate = bitRateFor(width, height, frameRate, limits)
        Log.i(
            TAG,
            "app content: asked for ${safeWidth}x$safeHeight, sending ${width}x$height " +
                "@ ${frameRate}fps at ${bitRate / 1_000_000.0} Mbit/s",
        )
        return CaptureGeometry(
            width = width,
            height = height,
            densityDpi = DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }

    /** The TV's cap, or ours, whichever is lower. */
    fun frameRateFor(limits: DecoderLimits?): Int = minOf(
        StreamConstants.VIDEO_MAX_FRAME_RATE,
        limits?.maxFrameRate?.takeIf { it > 0 } ?: StreamConstants.VIDEO_MAX_FRAME_RATE,
    )

    private fun bitRateFor(width: Int, height: Int, frameRate: Int, limits: DecoderLimits?): Int {
        var bitRate = (width.toLong() * height * frameRate * BITS_PER_PIXEL)
            .toLong()
            .coerceAtLeast(MIN_BITRATE.toLong())
        limits?.maxBitRate?.takeIf { it > 0 }?.let { bitRate = minOf(bitRate, it.toLong()) }
        return bitRate.toInt()
    }

    /**
     * The largest frame with the same aspect ratio as [width] x [height] that fits [limits].
     *
     * Both dimensions are checked independently, because a TV's limits are not necessarily the same
     * shape as a phone's screen: a 3840x2160 decoder still cannot take a 1440x3120 portrait frame
     * unrotated, and scaling by the tighter of the two ratios is what respects both.
     */
    private fun fitWithin(width: Int, height: Int, limits: DecoderLimits?): Pair<Int, Int> {
        if (limits == null) return width to height
        val maxWidth = limits.maxWidth.takeIf { it > 0 } ?: return width to height
        val maxHeight = limits.maxHeight.takeIf { it > 0 } ?: return width to height
        if (width <= maxWidth && height <= maxHeight) return width to height
        val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        return (width * scale).toInt() to (height * scale).toInt()
    }
}
