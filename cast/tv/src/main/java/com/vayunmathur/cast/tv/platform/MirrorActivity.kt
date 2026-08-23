package com.vayunmathur.cast.tv.platform

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "MirrorActivity"

/**
 * The picture: one full-screen `SurfaceView` the decoder writes straight into.
 *
 * **Deliberately not Compose.** Nothing here is a composable - it is one surface handed to
 * `MediaCodec` - and going through `AndroidView` would add a recomposition path between the decoder
 * and the display for no benefit. It also means this Activity needs no theme work to be full-screen
 * black.
 *
 * **Letterboxing is done here, and that is the point.** The old sender scaled the phone's portrait
 * screen into a 1920x1080 frame and sent mostly black pixels, because a Cast receiver answers
 * `scaling: "sender"` and will not pad for you. Owning the receiver means the phone can send its own
 * aspect ratio at full resolution and the TV pads it - so none of the encoded frame is wasted.
 */
class MirrorActivity : ComponentActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView

    /**
     * The frame size the sender said it would send, remembered so the letterbox can be recomputed.
     *
     * **Load-bearing.** The letterbox used to be computed only when `ReceiverController.state` emitted,
     * which is once per session - and on this box that emission lands a few milliseconds after
     * [preferLargestDisplayMode] has asked for 3840x2160 and before the switch has taken effect, so the
     * panel still measures 1920x1080. The state never changes again, so a stale layout was final: a
     * 1920x1080 surface left sitting on a 4K panel. Holding the frame size here is what lets the panel
     * tell us when it has finished resizing.
     */
    private var frameWidth = 0
    private var frameHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@MirrorActivity) }
        container = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER,
                ),
            )
        }
        setContentView(container)
        preferLargestDisplayMode()
        // The display-mode change above is asynchronous, and so is the first layout pass. Either one
        // finishing is a reason to redo the letterbox against the size the panel actually ended up.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                fitToFrame(frameWidth, frameHeight)
            }
        }
        // A TV has no navigation bar to keep clear of, but some launchers still overlay a status bar,
        // and a mirrored screen should be the whole panel.
        WindowCompat.getInsetsController(window, container).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Finishing when the session ends is what returns the user to the idle screen rather than
        // leaving the last frame frozen on a dead surface.
        lifecycleScope.launch {
            ReceiverController.state.collectLatest { state ->
                if (state.phase is ReceiverPhase.Mirroring) {
                    fitToFrame(state.phase.width, state.phase.height)
                } else {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReceiverController.detachSurface()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surface ready")
        ReceiverController.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // A rotation or a resolution change hands back a *different* surface, so the controller has
        // to be told again - re-attaching the same one is harmless.
        ReceiverController.attachSurface(holder.surface)
        // And the panel may be a different size than when the letterbox was last worked out.
        fitToFrame(frameWidth, frameHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surface gone")
        ReceiverController.detachSurface()
    }

    /**
     * Size the surface to the sender's aspect ratio, centred, with black either side.
     *
     * Two different sizes are set here and the distinction is the whole point:
     *
     *  - The **layout** size is the letterbox. A portrait phone appears as a tall strip in the middle
     *    of the panel, which is what mirroring a phone should look like.
     *  - The **buffer** size, via [SurfaceHolder.setFixedSize], stays at the sender's own
     *    resolution. Without it the surface buffer is the view's size, so a 1344x2992 frame was
     *    squeezed into roughly 486x1080 by `MediaCodec` before it ever reached the panel - the
     *    phone's extra resolution was encoded, transmitted, and then thrown away on arrival. With it
     *    the composer scales a full-resolution buffer to the output instead, and can put it on a
     *    hardware overlay rather than through the 1080p UI framebuffer.
     *
     * Whether that last step actually happens is the composer's decision, not ours, so it has to be
     * checked on the device with `dumpsys SurfaceFlinger` rather than assumed.
     */
    private fun fitToFrame(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        frameWidth = sourceWidth
        frameHeight = sourceHeight
        container.post {
            val availableWidth = container.width
            val availableHeight = container.height
            if (availableWidth == 0 || availableHeight == 0) return@post
            val frameAspect = sourceWidth.toDouble() / sourceHeight
            val screenAspect = availableWidth.toDouble() / availableHeight
            val (width, height) = if (frameAspect > screenAspect) {
                // Wider than the panel: full width, bars top and bottom.
                availableWidth to (availableWidth / frameAspect).toInt()
            } else {
                (availableHeight * frameAspect).toInt() to availableHeight
            }
            // Nothing to do, and worth checking: this is now called from a layout listener, so
            // re-applying the same layoutParams would schedule another layout pass and loop.
            val current = surfaceView.layoutParams
            if (current != null && current.width == width && current.height == height) return@post
            surfaceView.holder.setFixedSize(sourceWidth, sourceHeight)
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                android.view.Gravity.CENTER,
            )
            Log.i(
                TAG,
                "buffer ${sourceWidth}x$sourceHeight shown as ${width}x$height " +
                    "on a ${availableWidth}x$availableHeight panel",
            )
        }
    }

    /**
     * Ask for the largest display mode the panel offers, best effort.
     *
     * **A no-op on the box this was written for**, which exposes exactly one mode (3840x2160 at
     * 59.94 Hz), so there is nothing to choose and nothing changes. It is set anyway because a box
     * that does offer a choice would otherwise be left on whatever mode the launcher happened to
     * pick, and a mirror asking for the whole panel is the one case where the largest is right.
     *
     * Deliberately does not filter by refresh rate: the source is 30 fps, every mode a TV offers is a
     * multiple or near-multiple of that, and a mode switch mid-session blanks the panel for a second.
     */
    private fun preferLargestDisplayMode() {
        val modes = display?.supportedModes ?: return
        if (modes.size <= 1) return
        val largest = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = largest.modeId }
        Log.i(
            TAG,
            "asked for ${largest.physicalWidth}x${largest.physicalHeight} " +
                "@ ${largest.refreshRate}Hz of ${modes.size} modes",
        )
    }
}
