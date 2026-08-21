package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PairResult
import com.vayunmathur.cast.protocol.PairingGate
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.SecureRandom

private const val TAG = "ReceiverController"

/** How often the receiver's own throughput line goes out, paired with the sender's. */
private const val STATS_LOG_INTERVAL_MS = 1_000L

/** Feedback cadence. Well inside the 400 ms target playout delay, so a NACK can still be played. */
private const val FEEDBACK_INTERVAL_MS = 50L

/**
 * The single owner of the live receiving session.
 *
 * An object rather than ViewModel-owned state, for exactly the reason `:cast`'s `CastController` is:
 * `ReceiverService` keeps the sockets alive while nothing is on screen, and both `MainActivity` and
 * `MirrorActivity` observe the same session while neither reliably outlives the other.
 *
 * **One phone at a time, and a second connection is refused rather than swapped in.** A TV has one
 * screen, and letting any device on the LAN displace a running session would be a denial of service
 * with no authentication needed to mount it. A phone that dies is cleared by the control socket's read
 * timeout rather than by making that trade.
 */
object ReceiverController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    private var advertiser: ReceiverAdvertiser? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var sessionJob: Job? = null
    private var mediaJob: Job? = null

    /**
     * The surface `MirrorActivity` owns, when it has one.
     *
     * Video is **dropped rather than buffered** until it exists, and dropped before it reaches the
     * receiver session - see `MediaReceiver.pump`. Volatile because the media loop reads it while the
     * Activity's callbacks write it.
     */
    @Volatile
    private var surface: Surface? = null

    /** The frame size the phone said it would send, so the Activity can letterbox to it. */
    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    private val pairingGate = PairingGate()

    fun start(context: Context) {
        if (acceptJob != null) return
        val appContext = context.applicationContext
        acceptJob = scope.launch { listen(appContext) }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        // The session coroutine owns its own field; from outside, cancelling it is what ends it, and
        // its finally then stops the media loop through the same path a normal end takes.
        sessionJob?.cancel()
        endMedia()
        advertiser?.unadvertise()
        advertiser = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.update { it.copy(phase = ReceiverPhase.Starting) }
    }

    /** Called by `MirrorActivity` once its `SurfaceView` has a surface to draw into. */
    fun attachSurface(newSurface: Surface) {
        surface = newSurface
    }

    /**
     * The surface is going away - rotation, or the Activity finishing.
     *
     * Only the reference is cleared here. The media loop owns the decoder and releases it when it
     * next sees that there is nowhere to draw, so nothing is ever released underneath a `MediaCodec`
     * call in flight.
     */
    fun detachSurface() {
        surface = null
    }

    // ---- accepting ----

    private suspend fun listen(context: Context) {
        val store = PairingStore(context)
        val identity = PqcIdentity.loadOrCreate(store, prefix = "castTv")
        val deviceId = store.deviceId()
        val name = store.friendlyName(fallback = defaultName())
        val limits = VideoDecoder.limits()

        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.e(TAG, "could not bind a control socket", e)
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        serverSocket = server

        val nsd = ReceiverAdvertiser(context)
        advertiser = nsd
        nsd.advertise(friendlyName = name, deviceId = deviceId, port = server.localPort, limits = limits)
        _state.value = ReceiverUiState(
            phase = ReceiverPhase.Advertising,
            deviceName = name,
            localNetworkBlocked = nsd.localNetworkBlocked,
        )
        Log.i(TAG, "receiving as '$name' ($deviceId) on control port ${server.localPort}")

        while (scope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "accept failed", e)
                return
            }
            if (sessionJob?.isActive == true) {
                // One screen, one session. Refusing is what stops anyone on the LAN from displacing a
                // running mirror without authenticating at all.
                Log.i(TAG, "refusing ${socket.inetAddress} - already receiving")
                runCatching { socket.close() }
                continue
            }
            val channel = ControlChannel(socket)
            // A failure from the previous session has been on screen long enough; the phone connecting
            // now is what the user cares about.
            _state.update {
                if (it.phase is ReceiverPhase.Failed) it.copy(phase = ReceiverPhase.Advertising) else it
            }
            sessionJob = scope.launch {
                try {
                    runSession(channel, store, identity, deviceId, name, limits)
                } catch (e: Exception) {
                    Log.w(TAG, "session ended", e)
                } finally {
                    channel.close()
                    endMedia()
                    // A failure the user has to read is *not* overwritten with "ready" - it stays until
                    // the next phone tries, which is when it stops being the useful thing to show.
                    if (scope.isActive) {
                        _state.update {
                            if (it.phase is ReceiverPhase.Failed) {
                                it
                            } else {
                                it.copy(
                                    phase = ReceiverPhase.Advertising,
                                    localNetworkBlocked = nsd.localNetworkBlocked,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- one session ----

    private suspend fun runSession(
        channel: ControlChannel,
        store: PairingStore,
        identity: PqcIdentity,
        deviceId: String,
        deviceName: String,
        limits: DecoderLimits,
    ) {
        val transcript = Transcript()

        val hello = channel.receive() ?: return
        val greeting = hello.message as? Hello ?: return
        if (greeting.version != PROTOCOL_VERSION) {
            Log.w(TAG, "refusing protocol version ${greeting.version}, we speak $PROTOCOL_VERSION")
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        transcript.add(hello.body)
        Log.i(TAG, "'${greeting.senderName}' connected from ${channel.remoteAddress}")

        transcript.add(
            channel.send(
                TvIdentity(
                    receiverName = deviceName,
                    receiverId = deviceId,
                    publicBundle = ProtocolBase64.encode(identity.publicBundle),
                    limits = limits,
                ),
            ),
        )

        val sealed = channel.receive() ?: return
        val sealedSecret = sealed.message as? SealedSecret ?: return
        transcript.add(sealed.body)
        val secret = ProtocolBase64.decode(sealedSecret.sealed)
            ?.let { SecretSealing.open(identity, it) }
        if (secret == null) {
            // The phone sealed to a bundle that is not ours - a stale one from before a reset, or an
            // attacker's. Either way there is no shared secret and nothing to do but close.
            Log.w(TAG, "could not open the sealed secret; the phone has a stale identity for us")
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        val keys = SessionKeys.of(secret)
        // From here on the control channel is AES-256-GCM. Both ends install the cipher at exactly
        // this point, which is what keeps them in step without a per-frame flag an attacker could
        // clear.
        channel.codec.useSessionKey(keys.control)
        val transcriptValue = transcript.value()

        if (!authenticate(channel, store, keys, transcriptValue, greeting)) return
        _state.update { it.copy(phase = ReceiverPhase.Connected(greeting.senderName)) }

        val configured = channel.receive() ?: return
        val config = configured.message as? StreamConfig ?: return
        startStreaming(channel, keys, config, greeting.senderName)

        // The session now lives in the UDP loop; this coroutine stays here so a BYE or a dropped
        // socket tears everything down through the same path.
        while (true) {
            val next = channel.receive() ?: break
            if (next.message is Bye) {
                Log.i(TAG, "'${greeting.senderName}' said goodbye")
                break
            }
        }
    }

    /**
     * Pair, or prove a phone we already trust.
     *
     * The attempt loop stays open on a wrong code so the user can simply type it again; the socket's
     * read timeout is what ends a session nobody is completing.
     */
    private suspend fun authenticate(
        channel: ControlChannel,
        store: PairingStore,
        keys: SessionKeys,
        transcript: ByteArray,
        greeting: Hello,
    ): Boolean {
        val remembered = if (greeting.paired) store.deviceKey(greeting.senderId) else null
        if (remembered != null) {
            // Exactly one message either way, so the phone never has to guess whether a code is
            // coming. `code = false` is what tells it to prove the key it holds.
            channel.send(PairRequired(code = false))
            val proof = channel.receive()?.message as? PairProof ?: return false
            val bytes = ProtocolBase64.decode(proof.proof) ?: return false
            if (pairingGate.verifyDevice(keys, transcript, remembered, bytes) is PairResult.Ok) {
                channel.send(PairOk())
                Log.i(TAG, "'${greeting.senderName}' authenticated with a remembered device key")
                return true
            }
            // Not the phone we remember. The honest cause is a reinstall, so fall through to the code
            // rather than refusing. Deliberately **no PairFailed here**: the PairRequired below is the
            // one reply to that proof, and sending both would leave a message in the phone's buffer
            // that its next read would mistake for the answer to its code.
            Log.i(TAG, "'${greeting.senderName}' failed its device proof; asking for a code")
        }

        pairingGate.reset()
        channel.send(PairRequired(code = true, attemptsLeft = pairingGate.attemptsLeft))
        _state.update {
            it.copy(
                phase = ReceiverPhase.Pairing(
                    senderName = greeting.senderName,
                    code = pairingGate.code,
                    attemptsLeft = pairingGate.attemptsLeft,
                ),
            )
        }
        while (true) {
            val proof = channel.receive()?.message as? PairProof ?: return false
            val bytes = ProtocolBase64.decode(proof.proof) ?: return false
            when (val result = pairingGate.verifyCode(keys, transcript, bytes)) {
                is PairResult.Ok -> {
                    val deviceKey = result.deviceKey ?: return false
                    store.remember(greeting.senderId, deviceKey)
                    channel.send(PairOk(deviceKey = ProtocolBase64.encode(deviceKey)))
                    Log.i(TAG, "'${greeting.senderName}' paired; it will connect silently from now on")
                    return true
                }
                is PairResult.Wrong -> {
                    channel.send(
                        PairFailed(
                            attemptsLeft = result.attemptsLeft,
                            codeChanged = result.codeChanged,
                        ),
                    )
                    _state.update {
                        it.copy(
                            phase = ReceiverPhase.Pairing(
                                senderName = greeting.senderName,
                                code = pairingGate.code,
                                attemptsLeft = result.attemptsLeft,
                                codeChanged = result.codeChanged,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- media ----

    private fun startStreaming(
        channel: ControlChannel,
        keys: SessionKeys,
        config: StreamConfig,
        senderName: String,
    ) {
        frameWidth = config.width
        frameHeight = config.height
        // The socket is bound first, because the port it lands on is what STREAM_READY has to name -
        // filling in a port we hoped to get and then binding is how a sender ends up talking to nothing.
        val socket = try {
            DatagramSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "could not bind a udp socket", e)
            channel.send(Bye(reason = "no udp socket"))
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.StreamEnded)) }
            return
        }
        val random = SecureRandom()
        val ready = StreamReady(
            udpPort = socket.localPort,
            audioSsrc = random.ssrc(StreamConstants.AUDIO_SSRC_MIN, StreamConstants.AUDIO_SSRC_MAX),
            videoSsrc = random.ssrc(StreamConstants.VIDEO_SSRC_MIN, StreamConstants.VIDEO_SSRC_MAX),
        )
        val negotiation = Negotiation.of(config, ready, keys)
        try {
            channel.send(ready)
        } catch (e: Exception) {
            // Nothing owns the socket yet, so a failure here would leak it for the life of the process.
            Log.w(TAG, "could not send STREAM_READY", e)
            runCatching { socket.close() }
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.StreamEnded)) }
            return
        }
        _state.update {
            it.copy(phase = ReceiverPhase.Mirroring(senderName, config.width, config.height))
        }
        Log.i(
            TAG,
            "receiving ${config.width}x${config.height} @ ${config.frameRate}fps " +
                "(${config.bitRate / 1_000_000.0} Mbit/s) on udp ${socket.localPort}",
        )
        mediaJob = scope.launch { pump(socket, negotiation, config) }
    }

    /**
     * The receive loop: datagrams in, feedback out, and one log line a second.
     *
     * **This coroutine owns the decoder and the audio track outright** - they are locals, not fields.
     * `MediaCodec` or `AudioTrack` released underneath a thread parked inside it is a native crash
     * rather than a catchable exception, so nothing outside this loop may touch them: [detachSurface]
     * only clears the surface reference, and the loop does the release on its next pass. [endMedia]
     * cancels *and joins* this job before anything else is dismantled.
     *
     * The decoder is started from inside the loop rather than before it because the surface arrives
     * asynchronously from `MirrorActivity`, and it is torn down and rebuilt the same way, which is what
     * makes a rotation mid-stream survivable.
     */
    private suspend fun pump(
        socket: DatagramSocket,
        negotiation: Negotiation,
        config: StreamConfig,
    ) {
        var decoder: VideoDecoder? = null
        var player: AudioPlayer? = null
        if (config.audio) {
            val started = AudioPlayer()
            player = if (started.start()) started else null
        }
        val media = MediaReceiver(
            socket = socket,
            negotiation = negotiation,
            onVideo = { frame ->
                decoder?.let {
                    it.decode(frame.payload, rtpToMicros(frame.rtpTimestamp), frame.isKeyFrame)
                    it.render()
                }
            },
            onAudio = { frame -> player?.play(frame.payload, audioRtpToMicros(frame.rtpTimestamp)) },
            videoReady = { decoder != null },
        )
        var lastFeedback = 0L
        var lastStatsLog = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val activeSurface = surface
                if (activeSurface == null) {
                    decoder?.release()
                    decoder = null
                } else if (decoder == null && negotiation.hasVideo) {
                    val started = VideoDecoder(activeSurface)
                    if (started.start(config.width, config.height)) {
                        decoder = started
                        Log.i(TAG, "decoder up; the picture starts at the next key frame")
                    } else {
                        _state.update {
                            it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.NoDecoder))
                        }
                        return
                    }
                }
                media.pump()
                val now = System.currentTimeMillis()
                if (now - lastFeedback >= FEEDBACK_INTERVAL_MS) {
                    lastFeedback = now
                    media.sendFeedback()
                }
                if (now - lastStatsLog >= STATS_LOG_INTERVAL_MS) {
                    lastStatsLog = now
                    // Paired with MirrorEngine's line on the phone. Two logs reporting at the same
                    // cadence from both ends is what five rounds of hardware debugging never had.
                    Log.i(TAG, media.throughputSummary())
                }
            }
        } finally {
            decoder?.release()
            player?.release()
            media.close()
        }
    }

    /**
     * Stop the media loop.
     *
     * The media job is **joined**, not just cancelled: it holds a `MediaCodec` and an `AudioTrack`, and
     * a second session starting while the first is still inside a call on either would release one out
     * from under the other. `runBlocking` is acceptable because the loop only ever parks for the
     * socket's 50 ms timeout.
     *
     * Deliberately does **not** touch `sessionJob`. That field is written only by [listen], which is
     * the one coroutine that decides whether a connection is accepted; a session's own `finally`
     * clearing it could otherwise land *after* [listen] had stored the next session's job, leaving the
     * field null and letting a third phone in alongside a running one.
     */
    private fun endMedia() {
        val media = mediaJob
        mediaJob = null
        if (media != null) {
            runCatching {
                runBlocking {
                    media.cancel()
                    media.join()
                }
            }
        }
        frameWidth = 0
        frameHeight = 0
    }

    /** Video RTP timestamps are 90 kHz; `MediaCodec` wants microseconds. */
    private fun rtpToMicros(rtpTimestamp: Long): Long =
        rtpTimestamp * 1_000_000L / StreamConstants.VIDEO_TIMEBASE

    /** Audio is timestamped in samples, so its divisor is the sample rate. */
    private fun audioRtpToMicros(rtpTimestamp: Long): Long =
        rtpTimestamp * 1_000_000L / StreamConstants.AUDIO_TIMEBASE

    private fun SecureRandom.ssrc(min: Int, max: Int): Long = (min + nextInt(max - min + 1)).toLong()

    /** What the TV calls itself, before the user renames it. */
    private fun defaultName(): String =
        listOf(Build.MODEL, Build.DEVICE)
            .firstOrNull { !it.isNullOrBlank() }
            ?: "MA Cast TV"
}
