package com.vayunmathur.cast.tv.platform

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.tv.ui.CastTvTheme
import com.vayunmathur.cast.tv.ui.TransportOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "MirrorActivity"

/**
 * The picture: one full-screen `SurfaceView` the decoder writes straight into.
 *
 * **The surface is deliberately not Compose, and the overlay deliberately is.** The surface is one
 * handle given to `MediaCodec`; routing it through `AndroidView` would put a recomposition path
 * between the decoder and the display for no benefit. The transport controls have the opposite
 * requirement - they are state-driven and change constantly - so they are a *second child* of the same
 * `FrameLayout`, a `ComposeView` above the surface. The decoder's path is untouched by this; only the
 * overlay recomposes.
 *
 * **Letterboxing is done here, and that is the point.** The old sender scaled the phone's portrait
 * screen into a 1920x1080 frame and sent mostly black pixels, because a Cast receiver answers
 * `scaling: "sender"` and will not pad for you. Owning the receiver means the phone can send its own
 * aspect ratio at full resolution and the TV pads it - so none of the encoded frame is wasted.
 *
 * **This is also the app's whole input surface.** [dispatchKeyEvent] handles the remote directly rather
 * than moving a focus ring between buttons, because a media overlay's D-pad presses are gestures -
 * left seeks, up is volume - and there is nothing to focus. Keys are only claimed while a phone is
 * actually reporting playback, so screen mirroring keeps the ordinary behaviour of every key.
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

    /** Whether the controls are on screen. Any key press reveals them; a timer takes them away. */
    private var overlayVisible by mutableStateOf(false)

    /**
     * A scrub the user is still composing, in milliseconds, or -1 when none is in progress.
     *
     * **Accumulate here and commit on release.** Sending a seek per keypress would put a storm of them
     * on the control channel and into an ExoPlayer that is simultaneously feeding a hardware encoder;
     * holding a local preview and sending one `SeekTo` at the end is both kinder and what the user
     * means by holding the button down.
     */
    private var scrubPreviewMs by mutableLongStateOf(NO_SCRUB)

    private var hideJob: Job? = null

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
            addView(overlayView(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    /**
     * The controls, as a transparent layer above the picture.
     *
     * Composes to nothing at all until the phone reports playback, which is what keeps it off a
     * mirrored phone screen: screen mirroring has no transport, so it never sends a snapshot.
     */
    private fun overlayView(): ComposeView = ComposeView(this).apply {
        setContent {
            CastTvTheme {
                val state by ReceiverController.state.collectAsState()
                val playback = state.playback
                if (playback != null && overlayVisible) {
                    TransportOverlay(
                        snapshot = playback,
                        sourceName = (state.phase as? ReceiverPhase.Mirroring)?.sourceName.orEmpty(),
                        scrubPreviewMs = scrubPreviewMs.takeIf { it != NO_SCRUB },
                    )
                }
            }
        }
    }

    /**
     * The remote, in full.
     *
     * Only claims a key while the phone is reporting playback - otherwise every one of these falls
     * through to the system, which is what a mirrored phone screen needs. `BACK` is the exception worth
     * pointing at: it dismisses the controls before it will leave the session, because a user who has
     * just brought up a seek bar and presses back means "put that away", not "stop watching".
     *
     * Left and right accumulate into [scrubPreviewMs] and commit on release; everything else is one
     * message per press. `ACTION_MULTIPLE` is not handled: a held D-pad repeats as `ACTION_DOWN`.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playback = ReceiverController.state.value.playback
            ?: return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && overlayVisible) {
                hideOverlay()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Committing the scrub is the *release*, so it is handled before the reveal below - a release
        // must not be mistaken for a fresh press.
        if (event.action == KeyEvent.ACTION_UP && isScrubKey(event.keyCode)) {
            commitScrub()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Any press at all brings the controls up, and re-arms the timer that takes them away. The
        // press still does its own job: a user who reaches for pause should not have to press twice.
        revealOverlay()

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> ReceiverController.send(PlaybackCommand(PlaybackAction.Toggle))

            KeyEvent.KEYCODE_MEDIA_PLAY -> ReceiverController.send(PlaybackCommand(PlaybackAction.Play))
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ReceiverController.send(PlaybackCommand(PlaybackAction.Pause))

            KeyEvent.KEYCODE_DPAD_RIGHT -> scrubBy(SCRUB_STEP_MS, playback)
            KeyEvent.KEYCODE_DPAD_LEFT -> scrubBy(-SCRUB_STEP_MS, playback)

            // The remote's dedicated keys, where it has them. Distinct from a D-pad scrub: these are
            // a discrete "skip", so the phone's own interval applies rather than an accumulated one.
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ReceiverController.skip(forward = true)
            KeyEvent.KEYCODE_MEDIA_REWIND -> ReceiverController.skip(forward = false)

            KeyEvent.KEYCODE_MEDIA_NEXT ->
                ReceiverController.send(PlaybackCommand(PlaybackAction.Next))
            KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                ReceiverController.send(PlaybackCommand(PlaybackAction.Previous))

            // The shared level, not this box's own volume: the phone owns it, so the press goes there
            // and the gain follows on the next snapshot. Declining when there is no session lets the
            // key fall through to the box's ordinary volume control.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP,
            -> if (!ReceiverController.nudgeVolume(up = true)) return super.dispatchKeyEvent(event)

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> if (!ReceiverController.nudgeVolume(up = false)) return super.dispatchKeyEvent(event)

            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun isScrubKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /**
     * Move the preview position, starting it from where playback actually is on the first press.
     *
     * Interpolated rather than taken from the last report, so a scrub begun mid-playback starts from
     * the frame on screen rather than from up to half a second behind it.
     */
    private fun scrubBy(deltaMs: Long, playback: PlaybackSnapshot) {
        val duration = playback.state.durationMs
        if (duration <= 0) return
        val from = scrubPreviewMs.takeIf { it != NO_SCRUB }
            ?: playback.positionAt(System.currentTimeMillis())
        scrubPreviewMs = (from + deltaMs).coerceIn(0, duration)
    }

    private fun commitScrub() {
        val target = scrubPreviewMs
        scrubPreviewMs = NO_SCRUB
        if (target == NO_SCRUB) return
        ReceiverController.send(
            PlaybackCommand(PlaybackAction.SeekTo, value = target.toDouble()),
        )
        // The preview is dropped here rather than held until the phone confirms: the next snapshot is
        // at most half a second away, and holding a stale preview would freeze the bar if the seek was
        // refused - which the phone does while its own slider is under a thumb.
        revealOverlay()
    }

    private fun revealOverlay() {
        overlayVisible = true
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(OVERLAY_AUTO_HIDE_MS)
            // A scrub in progress is an interaction, however long the user has been holding the key.
            if (scrubPreviewMs == NO_SCRUB) overlayVisible = false
        }
    }

    private fun hideOverlay() {
        hideJob?.cancel()
        hideJob = null
        scrubPreviewMs = NO_SCRUB
        overlayVisible = false
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

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        /** No scrub in progress. A sentinel rather than a nullable, so the Compose state is primitive. */
        const val NO_SCRUB = -1L

        /**
         * How far one D-pad press moves the preview.
         *
         * Smaller than a skip on purpose: this is a *scrub*, and the user is aiming. Holding the key
         * repeats it, so a long press still crosses a long video quickly.
         */
        const val SCRUB_STEP_MS = 5_000L

        /** Matches YouPipe's own `CONTROLS_AUTO_HIDE_DELAY_MS`, doubled for a remote's slower aim. */
        const val OVERLAY_AUTO_HIDE_MS = 4_000L
    }
}
