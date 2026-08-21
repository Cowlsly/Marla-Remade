package com.vayunmathur.cast.platform.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface

private const val TAG = "ScreenCapture"

/**
 * A `VirtualDisplay` mirroring the real screen into an encoder's input surface.
 *
 * The size comes from [MirrorGeometry], decided before the OFFER so the resolution advertised and
 * the resolution actually sent are the same.
 */
class ScreenCapture(private val projection: MediaProjection) {

    private var display: VirtualDisplay? = null

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
}
