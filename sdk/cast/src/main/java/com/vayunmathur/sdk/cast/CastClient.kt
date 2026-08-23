package com.vayunmathur.sdk.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Puts this app's content on a TV, through the installed Cast app.
 *
 * Hold one per casting screen, [openSession] once a TV is connected (see [CastPickerContract]), draw
 * into [Session.surface], and [close] when done. Nothing here touches the network: Cast owns the
 * sockets, the pairing and the encoders, which is why a consumer needs no network permission and why
 * one pairing per TV serves the whole device.
 *
 * **Deliberately not fail-soft, unlike `GameHubClient`.** A hub write that quietly does nothing
 * loses one achievement; a streaming call that quietly does nothing leaves the caller rendering into
 * a surface that goes nowhere, with the local player hidden behind a "Playing on TV" panel, and no
 * way to find out. So every failure is a typed exception - probe with [support] first if a soft path
 * is wanted.
 *
 * The binding *is* the session's lifetime. If this process is killed mid-cast the binding drops and
 * Cast tears the session down, which a request/response IPC would need a death token bolted on to
 * notice.
 */
class CastClient(context: Context) {

    private val appContext = context.applicationContext

    /** Result of the fast, synchronous capability probe. */
    enum class Support {
        /** Cast is not installed; offer its store listing. */
        NOT_INSTALLED,

        /** Installed but older than [CastContract.MIN_CAST_VERSION_CODE]. */
        NEEDS_UPDATE,

        /** Installed and recent enough to serve SDK sessions. */
        READY,
    }

    /** A live session: where to draw, where to write PCM, and what Cast actually agreed. */
    class Session(
        /**
         * The encoder's input surface. Everything drawn here is encoded and sent.
         *
         * Owned by Cast: do not release it. Releasing the client-side copy is what would break the
         * *next* session rather than this one, which is the kind of bug that only shows up on a
         * second consecutive cast.
         */
        val surface: Surface,
        /**
         * Write 48 kHz stereo 16-bit little-endian PCM here, or null if audio was not asked for or
         * could not be started. Null is not a session failure - the video is still going out.
         */
        val audio: ParcelFileDescriptor?,
        /** The granted frame size, clamped to the TV and to this phone's encoder. Lay out for this. */
        val width: Int,
        val height: Int,
        val frameRate: Int,
        /** For a "Playing on <name>" panel where the video used to be. */
        val receiverName: String,
    )

    /**
     * A live content session: Cast is serving this app's own media to the TV.
     *
     * Nothing to draw into and nothing to write, because nothing is being encoded. The TV fetches
     * byte ranges of whatever the app named and decodes them itself, so the app's only remaining
     * job is to answer [CastResourceProvider] and to keep sending [reportPlaybackState].
     *
     * There is no granted geometry either: the TV plays the media at its own size, which is the
     * point - the frame is no longer being squeezed through an encoder this phone had to choose.
     */
    class ContentSession(
        /** For a "Playing on <name>" panel where the local player used to be. */
        val receiverName: String,
        /** False for an audio-only session, where the TV shows a now-playing screen. */
        val hasVideo: Boolean,
    )

    /**
     * Called when Cast ends the session on its own - the TV went away, screen mirroring took the
     * slot - with one of [CastContract]'s `REASON_` values. Not called for a [close].
     */
    var onEnded: ((Int) -> Unit)? = null

    /**
     * Called when somebody presses a button on the television's remote.
     *
     * Optional: a client that leaves this null still casts, it simply has no remote - and the TV shows
     * no overlay either, because the overlay only appears once [reportPlaybackState] has been called.
     * So the two halves are opt-in together and there is no way to end up with dead buttons on screen.
     *
     * Runs on the main thread, like [onEnded], so it can drive a player directly.
     */
    var onCommand: ((PlaybackCommand) -> Unit)? = null

    private var service: Messenger? = null
    private var bound = false

    /** Set once [MSG_SESSION_READY][CastContract.MSG_SESSION_READY] has been answered. */
    private var open = false

    /** Non-null only while an open is waiting, so a failure can be reported to it. */
    private var pending: ((Result<Bundle>) -> Unit)? = null

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CastContract.MSG_SESSION_READY -> {
                    onReady(msg.data)
                    true
                }
                CastContract.MSG_SESSION_ENDED -> {
                    val reason = msg.data?.getInt(CastContract.KEY_END_REASON)
                        ?: CastContract.REASON_FAILED
                    onSessionEnded(reason)
                    true
                }
                CastContract.MSG_PLAYBACK_COMMAND -> {
                    // Dropped rather than queued when the session is not live: a command that arrived
                    // during teardown would drive a player the TV is no longer showing.
                    if (open) PlaybackCommand.from(msg.data)?.let { onCommand?.invoke(it) }
                    true
                }
                CastContract.MSG_RESOURCE_REQUEST -> {
                    onResourceRequest(msg.data)
                    true
                }
                else -> false
            }
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            sendOpen()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            // The Cast process died. Nothing is being encoded any more, so the caller has to be told
            // even though it never asked for this.
            onSessionEnded(CastContract.REASON_RECEIVER_GONE)
        }
    }

    /** What the caller asked for, replayed in [sendOpen] once the binding lands. */
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestedAudio = false
    private var requestedVideo = true

    /** Set for a content session, and what makes a missing `Surface` expected rather than a failure. */
    private var resources: CastResourceProvider? = null

    /**
     * Serialises resource opens off the main thread.
     *
     * Opening a MediaStore descriptor is disk I/O, and the request arrives on a `Handler` bound to
     * the main looper. Answering there would put a file open on the main thread of every app that
     * casts. One thread rather than a pool because the calls are rare - once per resource per
     * session - and ordering them keeps a provider from needing to be thread-safe.
     */
    private var resourceExecutor: ExecutorService? = null

    /**
     * Whether Cast can serve a session, from the installed package's `versionCode` alone.
     *
     * No IPC and no binding, so "needs update" is instant rather than a timeout.
     */
    fun support(): Support = supportFor(installedVersionCode())

    /** null when Cast is absent. minSdk 31, so `longVersionCode` is always available. */
    private fun installedVersionCode(): Long? = try {
        appContext.packageManager.getPackageInfo(CastContract.CAST_PACKAGE, 0).longVersionCode
    } catch (_: Exception) {
        null
    }

    /**
     * Bind Cast and open a session for a [width] x [height] frame.
     *
     * Requires a TV already connected, which is what [CastPickerContract] is for. The returned
     * geometry is what Cast agreed with that TV and may be smaller than requested.
     *
     * @throws CastNotInstalledException Cast is absent.
     * @throws CastNeedsUpdateException Cast is too old to have the service.
     * @throws CastPermissionException this app is not signed with the Modern Apps key.
     * @throws CastNoSessionException no TV is connected.
     * @throws CastSessionFailedException Cast could not start the stream.
     */
    suspend fun openSession(width: Int, height: Int, wantAudio: Boolean): Session {
        requestedWidth = width
        requestedHeight = height
        requestedAudio = wantAudio
        requestedVideo = true
        resources = null
        val ready = awaitReady()
        @Suppress("DEPRECATION")
        val surface = ready.getParcelable(CastContract.KEY_SURFACE) as? Surface
            ?: throw CastSessionFailedException(CastContract.REASON_FAILED)
        @Suppress("DEPRECATION")
        val audio = ready.getParcelable(CastContract.KEY_AUDIO_FD) as? ParcelFileDescriptor
        return Session(
            surface = surface,
            audio = audio,
            width = ready.getInt(CastContract.KEY_GRANTED_WIDTH, requestedWidth),
            height = ready.getInt(CastContract.KEY_GRANTED_HEIGHT, requestedHeight),
            frameRate = ready.getInt(CastContract.KEY_GRANTED_FRAME_RATE, DEFAULT_FRAME_RATE),
            receiverName = ready.getString(CastContract.KEY_RECEIVER_NAME).orEmpty(),
        )
    }

    /**
     * Bind Cast and open a session it serves this app's own media into.
     *
     * Nothing is encoded on this phone: Cast asks [resources] for a descriptor per resource and the
     * TV fetches byte ranges of it. So there is no size to request and no `Surface` to draw into -
     * seeking becomes a byte offset, and the TV owns the clock.
     *
     * [video] false opens an audio-only session, which the `Surface` path could not express at all.
     * The TV negotiates no video codec and shows a now-playing screen; a TV with no audio decoder
     * refuses outright rather than playing silence.
     *
     * Throws exactly what [openSession] throws.
     */
    suspend fun openContentSession(
        resources: CastResourceProvider,
        video: Boolean = true,
        wantAudio: Boolean = true,
    ): ContentSession {
        requestedWidth = 0
        requestedHeight = 0
        requestedAudio = wantAudio
        requestedVideo = video
        this.resources = resources
        resourceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cast-resources").apply { isDaemon = true }
        }
        val ready = awaitReady()
        return ContentSession(
            receiverName = ready.getString(CastContract.KEY_RECEIVER_NAME).orEmpty(),
            hasVideo = video,
        )
    }

    /**
     * Binds Cast and suspends until it answers `MSG_SESSION_READY`, or fails.
     *
     * Shared by both kinds of session because the binding, the timeout and every failure path are
     * identical; only what the caller makes of the answer differs.
     */
    private suspend fun awaitReady(): Bundle {
        when (support()) {
            Support.NOT_INSTALLED -> throw CastNotInstalledException()
            Support.NEEDS_UPDATE -> throw CastNeedsUpdateException()
            Support.READY -> {}
        }
        return withTimeout(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pending = { result ->
                    pending = null
                    result
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                }
                // Includes the timeout: a binding left up after giving up would keep Cast alive and
                // hold a session nothing is drawing into.
                continuation.invokeOnCancellation { close() }
                val intent = Intent().setComponent(
                    ComponentName(CastContract.CAST_PACKAGE, CastContract.SERVICE_CLASS),
                )
                bound = try {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    finish(Result.failure(CastPermissionException()))
                    return@suspendCancellableCoroutine
                }
                if (!bound) {
                    // Resolved (support() said READY) but would not bind. The permission is the only
                    // thing that refuses a resolvable component.
                    finish(Result.failure(CastPermissionException()))
                }
            }
        }
    }

    /**
     * Tell the TV where playback is, so it can draw a seek bar for content only this app can see.
     *
     * Call it on any material change and otherwise at a slow heartbeat - twice a second is enough,
     * because the TV extrapolates between snapshots. Every field is absolute rather than a delta, so a
     * dropped one costs at most one heartbeat of staleness and needs no retry.
     *
     * Silent and non-throwing, unlike everything else here: this is called from a poll loop, so a
     * failure would be a failure per tick, and there is nothing a caller could usefully do about one.
     * It is also what mounts the TV's overlay - stop calling it and the remote goes away with it.
     */
    fun reportPlaybackState(state: PlaybackState) {
        if (!open) return
        val remote = service ?: return
        val message = Message.obtain(null, CastContract.MSG_PLAYBACK_STATE).apply {
            data = state.toBundle()
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            // Cast is gone; onServiceDisconnected is what says so, and it will.
        }
    }

    /**
     * Tell the TV to play a resource, in a content session.
     *
     * [resourceId] is this app's own name for it: whatever [CastResourceProvider] will be asked for.
     * Call it again for the next item - there is no playlist, because the queue stays here where the
     * ordering, the artwork and the metadata are.
     *
     * Silent and non-throwing, like [reportPlaybackState]: a session that has just ended is the
     * common reason for this to go nowhere, and the client already learns that through [onEnded].
     */
    fun play(resourceId: String, mimeType: String, durationMs: Long = 0) {
        if (!open) return
        val remote = service ?: return
        val message = Message.obtain(null, CastContract.MSG_PLAY_MEDIA).apply {
            data = Bundle().apply {
                putString(CastContract.KEY_RESOURCE_ID, resourceId)
                putString(CastContract.KEY_RESOURCE_TYPE, mimeType)
                putLong(CastContract.KEY_MEDIA_DURATION_MS, durationMs)
            }
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
        }
    }

    /**
     * End the session and unbind. Idempotent, and safe if [openSession] never succeeded.
     *
     * [onEnded] is not called: the caller asked for this and already knows.
     */
    fun close() {
        onEnded = null
        onCommand = null
        pending = null
        resources = null
        // Shut down after clearing the provider so a request already in flight finds nothing to
        // open and answers with a 404 rather than handing out a descriptor nobody will read.
        resourceExecutor?.shutdown()
        resourceExecutor = null
        if (open) {
            try {
                service?.send(Message.obtain(null, CastContract.MSG_CLOSE_SESSION))
            } catch (_: RemoteException) {
                // Already gone; unbinding is all that is left to do.
            }
        }
        open = false
        if (bound) {
            try {
                appContext.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Never actually bound.
            }
            bound = false
        }
        service = null
    }

    private fun sendOpen() {
        val remote = service ?: return
        val message = Message.obtain(null, CastContract.MSG_OPEN_SESSION).apply {
            replyTo = incoming
            data = Bundle().apply {
                putInt(CastContract.KEY_WIDTH, requestedWidth)
                putInt(CastContract.KEY_HEIGHT, requestedHeight)
                putBoolean(CastContract.KEY_WANT_AUDIO, requestedAudio)
                putBoolean(CastContract.KEY_WANT_VIDEO, requestedVideo)
                putBoolean(CastContract.KEY_SERVE_RESOURCES, resources != null)
            }
        }
        try {
            remote.send(message)
        } catch (_: RemoteException) {
            finish(Result.failure(CastSessionFailedException(CastContract.REASON_FAILED)))
        }
    }

    /**
     * Answers Cast's request for the bytes behind a resource id.
     *
     * Always answers, even when there is no provider or the provider declines: Cast is holding a
     * request open for the TV, and a silent client turns into a stalled fetch rather than a `404`.
     *
     * Our copy of the descriptor is closed once sent. The Binder transaction duplicates it, so
     * keeping ours would leak one file descriptor per resource for the life of the session - and it
     * is the app's own descriptor, so the leak would be the app's.
     */
    private fun onResourceRequest(payload: Bundle?) {
        val requestId = payload?.getInt(CastContract.KEY_REQUEST_ID) ?: return
        val resourceId = payload.getString(CastContract.KEY_RESOURCE_ID) ?: return
        val provider = resources
        val executor = resourceExecutor ?: return
        executor.execute {
            val resource = provider?.let { runCatching { it.open(resourceId) }.getOrNull() }
            val reply = Message.obtain(null, CastContract.MSG_RESOURCE_RESPONSE).apply {
                data = Bundle().apply {
                    putInt(CastContract.KEY_REQUEST_ID, requestId)
                    if (resource != null) {
                        putParcelable(CastContract.KEY_RESOURCE_FD, resource.descriptor)
                        putLong(CastContract.KEY_RESOURCE_LENGTH, resource.length)
                        putString(CastContract.KEY_RESOURCE_TYPE, resource.contentType)
                    }
                }
            }
            try {
                service?.send(reply)
            } catch (_: RemoteException) {
                // Cast is gone; onServiceDisconnected reports it.
            }
            runCatching { resource?.descriptor?.close() }
        }
    }

    /**
     * Cast has a session. What is in the answer depends on which kind, so it is handed on whole.
     *
     * A content session has no `Surface` in it, so the check that one arrived belongs to
     * [openSession] rather than here.
     */
    private fun onReady(data: Bundle?) {
        if (data == null) {
            finish(Result.failure(CastSessionFailedException(CastContract.REASON_FAILED)))
            return
        }
        open = true
        finish(Result.success(data))
    }

    private fun onSessionEnded(reason: Int) {
        val waiting = pending
        open = false
        if (waiting != null) {
            // Ended before it ever started, so this is [openSession]'s failure rather than a
            // mid-cast teardown the caller has to react to.
            waiting(
                Result.failure(
                    when (reason) {
                        CastContract.REASON_NO_SESSION -> CastNoSessionException()
                        else -> CastSessionFailedException(reason)
                    },
                ),
            )
            return
        }
        onEnded?.invoke(reason)
    }

    /** Hand one outcome to a waiting open, unbinding first if it was a failure. */
    private fun finish(result: Result<Bundle>) {
        if (result.isFailure) {
            // Nothing is going to arrive on this binding, and leaving it up would keep Cast alive.
            if (bound) {
                try {
                    appContext.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                }
                bound = false
            }
            service = null
        }
        pending?.invoke(result)
    }

    internal companion object {
        /**
         * The whole of [support]'s decision, separated from `PackageManager` so it is provable on
         * the JVM: the three branches are the difference between "install Cast", "update Cast" and
         * "go", and getting the boundary wrong is silent either way.
         */
        internal fun supportFor(versionCode: Long?): Support = when {
            versionCode == null -> Support.NOT_INSTALLED
            versionCode >= CastContract.MIN_CAST_VERSION_CODE -> Support.READY
            else -> Support.NEEDS_UPDATE
        }

        /**
         * Opening involves a `STREAM_CONFIG`/`STREAM_READY` round trip to the TV over the LAN and
         * starting a hardware encoder, so it is not instant - but it is also not minutes, and a
         * caller left waiting is a caller showing a spinner over a video that could be playing.
         */
        const val OPEN_TIMEOUT_MS = 20_000L

        const val DEFAULT_FRAME_RATE = 30
    }
}
