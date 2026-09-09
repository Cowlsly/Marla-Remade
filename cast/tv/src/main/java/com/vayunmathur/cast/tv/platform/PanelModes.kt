package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.vayunmathur.cast.protocol.DisplayMode

private const val TAG = "PanelModes"

/**
 * What this television's screen can actually display.
 *
 * Separate from [VideoDecoder.limits], and deliberately so: that describes the *decoder's*
 * envelope, which is a ceiling on what may be sent, not a description of the panel. A set-top box
 * whose decoder handles 4K attached to a 1080p screen reports both, and composing a desktop for the
 * decoder would render a surface the panel then scales back down.
 *
 * Only the default display is reported. A receiver renders into one `MirrorActivity`, so a second
 * screen is not somewhere this session can appear, and offering its modes to the sender would let
 * the phone pick a size nothing displays at.
 */
object PanelModes {

    fun of(context: Context): List<DisplayMode> {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            Log.w(TAG, "no default display; the sender will fall back to phone geometry")
            return emptyList()
        }
        val modes = display.supportedModes
            .filter { it.physicalWidth > 0 && it.physicalHeight > 0 }
            // Two modes differing only in refresh rate are one choice as far as a desktop is
            // concerned, and the sender picks by area - so collapse them and keep the fastest.
            .groupBy { it.physicalWidth to it.physicalHeight }
            .map { (size, sameSize) ->
                DisplayMode(
                    width = size.first,
                    height = size.second,
                    refreshRate = sameSize.maxOf { it.refreshRate },
                )
            }
            .sortedByDescending { it.area }
        Log.i(TAG, "panel offers ${modes.joinToString { "${it.width}x${it.height}" }}")
        return modes
    }
}
