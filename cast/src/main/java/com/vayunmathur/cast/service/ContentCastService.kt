package com.vayunmathur.cast.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.ContentSessionResult
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.sdk.cast.CastContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ContentCastService"

/**
 * Streams another app's content, on behalf of `:sdk:cast`.
 *
 * The exported half of the broker. `CastService` stays `exported="false"` and stays the
 * foreground-service owner: an SDK session needs `mediaPlayback` only, which its
 * `enterForeground(withProjection = false)` already provides, and duplicating that here would mean two
 * services fighting over one notification.
 *
 * **Binding is the session's lifetime.** [onUnbind] is the death notification, for free: if the client
 * is killed mid-cast the binding drops and the session is torn down, where the repo's
 * `startForegroundService` + `ResultReceiver` idiom would have needed a `linkToDeath` token bolted on
 * to notice. A cast session is long-lived, so it wants a long-lived binding.
 *
 * Gated by `CastContract.PERMISSION` in the manifest, which is signature-level. That is the only
 * enforcement, and it means any first-party app can stream - so nothing the client says about itself is
 * trusted. In particular the app's *name* never crosses this channel: `CastPickerActivity` resolves it
 * from the framework's `callingPackage`, because `Message` dispatch goes through a [Handler] and the
 * Binder calling identity is gone by the time the handler runs.
 */
class ContentCastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Where `MSG_SESSION_READY`, `MSG_SESSION_ENDED` and `MSG_PLAYBACK_COMMAND` go. */
    private var client: Messenger? = null

    /** True between a successful open and the session ending, so nothing is reported twice. */
    private var sessionOpen = false

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CastContract.MSG_OPEN_SESSION -> {
                    openSession(msg.replyTo, msg.data)
                    true
                }
                CastContract.MSG_CLOSE_SESSION -> {
                    closeSession(CastContract.REASON_CLIENT_CLOSED, notify = true)
                    true
                }
                CastContract.MSG_PLAYBACK_STATE -> {
                    // Dropped rather than queued with no session: there is no TV to draw it on, and a
                    // client is free to keep polling for a tick or two after the session ended.
                    if (sessionOpen) {
                        msg.data?.let { CastController.reportPlaybackState(it.toPlaybackState()) }
                    }
                    true
                }
                else -> false
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The client asked, or died. Either way nothing is drawing into the surface any more, so the
        // TV should go back to idle rather than hold the last frame. Not notified: there is nobody
        // left to tell.
        closeSession(CastContract.REASON_CLIENT_CLOSED, notify = false)
        // false: no onRebind, so a client that binds again gets a fresh onBind and a fresh session.
        return false
    }

    override fun onDestroy() {
        closeSession(CastContract.REASON_CLIENT_CLOSED, notify = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun openSession(replyTo: Messenger?, data: Bundle?) {
        if (replyTo == null) {
            Log.w(TAG, "MSG_OPEN_SESSION with no replyTo; there is nowhere to send the surface")
            return
        }
        client = replyTo
        // A second open on the same binding replaces the first, which is also what happens when
        // another app takes the single session.
        if (sessionOpen) closeSession(CastContract.REASON_PREEMPTED, notify = false)

        val width = data?.getInt(CastContract.KEY_WIDTH) ?: 0
        val height = data?.getInt(CastContract.KEY_HEIGHT) ?: 0
        val wantAudio = data?.getBoolean(CastContract.KEY_WANT_AUDIO) ?: false

        scope.launch {
            val result = CastController.startContentSession(
                context = this@ContentCastService,
                width = width,
                height = height,
                wantAudio = wantAudio,
                // Established by the picker from callingPackage, not by anything the client sent.
                appLabel = CastController.contentAppLabel,
            )
            when (result) {
                is ContentSessionResult.Failed -> send(sessionEnded(result.reason))
                is ContentSessionResult.Started -> {
                    sessionOpen = true
                    // Registered only now: startContentSession itself preempts whatever was running,
                    // and a callback set any earlier would be the one it cancelled.
                    CastController.onContentSessionEnded = { reason ->
                        scope.launch { closeSession(reason, notify = true) }
                    }
                    CastController.onPlaybackCommand = { command ->
                        // Hopped to the main thread because `awaitEnd` runs on IO and the client's
                        // callback drives a player.
                        scope.launch { send(playbackCommand(command)) }
                    }
                    sendReady(result)
                }
            }
        }
    }

    /**
     * Answer with the surface, the pipe and the numbers the TV actually agreed.
     *
     * The write end is closed **after** the send: a `ParcelFileDescriptor` is duplicated by the Binder
     * transaction, so holding our copy open would keep the pipe alive from this side and the reader
     * would never notice the client stop writing.
     */
    private fun sendReady(started: ContentSessionResult.Started) {
        val message = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
            data = Bundle().apply {
                putParcelable(CastContract.KEY_SURFACE, started.surface)
                started.audioWriteEnd?.let { putParcelable(CastContract.KEY_AUDIO_FD, it) }
                putInt(CastContract.KEY_GRANTED_WIDTH, started.width)
                putInt(CastContract.KEY_GRANTED_HEIGHT, started.height)
                putInt(CastContract.KEY_GRANTED_FRAME_RATE, started.frameRate)
                putString(CastContract.KEY_RECEIVER_NAME, started.receiverName)
            }
        }
        val sent = send(message)
        closeWriteEnd(started.audioWriteEnd)
        if (!sent) {
            // The client went away between opening and being answered; nothing is going to draw.
            closeSession(CastContract.REASON_CLIENT_CLOSED, notify = false)
        }
    }

    private fun closeSession(reason: Int, notify: Boolean) {
        if (!sessionOpen) return
        sessionOpen = false
        CastController.onContentSessionEnded = null
        CastController.onPlaybackCommand = null
        // Back to a paired-but-idle TV rather than a dropped session: the pairing is per device and
        // worth keeping, and the user may well cast again straight away.
        CastController.stopMirroring(this)
        if (notify) send(sessionEnded(reason))
    }

    private fun sessionEnded(reason: Int): Message =
        Message.obtain(null, CastContract.MSG_SESSION_ENDED).apply {
            data = Bundle().apply { putInt(CastContract.KEY_END_REASON, reason) }
        }

    /** Returns false if the client is gone. */
    private fun send(message: Message): Boolean {
        val remote = client ?: return false
        return try {
            remote.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun closeWriteEnd(pfd: ParcelFileDescriptor?) {
        runCatching { pfd?.close() }
    }
}

/**
 * The `Bundle` a client sent, as the message the TV understands.
 *
 * **This is the whole of the deliberate duplication between `:sdk:cast` and `:cast:protocol`, and it
 * lives here because this is the only place that depends on both.** `:sdk:cast` is what every casting
 * app compiles against, so it must not pull in the wire format, the crypto or the packetiser; the
 * protocol module is what the TV shares. Two definitions and one translation is the price, and this
 * file is where it is paid.
 *
 * Defaults rather than refusals for the optional fields: a client that never sets a speed is playing
 * at 1x, and a TV drawing 0x would be wrong in a way nothing would explain.
 */
private fun Bundle.toPlaybackState(): PlaybackState = PlaybackState(
    positionMs = getLong(CastContract.KEY_POSITION_MS),
    durationMs = getLong(CastContract.KEY_DURATION_MS),
    playing = getBoolean(CastContract.KEY_PLAYING),
    buffering = getBoolean(CastContract.KEY_BUFFERING),
    speed = getFloat(CastContract.KEY_SPEED, 1f),
    volume = getFloat(CastContract.KEY_VOLUME, 1f),
    hasNext = getBoolean(CastContract.KEY_HAS_NEXT),
    hasPrevious = getBoolean(CastContract.KEY_HAS_PREVIOUS),
)

/** The command the TV sent, as the `Message` the SDK reads. See [Bundle.toPlaybackState]. */
private fun playbackCommand(command: PlaybackCommand): Message =
    Message.obtain(null, CastContract.MSG_PLAYBACK_COMMAND).apply {
        data = Bundle().apply {
            putInt(CastContract.KEY_ACTION, command.action.sdkAction)
            command.value?.let { putDouble(CastContract.KEY_ACTION_VALUE, it) }
        }
    }

/**
 * The SDK's int for a protocol action.
 *
 * Exhaustive with no else branch on purpose: adding an action to the protocol enum should fail to
 * compile here rather than silently arrive at the client as whatever the fallback was.
 */
private val PlaybackAction.sdkAction: Int
    get() = when (this) {
        PlaybackAction.Play -> CastContract.ACTION_PLAY
        PlaybackAction.Pause -> CastContract.ACTION_PAUSE
        PlaybackAction.Toggle -> CastContract.ACTION_TOGGLE
        PlaybackAction.SeekTo -> CastContract.ACTION_SEEK_TO
        PlaybackAction.SkipForward -> CastContract.ACTION_SKIP_FORWARD
        PlaybackAction.SkipBack -> CastContract.ACTION_SKIP_BACK
        PlaybackAction.Next -> CastContract.ACTION_NEXT
        PlaybackAction.Previous -> CastContract.ACTION_PREVIOUS
        PlaybackAction.SetSpeed -> CastContract.ACTION_SET_SPEED
        PlaybackAction.SetVolume -> CastContract.ACTION_SET_VOLUME
    }
