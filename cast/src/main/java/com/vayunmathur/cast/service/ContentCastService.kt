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
import com.vayunmathur.cast.protocol.PlayMedia
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

    /** Where `MSG_SESSION_READY`, `MSG_SESSION_ENDED`, `MSG_PLAYBACK_COMMAND` and `MSG_TV_PLAYBACK_STATE` go. */
    private var client: Messenger? = null

    /** True between a successful open and the session ending, so nothing is reported twice. */
    private var sessionOpen = false

    /**
     * Where the media proxy gets its bytes.
     *
     * Held here rather than by `CastController` because the app is only reachable through this
     * binding, and the binding is the session's lifetime. The proxy is handed this when a content
     * session starts.
     */
    val resources = ClientResourceResolver { send(it) }

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
                CastContract.MSG_RESOURCE_RESPONSE -> {
                    resources.onResponse(msg.data)
                    true
                }
                CastContract.MSG_RESOURCE_COMPLETE -> {
                    // Not gated on `sessionOpen`, unlike the two below: this releases a reader that
                    // may be parked on a growing file, and dropping it during teardown would leave
                    // that reader to wait out its own bound for no reason.
                    resources.onComplete(msg.data)
                    true
                }
                CastContract.MSG_PLAY_MEDIA -> {
                    // Dropped with no session, for the same reason a state snapshot is: there is no
                    // TV to play it on, and a client may send one a tick after the session ended.
                    if (sessionOpen) {
                        msg.data?.let { CastController.playMedia(it.toPlayMedia()) }
                    }
                    true
                }
                CastContract.MSG_SEND_PLAYBACK_COMMAND -> {
                    // Dropped with no session, like the two above: the television it would drive is no
                    // longer this client's.
                    if (sessionOpen) {
                        msg.data?.toPlaybackCommand()
                            ?.let { CastController.sendPlaybackCommand(it) }
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
        // Absent means video, so a client written against the older contract still means what it did.
        val wantVideo = data?.getBoolean(CastContract.KEY_WANT_VIDEO, true) ?: true
        // A client that will answer resource requests is asking to be served rather than encoded.
        val served = data?.getBoolean(CastContract.KEY_SERVE_RESOURCES) == true

        scope.launch {
            val result = CastController.startContentSession(
                context = this@ContentCastService,
                width = width,
                height = height,
                wantAudio = wantAudio,
                // Established by the picker from callingPackage, not by anything the client sent.
                appLabel = CastController.contentAppLabel,
                wantVideo = wantVideo,
                resources = if (served) resources else null,
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
                is ContentSessionResult.Serving -> {
                    sessionOpen = true
                    CastController.onContentSessionEnded = { reason ->
                        scope.launch { closeSession(reason, notify = true) }
                    }
                    CastController.onPlaybackCommand = { command ->
                        scope.launch { send(playbackCommand(command)) }
                    }
                    // The half that only a served session has: the TV holds the player, so its state
                    // has to reach the client rather than the other way round.
                    CastController.onTvPlaybackState = { state ->
                        scope.launch { send(tvPlaybackState(state)) }
                    }
                    sendServing(result)
                }
            }
        }
    }

    /**
     * Answer a served session: a name, and no surface.
     *
     * The absence of `KEY_SURFACE` is the signal, and it is why `CastClient.openContentSession` does
     * not look for one - there is nothing to draw into because nothing is being encoded.
     */
    private fun sendServing(serving: ContentSessionResult.Serving) {
        val message = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
            data = Bundle().apply {
                putString(CastContract.KEY_RECEIVER_NAME, serving.receiverName)
                putBoolean(CastContract.KEY_WANT_VIDEO, serving.hasVideo)
            }
        }
        if (!send(message)) closeSession(CastContract.REASON_CLIENT_CLOSED, notify = false)
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
        CastController.onTvPlaybackState = null
        // The app's descriptors are ours until the session ends, and nothing else will close them.
        resources.close()
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

/** The item a client asked for, as the message the TV understands. See [Bundle.toPlaybackState]. */
private fun Bundle.toPlayMedia(): PlayMedia = PlayMedia(
    resourceId = getString(CastContract.KEY_RESOURCE_ID).orEmpty(),
    // A default rather than a refusal: ExoPlayer sniffs when it is not told, which costs a round
    // trip but plays, where a refused item would simply be silence.
    mimeType = getString(CastContract.KEY_RESOURCE_TYPE).orEmpty(),
    durationMs = getLong(CastContract.KEY_MEDIA_DURATION_MS),
    startPositionMs = getLong(CastContract.KEY_START_POSITION_MS),
)

/**
 * The press a client sent, as the message the TV understands, or null for an action this build does
 * not know.
 *
 * Matched through [sdkAction] rather than a second hand-written table, which is what keeps this
 * exhaustive for free: adding an action to the protocol enum fails to compile *there*, and this
 * follows without being able to disagree with it. Null rather than a fallback for an unrecognised int,
 * because a command invented by a newer SDK is one this build has nothing to do with.
 */
private fun Bundle.toPlaybackCommand(): PlaybackCommand? {
    val action = getInt(CastContract.KEY_ACTION, -1)
    val matched = PlaybackAction.entries.firstOrNull { it.sdkAction == action } ?: return null
    val value = if (containsKey(CastContract.KEY_ACTION_VALUE)) {
        getDouble(CastContract.KEY_ACTION_VALUE)
    } else {
        null
    }
    return PlaybackCommand(matched, value)
}

/** The command the TV sent, as the `Message` the SDK reads. See [Bundle.toPlaybackState]. */
private fun playbackCommand(command: PlaybackCommand): Message =
    Message.obtain(null, CastContract.MSG_PLAYBACK_COMMAND).apply {
        data = Bundle().apply {
            putInt(CastContract.KEY_ACTION, command.action.sdkAction)
            command.value?.let { putDouble(CastContract.KEY_ACTION_VALUE, it) }
        }
    }

/**
 * The television's own playback, as the `Message` the SDK reads.
 *
 * The inverse of [Bundle.toPlaybackState], and `hasNext`/`hasPrevious` are carried through unchanged
 * even though the TV cannot know them: the keys exist, the client ignores them, and dropping them here
 * would be a second place that has to agree about which fields are meaningful in which direction.
 */
private fun tvPlaybackState(state: PlaybackState): Message =
    Message.obtain(null, CastContract.MSG_TV_PLAYBACK_STATE).apply {
        data = Bundle().apply {
            putLong(CastContract.KEY_POSITION_MS, state.positionMs)
            putLong(CastContract.KEY_DURATION_MS, state.durationMs)
            putBoolean(CastContract.KEY_PLAYING, state.playing)
            putBoolean(CastContract.KEY_BUFFERING, state.buffering)
            putFloat(CastContract.KEY_SPEED, state.speed)
            putFloat(CastContract.KEY_VOLUME, state.volume)
            putBoolean(CastContract.KEY_HAS_NEXT, state.hasNext)
            putBoolean(CastContract.KEY_HAS_PREVIOUS, state.hasPrevious)
            putBoolean(CastContract.KEY_ENDED, state.ended)
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
