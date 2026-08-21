package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.vayunmathur.cast.domain.streaming.StreamSelection

private const val TAG = "MirrorGeometry"

/** The frame size to encode, the density to mirror at, and the bitrate that size needs. */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitRate: Int,
)

/**
 * Decides what size frame to send.
 *
 * Needed **before** the OFFER, which advertises a `resolutions` list - a receiver told to expect one
 * shape and sent another has been misinformed. It needs no projection, only display metrics.
 *
 * **A landscape frame, not the phone's native portrait one.** Three reasons, learned the hard way:
 *
 *  1. Chrome caps mirroring at 1920x1080 (`kMaxResolution` in `mirror_settings.cc`) and never sends
 *     a phone's native resolution. A modern phone screen is around 4 megapixels and, in portrait,
 *     nearly 3000 pixels tall - a shape TV H.264 decoders are not obliged to accept and in practice
 *     often refuse, which shows up as a receiver that goes quiet and closes its socket.
 *  2. The receiver answers `scaling: "sender"`, meaning it will *not* letterbox for us. Sending a
 *     tall narrow frame to a 16:9 panel is therefore our problem to solve, not its.
 *  3. `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` already solves it: given a landscape display it scales the
 *     phone screen to fit and pads the sides, which is exactly what mirroring to a TV should look
 *     like. No compositing code required.
 */
object MirrorGeometry {

    /** `kMaxResolution` from Chrome's `mirror_settings.cc`. */
    private const val FRAME_WIDTH = 1920
    private const val FRAME_HEIGHT = 1080

    /**
     * Bits per pixel per second.
     *
     * 0.1 bpp at 30 fps is a common screen-sharing figure: screen content is mostly static with
     * sharp edges, so it needs far less than camera video of the same size, but too low a rate makes
     * text unreadable. At 1080p this lands near 6 Mbit/s, close to Chrome's 5 Mbit/s start rate.
     */
    private const val BITS_PER_PIXEL = 0.1

    /** openscreen's `kDefaultVideoMinBitRate`, as a floor. */
    private const val MIN_BITRATE = 300_000

    fun forDisplay(context: Context): CaptureGeometry {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService<WindowManager>()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        // 4:2:0 chroma subsampling cannot represent an odd width or height, and some encoders fail
        // outright rather than rounding for you.
        val (width, height) = EncoderSupport.clampToEncoder(FRAME_WIDTH, FRAME_HEIGHT)
        val bitRate = (
            width.toLong() * height * StreamSelection.VIDEO_MAX_FRAME_RATE * BITS_PER_PIXEL
            )
            .toLong()
            .coerceAtLeast(MIN_BITRATE.toLong())
            .toInt()
        Log.i(
            TAG,
            "sending ${width}x$height at ${bitRate / 1_000_000.0} Mbit/s; " +
                "the ${metrics.widthPixels}x${metrics.heightPixels} screen is letterboxed into it",
        )
        return CaptureGeometry(
            width = width,
            height = height,
            // The phone's own density, so text scales the way it does on the screen rather than
            // being rendered for a notional 1080p tablet.
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }
}
