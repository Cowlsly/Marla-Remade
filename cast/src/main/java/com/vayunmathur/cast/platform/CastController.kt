package com.vayunmathur.cast.platform

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastFrame
import com.vayunmathur.cast.domain.CastPhase
import com.vayunmathur.cast.domain.CastSession
import com.vayunmathur.cast.domain.CastSessionState
import com.vayunmathur.cast.domain.MirroringAppIds
import com.vayunmathur.cast.domain.streaming.NegotiationFailure
import com.vayunmathur.cast.domain.streaming.StreamSelection
import com.vayunmathur.cast.domain.streaming.StreamingSession
import com.vayunmathur.cast.domain.streaming.encode
import com.vayunmathur.cast.domain.streaming.parseAnswer
import com.vayunmathur.cast.network.CastChannel
import com.vayunmathur.cast.platform.discovery.CastDiscoveryManager
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.MirrorConsentActivity
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorGeometry
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.platform.mirror.MirrorStopReason
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.library.ui.ExternalIntents
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
    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null

    /** The OFFER/ANSWER half, alive only while a mirror is being negotiated or run. */
    private var streaming: StreamingSession? = null
    private var engine: MirrorEngine? = null

    private val _mirrorPhase = MutableStateFlow(MirrorPhase.Idle)
    val mirrorPhase: StateFlow<MirrorPhase> = _mirrorPhase.asStateFlow()

    private val _degradation = MutableStateFlow(MirrorDegradation())
    val degradation: StateFlow<MirrorDegradation> = _degradation.asStateFlow()

    /**
     * Why mirroring failed, already a user-facing sentence.
     *
     * Separate from `CastSessionState.failure`, which is a raw `LAUNCH_ERROR` reason: these come
     * from our own pipeline and there is no wire constant to translate.
     */
    private val _failure = MutableStateFlow<String?>(null)
    val mirrorFailure: StateFlow<String?> = _failure.asStateFlow()

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
     * Open a channel to [device], launch the mirroring receiver, and start mirroring.
     *
     * Connecting and mirroring are one action because there is nothing else this app does. A joined
     * receiver that is not mirroring is a dead state: the TV is already showing the receiver's idle
     * screen and the only thing the connection could still be used for is volume. So tapping a
     * device asks for capture consent as soon as the receiver is joined.
     *
     * A no-op for the device already mirroring to; switching devices tears the old session down
     * first, since a sender holds one session at a time. The app id follows from [CastDevice.kind]
     * and is not negotiable - a receiver refuses the wrong one at LAUNCH.
     *
     * A [CastPhase.Failed] session is *not* treated as live, so tapping the same device again
     * retries rather than doing nothing. A refusal is a routine outcome here - the receiver says no
     * whenever the capability bitmask and the app id disagree - so a dead end would be a bug.
     */
    fun connect(context: Context, device: CastDevice, thenMirror: Boolean = true) {
        val phase = _sessionState.value.phase
        val live = phase != CastPhase.Idle && phase != CastPhase.Failed
        if (_device.value?.id == device.id && live) return
        val appContext = context.applicationContext
        scope.launch {
            teardown()
            _device.value = device
            _isConnecting.value = true
            val newChannel = CastChannel(device.host, device.port)
            val newSession = CastSession(MirroringAppIds.forKind(device.kind))
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
            // Whatever the user last tapped is the tile's target from now on.
            MirrorPreferences.setTarget(appContext, device)
            startPump(appContext, newChannel, newSession)
            send(newChannel, newSession) { it.open() }
            heartbeatJob = scope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    send(newChannel, newSession) { listOf(it.heartbeat()) }
                }
            }
            // Started only once a channel is actually open, so a failed connection does not
            // leave a notification behind.
            CastService.start(appContext)
            if (!thenMirror) return@launch
            // Consent can only be asked for by an Activity, and only once the receiver is joined -
            // an OFFER sent before that is dropped without an error. Waiting for Failed too so a
            // refused LAUNCH does not leave a consent dialog queued behind it.
            val resolved = _sessionState.first {
                it.phase == CastPhase.Ready || it.phase == CastPhase.Idle ||
                    it.phase == CastPhase.Failed
            }
            if (resolved.phase == CastPhase.Ready) {
                ExternalIntents.launch(appContext, MirrorConsentActivity.intent(appContext))
            }
        }
    }

    /** Stop the receiver app, close the channel and drop the session. */
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

    /**
     * Log every packet and a throughput summary once a second.
     *
     * A plain switch rather than a build flag: mirroring can only be diagnosed on hardware, and
     * something that needs a recompile to turn on does not get turned on.
     */
    var verboseStreamLogging: Boolean = false

    fun setVolume(level: Double) = act { it.setVolume(level) }

    /**
     * Begin mirroring with an already-granted projection.
     *
     * Called from [CastService] rather than from the UI, because the projection may only be
     * obtained after the service is in the foreground - see `MirrorConsentActivity` for the full
     * ordering constraint.
     *
     * The OFFER goes out only once the receiver app is joined; before that the `webrtc` namespace
     * is not addressable and the frame would be dropped without an error.
     */
    fun startMirroring(context: Context, projection: MediaProjection) {
        val appContext = context.applicationContext
        scope.launch {
            val activeChannel = channel
            val activeSession = session
            val device = _device.value
            if (activeChannel == null || activeSession == null || device == null) {
                Log.w(TAG, "asked to mirror with no session")
                projection.stop()
                return@launch
            }
            stopEngine()
            _mirrorPhase.value = MirrorPhase.Negotiating
            _degradation.value = MirrorDegradation()
            _failure.value = null
            // The capture size is decided here, before the OFFER, so the resolution advertised is
            // the resolution actually sent. Offering 1280x720 and then sending a portrait frame
            // tells the receiver to expect a shape it never gets.
            val geometry = MirrorGeometry.forDisplay(appContext)
            val plan = StreamSelection.offer(
                kind = device.kind,
                videoWidth = geometry.width,
                videoHeight = geometry.height,
                videoBitRate = geometry.bitRate,
            )
            val streamingSession = StreamingSession(plan)
            streaming = streamingSession
            // Fed by CastSession's webrtc route rather than parsed there: OFFER/ANSWER is its own
            // state machine and the control plane has no business understanding it.
            activeSession.onWebrtcPayload = { payload ->
                onWebrtcPayload(appContext, payload, projection, device.host, geometry)
            }
            send(activeChannel, activeSession) {
                it.webrtcFrame(plan.message(it.allocateRequestId()).encode())
            }
        }
    }

    fun stopMirroring(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            stopEngine()
            _mirrorPhase.value = MirrorPhase.Idle
            CastService.stopMirroring(appContext)
        }
    }

    private fun onWebrtcPayload(
        context: Context,
        payload: String,
        projection: MediaProjection,
        host: String,
        geometry: CaptureGeometry,
    ) {
        val answer = parseAnswer(payload) ?: return
        val streamingSession = streaming ?: return
        scope.launch {
            val failure = streamingSession.onAnswer(answer)
            if (failure != null) {
                Log.w(TAG, "the receiver would not agree a stream: $failure")
                abandonMirroring(
                    context,
                    projection,
                    context.getString(
                        when (failure) {
                            is NegotiationFailure.NoStreams -> R.string.cast_mirror_no_streams
                            else -> R.string.cast_mirror_negotiation_failed
                        },
                    ),
                )
                return@launch
            }
            val negotiation = streamingSession.negotiation
            if (negotiation == null) {
                // onAnswer reported no failure, so this cannot happen - but leaving a live
                // projection behind if it ever did would be a camera-light-style bug.
                Log.w(TAG, "an accepted answer produced no negotiation")
                abandonMirroring(
                    context,
                    projection,
                    context.getString(R.string.cast_mirror_negotiation_failed),
                )
                return@launch
            }
            val newEngine = MirrorEngine(
                context = context,
                projection = projection,
                receiverHost = host,
                negotiation = negotiation,
                geometry = geometry,
                session = streamingSession,
                onDegraded = { _degradation.value = it },
                onStopped = { reason -> onEngineStopped(context, reason) },
            ).apply { hexDump = verboseStreamLogging }
            engine = newEngine
            if (newEngine.start()) {
                _mirrorPhase.value = MirrorPhase.Mirroring
            } else {
                // start() already called onStopped, which set the message and the phase; all that
                // is left is to make sure nothing keeps holding the screen.
                engine = null
                runCatching { projection.stop() }
            }
        }
    }

    /** Give up on mirroring, and make sure the screen stops being captured. */
    private fun abandonMirroring(context: Context, projection: MediaProjection, message: String) {
        _mirrorPhase.value = MirrorPhase.Failed
        _failure.value = message
        streaming = null
        runCatching { projection.stop() }
        CastService.stopMirroring(context)
    }

    private fun onEngineStopped(context: Context, reason: MirrorStopReason) {
        _failure.value = context.getString(
            when (reason) {
                MirrorStopReason.Udp -> R.string.cast_mirror_udp_failed
                MirrorStopReason.NoEncoders -> R.string.cast_mirror_no_encoder
                MirrorStopReason.NoAudioForSpeaker -> R.string.cast_mirror_no_audio
            },
        )
        _mirrorPhase.value = MirrorPhase.Failed
        CastService.stopMirroring(context)
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        streaming = null
        session?.onWebrtcPayload = null
    }

    fun setMuted(muted: Boolean) = act { it.setMuted(muted) }

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
     * The socket is closed *before* [mutex] is taken, because closing is what unblocks a reader
     * or writer parked in it - and one of those may be the coroutine currently holding the lock.
     * The state reset then happens under the lock, so an in-flight [send] cannot publish the
     * dead session's state back over the cleared one.
     */
    private suspend fun teardown() {
        pumpJob?.cancel()
        heartbeatJob?.cancel()
        stopEngine()
        channel?.close()
        mutex.withLock {
            pumpJob = null
            heartbeatJob = null
            channel = null
            session = null
            _device.value = null
            _isConnecting.value = false
            _sessionState.value = CastSessionState()
            _mirrorPhase.value = MirrorPhase.Idle
            _degradation.value = MirrorDegradation()
            _failure.value = null
        }
    }
}
