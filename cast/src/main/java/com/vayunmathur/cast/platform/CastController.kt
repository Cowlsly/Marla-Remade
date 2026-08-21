package com.vayunmathur.cast.platform

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastFrame
import com.vayunmathur.cast.domain.CastMediaInformation
import com.vayunmathur.cast.domain.CastMediaMetadata
import com.vayunmathur.cast.domain.CastNamespaces
import com.vayunmathur.cast.domain.CastPhase
import com.vayunmathur.cast.domain.CastPlayerState
import com.vayunmathur.cast.domain.CastSession
import com.vayunmathur.cast.domain.CastSessionState
import com.vayunmathur.cast.domain.DEFAULT_MEDIA_RECEIVER_APP_ID
import com.vayunmathur.cast.domain.MirroringAppIds
import com.vayunmathur.cast.network.CastChannel
import com.vayunmathur.cast.network.MediaFileServer
import com.vayunmathur.cast.platform.discovery.CastDiscoveryManager
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "CastController"

/**
 * The receiver drops a channel it has not heard from in roughly ten seconds, so this has to
 * stay comfortably under that even when the app is doing nothing.
 */
private const val HEARTBEAT_INTERVAL_MS = 5_000L

/**
 * MEDIA_STATUS is pushed when the player state changes but not as time passes, so the position
 * has to be asked for. One second is what makes a progress bar look live.
 */
private const val MEDIA_POLL_INTERVAL_MS = 1_000L

/** THROWAWAY (Phase 0): everything `CastSession.onMessage` has a branch for. */
private val HANDLED_NAMESPACES = setOf(
    CastNamespaces.CONNECTION,
    CastNamespaces.HEARTBEAT,
    CastNamespaces.RECEIVER,
    CastNamespaces.MEDIA,
    CastNamespaces.WEBRTC,
)

/**
 * The single owner of the live cast session.
 *
 * An object rather than ViewModel-owned state, because both [CastViewModel] and [CastService]
 * act on the same session and neither reliably outlives the other: rotating the device rebuilds
 * the ViewModel, and the service is what keeps the session alive while the app is not in front.
 * `:share`'s `ShareReceiveController` exists for the same reason.
 *
 * Everything that touches [CastSession] happens under [mutex] on [scope], because the session is
 * a plain mutable state machine and the frames it returns have to reach the socket in the order
 * it produced them.
 */
object CastController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var channel: CastChannel? = null
    private var session: CastSession? = null
    private var fileServer: MediaFileServer? = null
    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null
    private var pollJob: Job? = null

    /** THROWAWAY (Phase 0): waits for the mirroring app to join, then sends one OFFER. */
    private var spikeJob: Job? = null

    private var discoveryManager: CastDiscoveryManager? = null

    private val _device = MutableStateFlow<CastDevice?>(null)
    val device: StateFlow<CastDevice?> = _device.asStateFlow()

    private val _sessionState = MutableStateFlow(CastSessionState())
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    /** True from the moment a device is tapped until the app on it is joined or refuses. */
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    fun discovery(context: Context): CastDiscoveryManager =
        discoveryManager ?: CastDiscoveryManager(context.applicationContext)
            .also { discoveryManager = it }

    /**
     * Open a channel to [device] and launch the media receiver on it.
     *
     * A no-op for the device already connected to; switching devices tears the old session down
     * first, since a sender holds one session at a time.
     */
    fun connect(context: Context, device: CastDevice) {
        if (_device.value?.id == device.id && _sessionState.value.phase != CastPhase.Idle) return
        openSession(context, device, DEFAULT_MEDIA_RECEIVER_APP_ID)
    }

    /**
     * THROWAWAY (Phase 0): launch a Cast Streaming receiver instead of the media receiver and
     * put one hand-written OFFER on the `webrtc` namespace.
     *
     * The only question it answers is whether an unregistered sender is allowed to launch these
     * app ids at all. Deleted in Phase 1 whichever way that goes; nothing is built on top of it
     * until it comes back green.
     *
     * Unguarded by the same-device check [connect] has, because trying the next app id on the
     * device already connected to is the whole point.
     */
    fun spikeMirror(context: Context, device: CastDevice, appId: String) {
        openSession(context, device, appId)
    }

    private fun openSession(context: Context, device: CastDevice, appId: String) {
        val appContext = context.applicationContext
        scope.launch {
            teardown()
            _device.value = device
            _isConnecting.value = true
            val newChannel = CastChannel(device.host, device.port)
            val newSession = CastSession(appId)
            try {
                newChannel.connect()
            } catch (e: Exception) {
                Log.w(TAG, "could not open a channel to ${device.host}", e)
                _isConnecting.value = false
                _device.value = null
                AppMessages.show(
                    appContext.getString(R.string.cast_connect_failed, device.friendlyName),
                )
                return@launch
            }
            channel = newChannel
            session = newSession
            fileServer = MediaFileServer(appContext.contentResolver)
            startPump(appContext, newChannel, newSession)
            send(newChannel, newSession) { it.open() }
            heartbeatJob = scope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    send(newChannel, newSession) { listOf(it.heartbeat()) }
                }
            }
            pollJob = scope.launch {
                while (true) {
                    delay(MEDIA_POLL_INTERVAL_MS)
                    if (_sessionState.value.playerState != CastPlayerState.Playing) continue
                    send(newChannel, newSession) { it.refreshMediaStatus() }
                }
            }
            if (appId != DEFAULT_MEDIA_RECEIVER_APP_ID) {
                startSpike(newChannel, newSession, appId)
            }
            // Started only once a channel is actually open, so a failed connection does not
            // leave a notification behind.
            CastService.start(appContext)
        }
    }

    /**
     * THROWAWAY (Phase 0).
     *
     * Waits for the LAUNCH to resolve either way rather than assuming it succeeds, because a
     * refusal is the result the spike is most likely to produce and it has to be legible in the
     * log rather than showing up as an OFFER that never gets an answer.
     */
    private fun startSpike(
        activeChannel: CastChannel,
        activeSession: CastSession,
        appId: String,
    ) {
        activeSession.onWebrtcPayload = { Log.i(TAG, "spike $appId: webrtc <- $it") }
        spikeJob = scope.launch {
            val resolved = _sessionState.first {
                it.phase == CastPhase.Ready || it.phase == CastPhase.Failed
            }
            if (resolved.phase == CastPhase.Failed) {
                Log.w(TAG, "spike $appId: LAUNCH refused, reason=${resolved.failure}")
                return@launch
            }
            Log.i(
                TAG,
                "spike $appId: launched, sessionId=${resolved.sessionId} " +
                    "transportId=${resolved.transportId}",
            )
            val audioOnly = MirroringAppIds.isAudioOnly(appId)
            send(activeChannel, activeSession) { session ->
                session.sendSpikeOffer(audioOnly).onEach {
                    Log.i(TAG, "spike $appId: webrtc -> ${it.payload}")
                }
            }
        }
    }

    /** Stop the receiver app, close the channel and the file server, and drop the session. */
    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val activeChannel = channel
            val activeSession = session
            if (activeChannel != null && activeSession != null) {
                send(activeChannel, activeSession) { it.close() }
            }
            teardown()
            CastService.stop(appContext)
        }
    }

    /** Cast a remote URL. Nothing is served locally, so the file server stays out of it. */
    fun castUrl(url: String, mimeType: String, title: String?) {
        act { it.load(mediaInformation(url, mimeType, title)) }
    }

    /**
     * Cast a local `content://` URI by serving it over the LAN for the receiver to fetch.
     *
     * Says so and gives up when the file cannot be served: a receiver handed an unreachable URL
     * reports a generic load failure, which is a worse thing to show than the real reason.
     */
    fun castLocalFile(context: Context, uri: Uri, mimeType: String, title: String?) {
        val appContext = context.applicationContext
        scope.launch {
            // Checked before binding a port: starting the server for a session that does not
            // exist would leave the file readable on the LAN with nothing to tear it down.
            if (channel == null || session == null) return@launch
            val server = fileServer
                ?: MediaFileServer(appContext.contentResolver).also { fileServer = it }
            val url = server.start(uri, mimeType)
            if (url == null) {
                AppMessages.show(appContext.getString(R.string.cast_server_failed))
                return@launch
            }
            act { it.load(mediaInformation(url, mimeType, title)) }
        }
    }

    fun play() = act { it.play() }

    fun pause() = act { it.pause() }

    fun stopPlayback() = act { it.stopMedia() }

    fun seek(positionSec: Double) = act { it.seek(positionSec) }

    fun setVolume(level: Double) = act { it.setVolume(level) }

    fun setMuted(muted: Boolean) = act { it.setMuted(muted) }

    private fun mediaInformation(url: String, mimeType: String, title: String?) =
        CastMediaInformation(
            contentId = url,
            contentType = mimeType,
            metadata = title?.let { CastMediaMetadata(title = it) },
        )

    /** Run [block] against the live session, if there is one, and send what it produces. */
    private fun act(block: (CastSession) -> List<CastFrame>) {
        scope.launch {
            val activeChannel = channel ?: return@launch
            val activeSession = session ?: return@launch
            send(activeChannel, activeSession, block)
        }
    }

    private suspend fun send(
        activeChannel: CastChannel,
        activeSession: CastSession,
        block: (CastSession) -> List<CastFrame>,
    ) = mutex.withLock {
        val frames = block(activeSession)
        publish(activeSession)
        for (frame in frames) {
            try {
                activeChannel.send(frame)
            } catch (e: Exception) {
                Log.w(TAG, "send failed on ${frame.namespace}", e)
                break
            }
        }
    }

    private fun startPump(
        context: Context,
        activeChannel: CastChannel,
        activeSession: CastSession,
    ) {
        pumpJob = scope.launch {
            try {
                activeChannel.messages().collect { message ->
                    // THROWAWAY (Phase 0): a `tp.deviceauth` challenge would otherwise be
                    // dropped in silence - CastSession ignores namespaces it does not know and
                    // the payload is protobuf, not JSON, so it fails to parse before it is even
                    // routed. Whether one arrives decides whether mirroring needs a whole
                    // sub-project, so it has to be visible.
                    if (message.namespace !in HANDLED_NAMESPACES) {
                        Log.i(TAG, "unhandled namespace ${message.namespace}")
                    }
                    mutex.withLock {
                        val replies =
                            activeSession.onMessage(message.namespace, message.payloadUtf8)
                        publish(activeSession)
                        replies.forEach { activeChannel.send(it) }
                    }
                }
                Log.i(TAG, "channel closed by the receiver")
            } catch (e: CancellationException) {
                // Cancelled by teardown, which is already doing all of this - and by the time
                // we get here a *newer* channel and session may already be installed, so
                // tearing down again would wipe a session that has only just come up.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "channel read loop ended", e)
            }
            // The link died on its own. Clearing the reference first matters: teardown cancels
            // pumpJob, and this coroutine *is* pumpJob.
            pumpJob = null
            teardown()
            CastService.stop(context)
        }
    }

    private fun publish(activeSession: CastSession) {
        val state = activeSession.state
        _sessionState.value = state
        if (state.phase == CastPhase.Ready || state.phase == CastPhase.Failed) {
            _isConnecting.value = false
        }
    }

    /**
     * Drop everything.
     *
     * The socket and the file server are closed *before* [mutex] is taken, because closing is
     * what unblocks a reader or writer parked in the socket - and one of those may be the
     * coroutine currently holding the lock. The state reset then happens under the lock, so an
     * in-flight [send] cannot publish the dead session's state back over the cleared one.
     */
    private suspend fun teardown() {
        pumpJob?.cancel()
        heartbeatJob?.cancel()
        pollJob?.cancel()
        spikeJob?.cancel()
        channel?.close()
        fileServer?.stop()
        mutex.withLock {
            pumpJob = null
            heartbeatJob = null
            pollJob = null
            spikeJob = null
            fileServer = null
            channel = null
            session = null
            _device.value = null
            _isConnecting.value = false
            _sessionState.value = CastSessionState()
        }
    }
}
