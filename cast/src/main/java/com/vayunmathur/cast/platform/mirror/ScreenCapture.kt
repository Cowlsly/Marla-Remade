package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.getSystemService

private const val TAG = "ScreenCapture"

/** The capture size and the frame rate to encode at. */
data class CaptureGeometry(val width: Int, val height: Int, val densityDpi: Int)

/**
 * A `VirtualDisplay` mirroring the real screen into an encoder's input surface.
 *
 * The receiver answers `scaling: "sender"` with its own (often 4K) dimensions, so choosing the
 * capture size is ours to do. It is scaled down to at most [MAX_DIMENSION] on the long edge, keeping
 * the real aspect ratio, because that is what the OFFER promised and what keeps the bitrate sane.
 *
 * Both dimensions are rounded to even numbers: H.264's 4:2:0 chroma subsampling cannot represent an
 * odd width or height, and some encoders fail outright rather than rounding for you.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
) {

    private var display: VirtualDisplay? = null

    fun geometry(): CaptureGeometry {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService<WindowManager>()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val realWidth = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val realHeight = metrics.heightPixels.takeIf { it > 0 } ?: 1920
        val scale = minOf(
            1.0,
            MAX_DIMENSION.toDouble() / maxOf(realWidth, realHeight),
        )
        return CaptureGeometry(
            width = ((realWidth * scale).toInt() / 2) * 2,
            height = ((realHeight * scale).toInt() / 2) * 2,
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
        )
    }

    /** Returns false if the platform refused the display, which leaves nothing to encode. */
    fun start(surface: Surface, geometry: CaptureGeometry): Boolean = try {
        display = projection.createVirtualDisplay(
            "cast-mirror",
            geometry.width,
            geometry.height,
            geometry.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        display != null
    } catch (e: Exception) {
        Log.w(TAG, "could not create the virtual display", e)
        false
    }

    fun release() {
        runCatching { display?.release() }
        display = null
    }

    private companion object {
        /** 720p on the long edge, matching the `resolutions` the OFFER advertises. */
        const val MAX_DIMENSION = 1280
    }
}
