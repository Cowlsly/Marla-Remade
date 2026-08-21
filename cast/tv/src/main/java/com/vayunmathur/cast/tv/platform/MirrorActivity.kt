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
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surface gone")
        ReceiverController.detachSurface()
    }

    /**
     * Size the surface to the sender's aspect ratio, centred, with black either side.
     *
     * `MediaCodec` scales its output to whatever the surface is, so the only thing that decides
     * whether the picture is stretched is these layout params. A portrait phone therefore appears as
     * a tall strip in the middle of the panel, which is what mirroring a phone should look like.
     */
    private fun fitToFrame(frameWidth: Int, frameHeight: Int) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        container.post {
            val availableWidth = container.width
            val availableHeight = container.height
            if (availableWidth == 0 || availableHeight == 0) return@post
            val frameAspect = frameWidth.toDouble() / frameHeight
            val screenAspect = availableWidth.toDouble() / availableHeight
            val (width, height) = if (frameAspect > screenAspect) {
                // Wider than the panel: full width, bars top and bottom.
                availableWidth to (availableWidth / frameAspect).toInt()
            } else {
                (availableHeight * frameAspect).toInt() to availableHeight
            }
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                android.view.Gravity.CENTER,
            )
        }
    }
}
