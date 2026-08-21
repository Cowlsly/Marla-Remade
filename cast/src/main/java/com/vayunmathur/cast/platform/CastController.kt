package com.vayunmathur.cast.platform

import android.content.Context
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.platform.discovery.CastDiscoveryManager
import com.vayunmathur.cast.platform.mirror.MirrorConsentActivity
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorGeometry
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.platform.mirror.MirrorStopReason
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.StreamingSession
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.sdk.cast.CastContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "CastController"

/** What [CastController.startContentSession] managed. */
sealed interface ContentSessionResult {

    /**
     * Live. [surface] is the encoder's input surface and [audioWriteEnd] the PCM pipe, both of which
     * are the caller's to hand to the SDK client - and [audioWriteEnd] is the caller's to close once it
     * has been sent.
     *
     * The geometry is what the TV and this phone's encoder actually agreed, not what was asked for.
     */
    class Started(
        val surface: Surface,
        val audioWriteEnd: ParcelFileDescriptor?,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val receiverName: String,
    ) : ContentSessionResult

    /** [reason] is one of `CastContract`'s `REASON_` values, ready to send straight back. */
    class Failed(val reason: Int) : ContentSessionResult
}

/**
 * The single owner of the live session.
 *
 * An object rather than ViewModel-owned state, because both [CastViewModel] and [CastService] act on
 * the same session and neither reliably outlives the other: rotating the device rebuilds the ViewModel,
 * and the service is what keeps the session alive while the app is not in front. `:share`'s
 * `ShareReceiveController` exists for the same reason.
 *
 * **The shape of a session changed with the protocol.** Under Cast the sequence was CONNECT → LAUNCH →
 * join → OFFER, and consent had to wait for the receiver app to come up. Now it is: connect, pair
 * (possibly waiting for six digits from the user), *then* ask for consent, then configure the stream
 * against the TV's real limits. Pairing before consent is deliberate - there is no point recording the
 * screen for a TV that is going to reject the code.
 *
 * Everything that touches [MirrorClient] happens on [scope] under [mutex], because it is a plain
 * sequential exchange on one socket and two callers interleaving would desynchronise the stream.
 */
object CastController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var socket: ControlSocket? = null
    private var client: MirrorClient? = null
    private var watchJob: Job? = null

    /** The running mirror. Its retransmit buffers live per-stream inside it. */
    private var engine: MirrorEngine? = null

    private val _mirrorPhase = MutableStateFlow(MirrorPhase.Idle)
    val mirrorPhase: StateFlow<MirrorPhase> = _mirrorPhase.asStateFlow()

    private val _degradation = MutableStateFlow(MirrorDegradation())
    val degradation: StateFlow<MirrorDegradation> = _degradation.asStateFlow()

    /** Why mirroring failed, already a user-facing sentence. */
    private val _failure = MutableStateFlow<String?>(null)
    val mirrorFailure: StateFlow<String?> = _failure.asStateFlow()

    private var discoveryManager: CastDiscoveryManager? = null

    private val _device = MutableStateFlow<CastDevice?>(null)
    val device: StateFlow<CastDevice?> = _device.asStateFlow()

    private val _sessionState = MutableStateFlow(ClientState())
    val sessionState: StateFlow<ClientState> = _sessionState.asStateFlow()

    /** True from the moment a device is tapped until it is paired or refuses. */
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /**
     * Whether the session being opened should go on to ask for capture consent.
     *
     * Remembered from [connect] because a code pairing returns to the user and comes back through
     * [submitPairCode], which would otherwise have to assume - and assuming "yes" is what would put the
     * screen-capture dialog in front of an app that launched the picker to send its own content.
     */
    private var pendingMirror: Boolean = true

    /**
     * Notified when an SDK session ends for a reason the app did not ask for.
     *
     * Set by `ContentCastService` for the life of one session and cleared as it ends, so there is
     * exactly one at a time - which matches the one session this object holds. A callback rather than a
     * flow because there is nothing to observe when it is absent, and a reason has to reach the client
     * once rather than be a current value.
     */
    var onContentSessionEnded: ((Int) -> Unit)? = null

    /**
     * The name of the app that last completed `CastPickerActivity`, resolved from its
     * `callingPackage`.
     *
     * Kept here rather than passed through the IPC on purpose: a self-reported label would be a lie an
     * app could tell, and the TV displays this. Empty means screen mirroring.
     */
    var contentAppLabel: String = ""

    fun discovery(context: Context): CastDiscoveryManager =
        discoveryManager ?: CastDiscoveryManager(context.applicationContext)
            .also { discoveryManager = it }

    /**
     * Log every packet and a throughput summary once a second.
     *
     * A plain switch rather than a build flag: mirroring can only be diagnosed on hardware, and
     * something that needs a recompile to turn on does not get turned on.
     */
    var verboseStreamLogging: Boolean = false

    /**
     * Open a control channel to [device] and pair with it.
     *
     * Connecting and mirroring are one action because there is nothing else this app does. A paired TV
     * that is not mirroring is a dead state - it is sitting on its idle screen - so a successful pair
     * goes straight on to asking for capture consent.
     *
     * A no-op for the device already connected to; switching TVs tears the old session down first, since
     * a phone holds one session at a time. A [ClientPhase.Failed] session is *not* treated as live, so
     * tapping the same device again retries rather than doing nothing.
     */
    fun connect(context: Context, device: CastDevice, thenMirror: Boolean = true) {
        val phase = _sessionState.value.phase
        val live = phase != ClientPhase.Idle && phase != ClientPhase.Failed
        if (_device.value?.id == device.id && live) return
        val appContext = context.applicationContext
        pendingMirror = thenMirror
        scope.launch {
            teardown()
            _device.value = device
            _isConnecting.value = true
            _sessionState.value = ClientState(phase = ClientPhase.Connecting)

            if (device.protocolVersion != 0 && device.protocolVersion != PROTOCOL_VERSION) {
                // Said before connecting rather than after a failed handshake: the TXT record already
                // told us, and a clear message beats a socket that opens and then gives up.
                fail(appContext, ClientFailure.VersionMismatch)
                return@launch
            }

            val newSocket = ControlSocket(device.host, device.port)
            try {
                newSocket.connect()
            } catch (e: Exception) {
                Log.w(TAG, "could not open a control channel to ${device.host}:${device.port}", e)
                _isConnecting.value = false
                _device.value = null
                _sessionState.value = ClientState()
                AppMessages.show(
                    appContext.getString(R.string.cast_connect_failed, device.friendlyName),
                )
                return@launch
            }
            socket = newSocket
            // Whatever the user last tapped is the tile's target from now on.
            MirrorPreferences.setTarget(appContext, device)

            val senderId = MirrorPreferences.senderId(appContext)
            val storedKey = MirrorPreferences.deviceKey(appContext, device.id)
            val newClient = MirrorClient(
                socket = newSocket,
                senderId = senderId,
                storedDeviceKey = storedKey,
            )
            client = newClient
            // Started only once a channel is actually open, so a failed connection does not leave a
            // notification behind.
            CastService.start(appContext)

            when (val outcome = mutex.withLock { newClient.begin() }) {
                is HandshakeOutcome.Paired -> {
                    onPaired(appContext, device, newClient, outcome.deviceKey, thenMirror)
                }
                is HandshakeOutcome.NeedsCode -> {
                    _sessionState.value = ClientState(
                        phase = ClientPhase.AwaitingCode,
                        receiverName = newClient.receiverName,
                        attemptsLeft = outcome.attemptsLeft,
                    )
                    _isConnecting.value = false
                }
                is HandshakeOutcome.Failed -> fail(appContext, outcome.reason)
                is HandshakeOutcome.Ready -> Unit // begin() cannot produce this.
            }
        }
    }

    /**
     * Submit the six digits the user read off the TV.
     *
     * A wrong code is a routine outcome and leaves the session open, so the user can simply try again -
     * which is also why this reports [ClientState.attemptsLeft] rather than just failing.
     */
    fun submitPairCode(context: Context, code: String) {
        val appContext = context.applicationContext
        scope.launch {
            val activeClient = client ?: return@launch
            val device = _device.value ?: return@launch
            when (val outcome = mutex.withLock { activeClient.enterCode(code) }) {
                is HandshakeOutcome.Paired ->
                    onPaired(appContext, device, activeClient, outcome.deviceKey, pendingMirror)
                is HandshakeOutcome.NeedsCode -> _sessionState.update {
                    it.copy(
                        phase = ClientPhase.AwaitingCode,
                        // -1 means "that was not even six digits", so the allowance is unchanged.
                        attemptsLeft = if (outcome.attemptsLeft < 0) it.attemptsLeft else outcome.attemptsLeft,
                        codeChanged = outcome.codeChanged,
                    )
                }
                is HandshakeOutcome.Failed -> fail(appContext, outcome.reason)
                is HandshakeOutcome.Ready -> Unit
            }
        }
    }

    private suspend fun onPaired(
        context: Context,
        device: CastDevice,
        activeClient: MirrorClient,
        deviceKey: ByteArray?,
        thenMirror: Boolean,
    ) {
        // Persisted against the TV's own id rather than the mDNS instance name, so renaming the TV does
        // not make the phone ask for a code again.
        if (deviceKey != null) {
            MirrorPreferences.rememberDeviceKey(context, activeClient.receiverId ?: device.id, deviceKey)
        }
        _sessionState.value = ClientState(
            phase = ClientPhase.Paired,
            receiverName = activeClient.receiverName,
        )
        _isConnecting.value = false
        // **No watch loop yet.** `awaitEnd` reads the socket, and `configureStream` still has a
        // request/response to do on it - a second reader would consume the STREAM_READY that
        // negotiation is waiting for. The watch starts once the exchange is finished; until then a dead
        // TV surfaces as a failed configureStream, which is just as prompt.
        if (!thenMirror) return
        // Consent is asked for only now: there is no point recording the screen for a TV that was going
        // to reject the code.
        ExternalIntents.launch(context, MirrorConsentActivity.intent(context))
    }

    /** Say goodbye, close the channel and drop the session. */
    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            client?.let { mutex.withLock { it.sayGoodbye("user disconnected") } }
            teardown()
            CastService.stop(appContext)
        }
    }

    /**
     * Begin mirroring with an already-granted projection.
     *
     * Called from [CastService] rather than from the UI, because the projection may only be obtained
     * after the service is in the foreground - see `MirrorConsentActivity` for the full ordering
     * constraint.
     */
    fun startMirroring(context: Context, projection: MediaProjection) {
        val appContext = context.applicationContext
        scope.launch {
            val activeClient = client
            val device = _device.value
            if (activeClient == null || device == null) {
                Log.w(TAG, "asked to mirror with no session")
                projection.stop()
                return@launch
            }
            // The screen and an app's content are mutually exclusive - there is one session, one
            // encoder and one socket - so whichever was running loses, with the SDK client told why
            // rather than left drawing into a dead surface.
            endContentSession(CastContract.REASON_PREEMPTED)
            stopEngine()
            _mirrorPhase.value = MirrorPhase.Negotiating
            _degradation.value = MirrorDegradation()
            _failure.value = null

            // The frame size is chosen from the TV's own reported limits, and it is the phone's real
            // aspect ratio: the receiver letterboxes, so none of the encoded frame is wasted on bars.
            val geometry = MirrorGeometry.forDisplay(appContext, activeClient.limits)
            val frameRate = MirrorGeometry.frameRateFor(activeClient.limits)
            val outcome = mutex.withLock {
                activeClient.configureStream(
                    width = geometry.width,
                    height = geometry.height,
                    frameRate = frameRate,
                    bitRate = geometry.bitRate,
                    audio = true,
                    video = true,
                )
            }
            val ready = outcome as? HandshakeOutcome.Ready
            if (ready == null) {
                Log.w(TAG, "the TV would not agree a stream: $outcome")
                abandonMirroring(
                    appContext,
                    projection,
                    appContext.getString(R.string.cast_mirror_negotiation_failed),
                )
                return@launch
            }

            val newEngine = MirrorEngine(
                context = appContext,
                source = MirrorSource.Screen(projection),
                receiverHost = device.host,
                negotiation = ready.negotiation,
                geometry = geometry,
                frameRate = frameRate,
                onDegraded = { _degradation.value = it },
                onStopped = { reason -> onEngineStopped(appContext, reason) },
            ).apply { hexDump = verboseStreamLogging }
            engine = newEngine
            if (newEngine.start()) {
                _mirrorPhase.value = MirrorPhase.Mirroring
                _sessionState.update {
                    it.copy(phase = ClientPhase.Streaming, negotiation = ready.negotiation)
                }
                startWatch(appContext, activeClient)
            } else {
                // start() already called onStopped, which set the message and the phase; all that is
                // left is to make sure nothing keeps holding the screen.
                engine = null
                runCatching { projection.stop() }
            }
        }
    }

    /**
     * Stream another app's content instead of the screen.
     *
     * The second entry point beside [startMirroring], and the only one `:sdk:cast` reaches. Requires a
     * TV already connected and paired - which is what `CastPickerActivity` is for - because there is
     * nothing an SDK caller could do about a pair code, and the picker is also what establishes the
     * [appLabel] the TV displays.
     *
     * Suspends until the stream is live or has failed, so the service can answer
     * `MSG_SESSION_READY` with real numbers rather than a promise. Screen mirroring and this remain
     * mutually exclusive; the single [engine] is what enforces it.
     */
    suspend fun startContentSession(
        context: Context,
        width: Int,
        height: Int,
        wantAudio: Boolean,
        appLabel: String,
    ): ContentSessionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val activeClient = client
        val device = _device.value
        val phase = _sessionState.value.phase
        if (activeClient == null || device == null ||
            (phase != ClientPhase.Paired && phase != ClientPhase.Streaming)
        ) {
            Log.w(TAG, "asked for an app-content session with no paired TV")
            return@withContext ContentSessionResult.Failed(CastContract.REASON_NO_SESSION)
        }
        endContentSession(CastContract.REASON_PREEMPTED)
        stopEngine()
        _mirrorPhase.value = MirrorPhase.Negotiating
        _degradation.value = MirrorDegradation()
        _failure.value = null

        val geometry = MirrorGeometry.forContent(width, height, activeClient.limits)
        val frameRate = MirrorGeometry.frameRateFor(activeClient.limits)
        val outcome = mutex.withLock {
            activeClient.configureStream(
                width = geometry.width,
                height = geometry.height,
                frameRate = frameRate,
                bitRate = geometry.bitRate,
                audio = wantAudio,
                video = true,
                appLabel = appLabel,
            )
        }
        val ready = outcome as? HandshakeOutcome.Ready
        if (ready == null) {
            Log.w(TAG, "the TV would not agree an app-content stream: $outcome")
            _mirrorPhase.value = MirrorPhase.Failed
            _failure.value = appContext.getString(R.string.cast_mirror_negotiation_failed)
            return@withContext ContentSessionResult.Failed(CastContract.REASON_FAILED)
        }

        val newEngine = MirrorEngine(
            context = appContext,
            source = MirrorSource.Content(appLabel = appLabel, wantAudio = wantAudio),
            receiverHost = device.host,
            negotiation = ready.negotiation,
            geometry = geometry,
            frameRate = frameRate,
            onDegraded = { _degradation.value = it },
            onStopped = { reason -> onEngineStopped(appContext, reason) },
        ).apply { hexDump = verboseStreamLogging }
        engine = newEngine
        // No surface means there is nowhere for the app to draw, which for an SDK session is the whole
        // point - unlike mirroring, it cannot usefully degrade to audio only.
        val surface = if (newEngine.start()) newEngine.contentSurface else null
        if (surface == null) {
            newEngine.stop()
            engine = null
            _mirrorPhase.value = MirrorPhase.Failed
            return@withContext ContentSessionResult.Failed(CastContract.REASON_FAILED)
        }
        _mirrorPhase.value = MirrorPhase.Mirroring
        _sessionState.update {
            it.copy(phase = ClientPhase.Streaming, negotiation = ready.negotiation)
        }
        startWatch(appContext, activeClient)
        ContentSessionResult.Started(
            surface = surface,
            audioWriteEnd = newEngine.audioWriteEnd,
            width = geometry.width,
            height = geometry.height,
            frameRate = frameRate,
            receiverName = activeClient.receiverName ?: device.friendlyName,
        )
    }

    /**
     * Become the control socket's single reader, now the exchange is finished.
     *
     * **Not started any earlier.** [MirrorClient.awaitEnd] reads the socket, and `configureStream` has
     * a request/response to do on it - a second reader would consume the `STREAM_READY` that
     * negotiation is waiting for. Until this starts, a dead TV surfaces as a failed `configureStream`,
     * which is just as prompt.
     */
    private fun startWatch(appContext: Context, activeClient: MirrorClient) {
        watchJob = scope.launch {
            activeClient.awaitEnd()
            Log.i(TAG, "the control channel closed")
            // Cleared first: teardown cancels watchJob, and this coroutine *is* watchJob.
            watchJob = null
            teardown()
            CastService.stop(appContext)
        }
    }

    fun stopMirroring(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            endContentSession(CastContract.REASON_CLIENT_CLOSED)
            stopEngine()
            _mirrorPhase.value = MirrorPhase.Idle
            _sessionState.update {
                if (it.phase == ClientPhase.Streaming) it.copy(phase = ClientPhase.Paired) else it
            }
            CastService.stopMirroring(appContext)
        }
    }

    private suspend fun fail(context: Context, reason: ClientFailure) {
        _sessionState.value = ClientState(
            phase = ClientPhase.Failed,
            receiverName = client?.receiverName,
            failure = reason,
        )
        _isConnecting.value = false
        teardown(keepFailure = true)
        CastService.stop(context)
    }

    /** Give up on mirroring, and make sure the screen stops being captured. */
    private fun abandonMirroring(context: Context, projection: MediaProjection, message: String) {
        _mirrorPhase.value = MirrorPhase.Failed
        _failure.value = message
        runCatching { projection.stop() }
        CastService.stopMirroring(context)
    }

    private fun onEngineStopped(context: Context, reason: MirrorStopReason) {
        _failure.value = context.getString(
            when (reason) {
                MirrorStopReason.Udp -> R.string.cast_mirror_udp_failed
                MirrorStopReason.NoEncoders -> R.string.cast_mirror_no_encoder
                MirrorStopReason.ReceiverGone -> R.string.cast_mirror_receiver_gone
            },
        )
        _mirrorPhase.value = MirrorPhase.Failed
        endContentSession(CastContract.REASON_FAILED)
        CastService.stopMirroring(context)
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    /**
     * Tell an SDK client its session is over, once.
     *
     * Cleared as it fires, so the client hears exactly one reason: a teardown runs through several of
     * these paths and a client told twice would react to the second after it had already cleaned up.
     */
    private fun endContentSession(reason: Int) {
        val notify = onContentSessionEnded ?: return
        onContentSessionEnded = null
        notify(reason)
    }

    /**
     * Drop everything.
     *
     * The socket is closed *before* [mutex] is taken, because closing is what unblocks a reader or
     * writer parked in it - and one of those may be the coroutine currently holding the lock. The state
     * reset then happens under the lock, so an in-flight exchange cannot publish the dead session's
     * state back over the cleared one.
     *
     * [keepFailure] is for the path that has just set a failure it wants the user to read; everything
     * else clears back to idle.
     */
    private suspend fun teardown(keepFailure: Boolean = false) {
        watchJob?.cancel()
        endContentSession(CastContract.REASON_RECEIVER_GONE)
        stopEngine()
        socket?.close()
        mutex.withLock {
            watchJob = null
            socket = null
            client = null
            _isConnecting.value = false
            _mirrorPhase.value = MirrorPhase.Idle
            _degradation.value = MirrorDegradation()
            if (!keepFailure) {
                _device.value = null
                _sessionState.value = ClientState()
                _failure.value = null
            }
        }
    }
}
