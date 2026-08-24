package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PairResult
import com.vayunmathur.cast.protocol.PairingGate
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodecConfig
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.SecureRandom

private const val TAG = "ReceiverController"

/** How often the receiver's own throughput line goes out, paired with the sender's. */
private const val STATS_LOG_INTERVAL_MS = 1_000L

/** Feedback cadence. Well inside the target playout delay, so a NACK can still be played. */
private const val FEEDBACK_INTERVAL_MS = 50L

/**
 * The most often a decode failure may demand a key frame.
 *
 * Asking is not free: it discards every partial frame the assembler holds, so one request per refused
 * frame turns a burst of refusals into a stall it would otherwise have recovered from.
 */
private const val RESYNC_INTERVAL_MS = 500L

/**
 * How long the media loop waits for a codec configuration that has to arrive out of band.
 *
 * The wait itself is not new: `decoder == null` is already an unbounded state gated on the Surface,
 * and in practice this overlaps that wait entirely - the phone's encoder emits its configuration at
 * `start()`, long before `MirrorActivity` has produced anything to draw on. What is new is that it can
 * now fail, and it has to: a decoder that never starts is a black screen, and a black screen that
 * reports [ReceiverFailure.NoDecoder] would send the next hour of debugging to the wrong place.
 */
private const val CODEC_CONFIG_TIMEOUT_MS = 5_000L

/**
 * How much the receive socket may hold while the loop is busy decoding.
 *
 * An IDR at native resolution and ~24 Mbit/s is a few hundred packets arriving back to back once a
 * second, and the loop is single-threaded: it is inside `MediaCodec` when the burst lands. Nothing
 * set this before, so the default buffer overflowed and the loss looked like Wi-Fi. The kernel may
 * grant less than this, which is why it is read back and logged.
 */
private const val RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024

/** One press of the remote's volume key. Sixteen steps end to end, which is what a TV usually offers. */
private const val VOLUME_STEP = 1f / 16f

/**
 * How often a served session's playback goes to the phone even when nothing changed.
 *
 * Matches the cadence the phone used to report at, and for the same reason: every message is an
 * absolute snapshot, so this is the longest anything can be stale for, and the receiving end
 * interpolates position between them precisely so it does not need them faster.
 */
private const val REPORT_HEARTBEAT_MS = 500L

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

    /**
     * The player for a served content session, or null when there is not one.
     *
     * Volatile because the Activity's surface callbacks read it while the session coroutine writes
     * it. Its own field rather than a branch inside the media loop: a served session has no RTP, no
     * decoder and no playout queue, so there is nothing for that loop to do.
     */
    @Volatile
    private var contentPlayer: ContentPlayer? = null

    /** The frame size the phone said it would send, so the Activity can letterbox to it. */
    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    /**
     * The video codec configuration the phone sent, for a codec that cannot carry it in-band.
     *
     * Volatile because the control coroutine writes it while the media loop reads it, and cleared only
     * when a new session begins. **Deliberately not cleared when the decoder is released**: a surface
     * loss - a rotation - releases the decoder and rebuilds it, and a field cleared on the way past
     * would leave the replacement waiting for bytes that are only emitted once per encoder, wedging the
     * session for good.
     */
    @Volatile
    private var videoCodecConfig: ByteArray? = null

    /**
     * The output gain the phone last asked for, 0..1.
     *
     * A field rather than a call into [AudioPlayer], because that object belongs to the media loop
     * outright - see `pump`. The loop picks this up on its next pass, which is at most one socket
     * timeout away and imperceptible for a volume change.
     */
    @Volatile
    private var castVolume: Float = 1f

    private val pairingGate = PairingGate()

    /**
     * The live control channel, for sending back up it.
     *
     * The handshake reads and writes this channel from one coroutine, which is why it was never a
     * field before. A remote press is the first thing that originates on *this* end at an arbitrary
     * moment, so it needs a way in - [ControlChannel.send] is synchronised for the same reason.
     * Volatile because the UI thread reads it while the session coroutine writes it.
     */
    @Volatile
    private var channel: ControlChannel? = null

    /**
     * Do what the remote asked, wherever the player for it happens to be.
     *
     * **A served session applies the press here**, because that is where the player is: the phone
     * paused its own before the cast started, so a round trip to it would move nothing and take a
     * LAN's latency doing it. The phone finds out from the next snapshot, half a second later at
     * worst. Screen mirroring is the other way round - the phone's player is the one making the
     * sound - so the command goes to it, unchanged.
     *
     * [PlaybackAction.Next] and [PlaybackAction.Previous] always go to the phone, whichever kind of
     * session it is: the queue is a list only the phone can see. Dropped silently with no session,
     * which is the honest answer - the remote was pressed at a screen with nothing playing on it.
     */
    fun send(command: PlaybackCommand) {
        val served = contentPlayer
        // On the main thread already, which is where ExoPlayer must be touched: every caller is
        // `MirrorActivity`'s key handling.
        if (served != null && served.apply(command)) {
            // A press has to be visible before the reporter's next tick, or the overlay lags the
            // sound coming out of this very box.
            onPlaybackState(served.snapshot())
            return
        }
        val live = channel ?: return
        scope.launch {
            runCatching { live.send(command) }
                .onFailure { Log.w(TAG, "could not send ${command.action}", it) }
        }
    }

    /** Skip forward or back by whichever interval the end holding the player uses. */
    fun skip(forward: Boolean) {
        send(PlaybackCommand(if (forward) PlaybackAction.SkipForward else PlaybackAction.SkipBack))
    }

    /**
     * Nudge the shared volume level up or down by one step.
     *
     * The level is read from the last snapshot rather than held separately, so there is one answer to
     * "how loud is it" however the session is arranged. [send] then puts the new level wherever the
     * player is: on this box's own player for a served session, or on the phone for screen mirroring,
     * where the phone owns the level, stores it, and keeps it for local playback afterwards.
     *
     * Returns false with no session, so the key falls through to the box's own volume control.
     */
    fun nudgeVolume(up: Boolean): Boolean {
        val current = _state.value.playback?.state?.volume ?: return false
        val level = (current + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0f, 1f)
        send(PlaybackCommand(PlaybackAction.SetVolume, value = level.toDouble()))
        return true
    }

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
        forgetPlayback()
        _state.update { it.copy(phase = ReceiverPhase.Starting) }
    }

    /**
     * Drop everything the last session said about playback.
     *
     * Both halves together, always: clearing [ReceiverUiState.playback] is what takes the overlay away,
     * and resetting [castVolume] is what stops the *next* session inheriting this one's gain. Missing
     * the second is a quiet, nasty failure - a screen-mirroring session that follows a quiet cast would
     * play at that gain for ever, with no snapshot to change it and `nudgeVolume` declining to try.
     *
     * The metadata goes with them, and has to: it is not merely stale but wrong, and a cover left on
     * screen over the next session's audio would look deliberate.
     */
    private fun forgetPlayback() {
        castVolume = 1f
        _state.update {
            it.copy(
                playback = null,
                nowPlaying = null,
                playingResourceId = "",
                artwork = null,
            )
        }
    }

    /** Called by `MirrorActivity` once its `SurfaceView` has a surface to draw into. */
    fun attachSurface(newSurface: Surface) {
        surface = newSurface
        // A served session hands the surface straight to its player. Nothing is being decoded here,
        // so there is no media loop to notice one appearing.
        contentPlayer?.setSurface(newSurface)
    }

    /**
     * The surface is going away - rotation, or the Activity finishing.
     *
     * Only the reference is cleared here. The media loop owns the decoder and releases it when it
     * next sees that there is nowhere to draw, so nothing is ever released underneath a `MediaCodec`
     * call in flight.
     */
    fun detachSurface() {
        // Taken off the player first: handing a released surface to ExoPlayer is what breaks the
        // *next* session rather than this one.
        contentPlayer?.setSurface(null)
        surface = null
    }

    // ---- accepting ----

    private suspend fun listen(context: Context) {
        val store = PairingStore(context)
        val identity = PqcIdentity.loadOrCreate(store, prefix = "castTv")
        val deviceId = store.deviceId()
        val name = store.friendlyName(fallback = defaultName())
        // Audio is advertised alongside video for the first time. It never needed negotiating while
        // every session had a picture - a TV with no Opus decoder simply played silence - but an
        // audio-only session stands or falls on it, and the phone can only refuse by name if it was
        // told.
        val limits = VideoDecoder.limits().copy(audioCodecs = AudioPlayer.limits())

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
            // now is what the user cares about. The previous session's playback goes with it, or the
            // next phone would inherit a seek bar describing something that is no longer playing.
            forgetPlayback()
            _state.update {
                if (it.phase is ReceiverPhase.Failed) {
                    it.copy(phase = ReceiverPhase.Advertising)
                } else {
                    it
                }
            }
            this@ReceiverController.channel = channel
            sessionJob = scope.launch {
                try {
                    runSession(context, channel, store, identity, deviceId, name, limits)
                } catch (e: Exception) {
                    Log.w(TAG, "session ended", e)
                } finally {
                    channel.close()
                    this@ReceiverController.channel = null
                    endMedia()
                    forgetPlayback()
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
        context: Context,
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

        // **A loop rather than one configuration, because a content session gives the channel back.**
        // Ending a cast is far more common than disconnecting a television, and the pairing is exactly
        // what the user just spent time on - so `CONTENT_ENDED` returns here and the next cast starts
        // without re-picking the TV. Screen mirroring never comes back: its session lives in the UDP
        // loop until the socket dies.
        while (true) {
            val configured = channel.receive() ?: return
            // The fork between the two kinds of session. Screen mirroring has no file behind it and
            // keeps the RTP path; anything with a file is served over HTTPS and decoded here, which is
            // why seeking becomes an offset and a pause is nobody's business but this end's.
            when (val first = configured.message) {
                is StreamConfig -> {
                    startStreaming(channel, keys, first, greeting.senderName)
                    mirrorSession(channel, first, greeting.senderName)
                    return
                }
                is ContentSession -> {
                    if (!serveContent(context, channel, first, greeting.senderName)) return
                    _state.update { it.copy(phase = ReceiverPhase.Connected(greeting.senderName)) }
                    forgetPlayback()
                }
                is Bye -> {
                    Log.i(TAG, "'${greeting.senderName}' said goodbye")
                    return
                }
                // Echoed rather than treated as a surprise, so a keep-alive cannot end the very channel
                // it exists to preserve. A snapshot, a command or a metadata update still in flight
                // from the session that just ended is dropped for the same reason: there is no longer
                // a player for the first two, and nothing on screen for the third. Metadata is the
                // likeliest of the three to land here, because the phone prepares it off the path that
                // ends the session - a cover read and re-compressed while the user closed the cast.
                is Ping -> runCatching { channel.send(Ping) }
                is PlaybackState, is PlaybackCommand, is NowPlaying -> Unit
                else -> return
            }
        }
    }

    /**
     * The rest of a screen-mirroring session: everything that arrives while the UDP loop runs.
     *
     * The session now lives in that loop; this coroutine stays here so a BYE or a dropped socket tears
     * everything down through the same path. It is also where the codec configuration arrives, which
     * for AV1 is what the media loop is waiting on before it can start a decoder at all - it is
     * repeated on every key-frame request, so this handles it more than once. And it is where playback
     * snapshots arrive, twice a second, for as long as an app is casting encoded content - as does the
     * metadata that says what that content *is*, which an encoded video needs quite as much as a
     * served track did.
     */
    private suspend fun mirrorSession(
        channel: ControlChannel,
        config: StreamConfig,
        senderName: String,
    ) {
        while (true) {
            val next = channel.receive() ?: break
            when (val message = next.message) {
                is Bye -> {
                    Log.i(TAG, "'$senderName' said goodbye")
                    return
                }
                is VideoCodecConfig -> onVideoCodecConfig(message, config)
                is PlaybackState -> onPlaybackState(message)
                // What the encoded picture is of. Names no resource - nothing is being served here -
                // so the gate in `ReceiverUiState.nowPlayingForCurrentItem` passes it straight
                // through: this end holds no player, so the phone's latest word is the only word.
                is NowPlaying -> _state.update { it.copy(nowPlaying = message) }
                // As in the content-session loop below: echoed so the phone's read deadline moves
                // too. Screen mirroring never sends one, so this only fires for app content.
                is Ping -> runCatching { channel.send(Ping) }
                else -> Unit
            }
        }
    }

    /**
     * Serve a content session: the phone has the bytes, this end has the player.
     *
     * The whole of the new arrangement on this side. Nothing is decoded from RTP, nothing waits for a
     * key frame, and there is no picture-loss indicator to send because there are no lost pictures.
     * What is left is a URL, a pinned certificate and a player that owns its own clock.
     *
     * **And because it owns the clock, it owns the truth.** A reporting coroutine puts this player's
     * state on the channel - immediately when something changes, and otherwise at
     * [REPORT_HEARTBEAT_MS] - which is what lets every surface on the phone show what is actually
     * playing. `PLAYBACK_COMMAND` arrives here rather than leaving, for the same reason.
     *
     * ExoPlayer must be built and driven from the thread whose looper it took, so every call into it
     * hops to the main thread. The control channel stays on this coroutine, because it is the same
     * blocking read it always was.
     *
     * Returns true when the phone ended the *session* and wants to keep the connection, so the caller
     * can go back to waiting for the next configuration.
     */
    private suspend fun serveContent(
        context: Context,
        channel: ControlChannel,
        session: ContentSession,
        senderName: String,
    ): Boolean {
        if (!session.video && AudioPlayer.limits().isEmpty()) {
            // The failure that used to be silence. A TV with no Opus decoder and no picture to fall
            // back on has to say so, and the phone has to be told rather than left streaming into it.
            Log.w(TAG, "refusing an audio-only session: this TV has no Opus decoder")
            channel.send(ContentReady(accepted = false, detail = "this TV has no Opus decoder"))
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.NoAudioDecoder)) }
            return false
        }

        val player = withContext(Dispatchers.Main) { ContentPlayer(context, session) }
        val started = withContext(Dispatchers.Main) {
            player.start { detail -> Log.w(TAG, "the served stream failed: $detail") }
        }
        if (!started) {
            channel.send(ContentReady(accepted = false, detail = "the player could not be built"))
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            withContext(NonCancellable + Dispatchers.Main) { player.release() }
            return false
        }

        contentPlayer = player
        // A surface may already exist from a previous session's Activity; an audio-only session wants
        // none, and handing it one would put a black rectangle over the now-playing screen.
        if (session.video) surface?.let { withContext(Dispatchers.Main) { player.setSurface(it) } }
        channel.send(ContentReady(accepted = true))
        // Built outside the update, because `update` may retry its lambda under contention and
        // allocating a fetcher per attempt would be one object per lost race.
        val artworkFetcher = ArtworkFetcher(session)
        _state.update {
            it.copy(
                phase = ReceiverPhase.Mirroring(
                    senderName = senderName,
                    // No frame size to letterbox to: the TV plays the media at its own size, which is
                    // the point of not squeezing it through an encoder first.
                    width = 0,
                    height = 0,
                    appLabel = session.appLabel,
                    hasVideo = session.video,
                ),
                // In the state rather than a field of this object, because the now-playing screen
                // reads it during composition and a plain field is not something composition
                // observes - the same trap the `overlayPinned` comment in `MirrorActivity` names.
                artwork = artworkFetcher,
            )
        }
        Log.i(TAG, "serving content from ${session.host}:${session.port} for '${session.appLabel}'")

        // A third writer on this channel, alongside the ping echo and this coroutine's own sends.
        // Nothing guards it here because nothing needs to: `ControlChannel.send` encodes and writes
        // under one lock, so a frame cannot interleave with another and the cipher's nonce cannot
        // advance twice for one message. What a mutex would add is ordering between *sequences*, and
        // there are none here - every send is a single message.
        val reporting = scope.launch { report(player, channel) }
        try {
            while (true) {
                val next = channel.receive() ?: return false
                when (val message = next.message) {
                    is Bye -> {
                        Log.i(TAG, "'$senderName' said goodbye")
                        return false
                    }
                    // The phone is done casting but not done with us. Distinct from a `Bye`, and the
                    // difference is the pairing: this leaves the TV connected and ready for the next
                    // cast rather than back at its idle screen waiting to be picked again.
                    is ContentEnded -> {
                        Log.i(TAG, "'$senderName' ended the content session")
                        return true
                    }
                    is PlayMedia -> {
                        withContext(Dispatchers.Main) { player.play(message) }
                        // Published rather than only held on the player, because it is half of the
                        // comparison `nowPlayingForCurrentItem` makes and the UI has to recompose on it.
                        _state.update { it.copy(playingResourceId = message.resourceId) }
                    }
                    // What the item *is*, as opposed to which bytes it is. Stored whatever it names:
                    // the gate is at read time, so a snapshot arriving before or after the play it
                    // describes both work, and one for a track already skipped past is simply never
                    // shown. See `ReceiverUiState.nowPlayingForCurrentItem`.
                    is NowPlaying -> _state.update { it.copy(nowPlaying = message) }
                    // The phone's transport, wherever it was pressed - its own screen, a notification,
                    // a headset button, a car. Applied to the player that is actually making the
                    // sound. `Next` and `Previous` are refused by the player and arrive as a fresh
                    // `PLAY_MEDIA` instead, because only the phone can see the queue.
                    is PlaybackCommand -> withContext(Dispatchers.Main) { player.apply(message) }
                    // Echoed straight back, which is the whole of the keep-alive. Reading it has
                    // already pushed this end's deadline out; replying is what pushes the phone's,
                    // since a read timeout is not reset by anything that end sends.
                    is Ping -> runCatching { channel.send(Ping) }
                    else -> Unit
                }
            }
        } finally {
            // Cancelled **and joined**, under NonCancellable because this runs on the teardown path a
            // cancelled session takes. A publish already past its last suspension point would
            // otherwise land after the caller has cleared the overlay, putting a stale 0:00 snapshot
            // back on the idle screen and making `nudgeVolume` answer for a session that has gone.
            withContext(NonCancellable) { reporting.cancelAndJoin() }
            contentPlayer = null
            // NonCancellable because this is the teardown path a cancelled session takes, and an
            // ExoPlayer left unreleased holds a codec the next session will ask for.
            withContext(NonCancellable + Dispatchers.Main) { player.release() }
        }
    }

    /**
     * Keep the phone's copy of this player current, for as long as the session lasts.
     *
     * Two cadences, because they answer different needs. Anything the phone *renders* differently goes
     * out the instant it changes - a pause that took half a second to reach a notification reads as a
     * dropped button press. Position is deliberately excluded from that test: it moves constantly, so
     * "changed" would mean "always", and the phone interpolates between snapshots precisely so it does
     * not need them faster.
     *
     * The heartbeat underneath is what makes the whole thing self-repairing: every message is an
     * absolute snapshot, so a lost one costs at most one interval of staleness and needs no
     * acknowledgement, no sequence number and no retry.
     *
     * Each snapshot is also published locally, because this box draws its own overlay from the same
     * numbers - it is the source of them now, so there is nothing else to draw from.
     */
    private suspend fun report(player: ContentPlayer, channel: ControlChannel) {
        // Built on the main thread, because every getter behind `snapshot` asserts the player's own
        // looper - reading them from this coroutine would throw rather than report a stale number.
        val latest = withContext(Dispatchers.Main) {
            MutableStateFlow(player.snapshot()).also { flow ->
                player.onChanged = { flow.value = player.snapshot() }
            }
        }
        try {
            coroutineScope {
                launch {
                    latest
                        .map { it.copy(positionMs = 0) }
                        .distinctUntilChanged()
                        .collect { publish(latest.value, channel) }
                }
                while (isActive) {
                    delay(REPORT_HEARTBEAT_MS)
                    latest.value = withContext(Dispatchers.Main) { player.snapshot() }
                    publish(latest.value, channel)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { player.onChanged = null }
        }
    }

    /** One snapshot, to this box's own overlay and to the phone. */
    private fun publish(snapshot: PlaybackState, channel: ControlChannel) {
        onPlaybackState(snapshot)
        runCatching { channel.send(snapshot) }
            .onFailure { Log.w(TAG, "could not report playback", it) }
    }

    /**
     * Take a playback snapshot, and stamp it with the moment it became true.
     *
     * The timestamp is taken here rather than in the UI because this is the closest thing to "when it
     * was so" that exists - anything later would fold recomposition delay into the seek bar's anchor.
     *
     * Fed from the phone for screen mirroring and from this box's own player for a served session,
     * which is the whole of the direction change: the overlay is drawn from whichever end owns the
     * clock, through one field either way.
     *
     * Not logged. Two of these a second would drown the once-a-second throughput line that everything
     * else about a session is diagnosed from.
     */
    private fun onPlaybackState(message: PlaybackState) {
        castVolume = message.volume
        _state.update {
            it.copy(
                playback = PlaybackSnapshot(
                    state = message,
                    receivedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Take the codec configuration the phone sent, and say enough about it to diagnose it from a log.
     *
     * **The length and the leading bytes are the whole point of this log line.** Whether AV1's
     * `BUFFER_FLAG_CODEC_CONFIG` is an `av1C` configuration record or a bare sequence-header OBU is a
     * device fact, not an API one, and it decides whether `csd-0` was the right place to put it - so it
     * has to be readable from the one hardware session where it can be answered. `0x81` leads an `av1C`
     * record (marker 1, version 1); `0x0a` leads a sequence header OBU with a size field.
     */
    private fun onVideoCodecConfig(message: VideoCodecConfig, config: StreamConfig) {
        val codec = config.videoCodec
        if (codec == null) {
            // An audio-only session has no decoder to configure. Ignored rather than treated as a
            // fault: it means the phone sent one anyway, which is harmless and says nothing useful.
            Log.w(TAG, "a codec config arrived for a session with no video")
            return
        }
        val csd = ProtocolBase64.decode(message.csd)
        if (csd == null || csd.isEmpty()) {
            Log.w(TAG, "the phone sent an unreadable codec config")
            return
        }
        val shape = when (csd.first()) {
            0x81.toByte() -> "an av1C configuration record"
            0x0a.toByte() -> "a sequence header OBU"
            else -> "an unrecognised form"
        }
        Log.i(
            TAG,
            "codec config for ${codec.label}: ${csd.size} bytes, $shape, " +
                "[${csd.take(16).joinToString(" ") { "%02x".format(it) }}]; " +
                "the stream is ${config.width}x${config.height}",
        )
        videoCodecConfig = csd
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
        // A new session's codec configuration has not arrived yet, and the previous session's would be
        // for a different encoder. This is the *only* place it is cleared - see the field's own note on
        // why a decoder release must not.
        videoCodecConfig = null
        // The socket is bound first, because the port it lands on is what STREAM_READY has to name -
        // filling in a port we hoped to get and then binding is how a sender ends up talking to nothing.
        val socket = try {
            DatagramSocket(0).apply {
                // Best effort: the kernel clamps to its own maximum and reports what it gave, so the
                // request is made and then the result is logged rather than assumed.
                runCatching { receiveBufferSize = RECEIVE_BUFFER_BYTES }
            }
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
            it.copy(
                phase = ReceiverPhase.Mirroring(
                    senderName = senderName,
                    width = config.width,
                    height = config.height,
                    appLabel = config.appLabel,
                ),
            )
        }
        Log.i(
            TAG,
            "receiving ${config.videoCodec?.label ?: "audio only"} ${config.width}x${config.height} @ " +
                "${config.frameRate}fps (${config.bitRate / 1_000_000.0} Mbit/s) on udp " +
                "${socket.localPort}, " +
                "rcvbuf=${runCatching { socket.receiveBufferSize }.getOrDefault(0)}B, " +
                "playout=${StreamConstants.TARGET_DELAY_MS}ms" +
                if (config.videoCodec?.needsCodecConfig == true) "; waiting for its codec config" else "",
        )
        mediaJob = scope.launch { pump(socket, negotiation, config, channel) }
    }

    /**
     * The receive loop: datagrams in, frames out at their scheduled time, feedback out, and one log
     * line a second.
     *
     * **This coroutine owns the decoder and the audio track outright** - they are locals, not fields.
     * `MediaCodec` or `AudioTrack` released underneath a thread parked inside it is a native crash
     * rather than a catchable exception, so nothing outside this loop may touch them: [detachSurface]
     * only clears the surface reference, and the loop does the release on its next pass. [endMedia]
     * cancels *and joins* this job before anything else is dismantled.
     *
     * Decode stays **on this thread**, even though a playout queue now separates arrival from
     * presentation. A second thread would buy nothing - the queue is what decouples the two - and
     * would reintroduce exactly the native-crash surface the paragraph above exists to avoid.
     *
     * The decoder is started from inside the loop rather than before it because the surface arrives
     * asynchronously from `MirrorActivity`, and it is torn down and rebuilt the same way, which is what
     * makes a rotation mid-stream survivable.
     */
    private suspend fun pump(
        socket: DatagramSocket,
        negotiation: Negotiation,
        config: StreamConfig,
        channel: ControlChannel,
    ) {
        var decoder: VideoDecoder? = null
        // Hoisted into a local because the property comes from another module, where Kotlin will not
        // smart-cast it - and every use below is inside a branch that has already established there
        // is video.
        val videoCodec = config.videoCodec
        val player = if (config.audio) AudioPlayer().takeIf { it.start() } else null
        val playout = PlayoutQueue(
            targetDelayMs = StreamConstants.TARGET_DELAY_MS.toLong(),
            timebase = StreamConstants.VIDEO_TIMEBASE,
        )
        // Audio is held for the same interval as video, or the buffer below would put the sound
        // 150 ms ahead of the picture - which is worse than the judder it exists to remove.
        val audioPlayout = PlayoutQueue(
            targetDelayMs = StreamConstants.TARGET_DELAY_MS.toLong(),
            timebase = StreamConstants.AUDIO_TIMEBASE,
        )
        val media = MediaReceiver(
            socket = socket,
            negotiation = negotiation,
            // Queued, not decoded: the loop below decides when this frame is due.
            onVideo = { frame -> playout.add(frame, System.currentTimeMillis()) },
            // Not queued when there is no player to drain it into, or the queue would only grow.
            onAudio = { frame ->
                if (player != null) audioPlayout.add(frame, System.currentTimeMillis())
            },
            videoReady = { decoder != null },
        )
        var lastFeedback = 0L
        var lastStatsLog = 0L
        var lastResync = 0L
        // The gain currently on the track, so it is written only when the phone actually changes it.
        var appliedVolume = Float.NaN
        // When the wait for a codec configuration began, or 0 while nothing is waiting. Started from
        // the moment the decoder *could* otherwise have been built, so it does not run down while the
        // Activity is still producing a surface.
        var codecConfigWaitStartedAt = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val activeSurface = surface
                if (activeSurface == null) {
                    val stale = decoder
                    if (stale != null) {
                        stale.release()
                        decoder = null
                        // Whatever is queued was scheduled for a decoder that no longer exists, and
                        // its replacement can decode nothing until it has been given a key frame.
                        playout.clear()
                        media.requestKeyFrame(StreamKind.Video)
                    }
                } else if (decoder == null && negotiation.hasVideo && videoCodec != null) {
                    // Only consulted for a codec that needs it. An H.265 session finds its parameter
                    // sets in the stream, and installing a `csd-0` it was not expecting would fail the
                    // configure - so the field is read through the codec's own contract rather than
                    // passed on because it happened to be set.
                    val codecConfig = videoCodecConfig.takeIf { videoCodec.needsCodecConfig }
                    if (videoCodec.needsCodecConfig && codecConfig == null) {
                        // **The one genuinely new wait.** This widens the entry condition of a state
                        // the pipeline already survives - `decoder == null`, which `MediaReceiver`'s
                        // pre-session video drop, `ReceiverSession.synchronised` and PLI already make
                        // recoverable - rather than adding a new one.
                        val waiting = System.currentTimeMillis()
                        if (codecConfigWaitStartedAt == 0L) {
                            codecConfigWaitStartedAt = waiting
                            Log.i(
                                TAG,
                                "surface ready; waiting for ${videoCodec.label}'s codec config",
                            )
                        } else if (waiting - codecConfigWaitStartedAt >= CODEC_CONFIG_TIMEOUT_MS) {
                            Log.w(
                                TAG,
                                "no ${videoCodec.label} codec config after " +
                                    "${CODEC_CONFIG_TIMEOUT_MS}ms; nothing can be decoded",
                            )
                            // Named so the phone can remember it and start the next session on the
                            // other codec - a black screen it could not attribute would just repeat.
                            runCatching {
                                channel.send(Bye(reason = ByeReason.MISSING_CODEC_CONFIG))
                            }
                            _state.update {
                                it.copy(
                                    phase = ReceiverPhase.Failed(
                                        ReceiverFailure.MissingCodecConfig,
                                    ),
                                )
                            }
                            return
                        }
                    } else {
                        val started = VideoDecoder(activeSurface, videoCodec)
                        if (started.start(config.width, config.height, codecConfig)) {
                            decoder = started
                            Log.i(TAG, "decoder up; the picture starts at the next key frame")
                        } else {
                            _state.update {
                                it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.NoDecoder))
                            }
                            return
                        }
                    }
                }
                media.pump()
                val now = System.currentTimeMillis()
                val active = decoder
                if (active != null) {
                    while (true) {
                        val frame = playout.due(now) ?: break
                        val queued = active.decode(
                            frame.payload,
                            rtpToMicros(frame.rtpTimestamp),
                            frame.isKeyFrame,
                        )
                        if (!queued && now - lastResync >= RESYNC_INTERVAL_MS && !senderIdle()) {
                            // The session counted this frame as delivered, so its checkpoint has
                            // moved past a frame the decoder never got: every delta frame behind it
                            // now references something that does not exist. Only a key frame
                            // recovers, and nothing else in the pipeline can know to ask for one.
                            //
                            // **Rate-limited, because asking costs the assembler's partial frames.**
                            // A decoder whose input buffers are briefly full refuses several frames
                            // in a row - measured at 55 in the first seconds of a session - and one
                            // resync per refusal wipes the very packets retransmission was about to
                            // repair, turning a hiccup into a stall.
                            lastResync = now
                            media.requestKeyFrame(StreamKind.Video)
                        }
                    }
                    active.render()
                }
                if (player != null) {
                    if (castVolume != appliedVolume) {
                        appliedVolume = castVolume
                        player.setVolume(appliedVolume)
                    }
                    while (true) {
                        val frame = audioPlayout.due(now) ?: break
                        player.play(frame.payload, audioRtpToMicros(frame.rtpTimestamp))
                    }
                }
                if (now - lastFeedback >= FEEDBACK_INTERVAL_MS) {
                    lastFeedback = now
                    media.sendFeedback(senderIdle = senderIdle())
                }
                if (now - lastStatsLog >= STATS_LOG_INTERVAL_MS) {
                    lastStatsLog = now
                    // Paired with MirrorEngine's line on the phone. Two logs reporting at the same
                    // cadence from both ends is what five rounds of hardware debugging never had.
                    Log.i(
                        TAG,
                        media.throughputSummary() +
                            " playout=${playout.depth}f/rebased=${playout.rebases}" +
                            " dropped=${active?.framesDropped ?: 0}" +
                            audioHealth(player),
                    )
                }
            }
        } finally {
            decoder?.release()
            player?.release()
            media.close()
        }
    }

    /**
     * Whether the phone has told us it is deliberately not producing frames.
     *
     * The one thing that distinguishes "paused" from "broken", and the pipeline had no way to know it
     * until the phone started reporting playback: a paused sender and a dead link look identical from
     * here - no frames arriving - and every recovery mechanism is built for the second. False for screen
     * mirroring, which never reports playback and can never be paused, so its behaviour is unchanged.
     */
    private fun senderIdle(): Boolean =
        _state.value.playback?.state?.let { !it.playing && !it.buffering } == true

    /**
     * Stop the media loop.
     *
     * The media job is **joined**, not just cancelled: it holds a `MediaCodec` and an `AudioTrack`, and
     * a second session starting while the first is still inside a call on either would release one out
     * from under the other. `runBlocking` is acceptable because the loop only ever parks for the
     * socket's 10 ms timeout.
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

    /**
     * What the audio path is doing, in the one line that reports everything else.
     *
     * A rebuild is worth saying out loud even though it recovered: silence that came back is a fault
     * that will happen again, and a session that reports nothing looks identical to one that never had
     * a problem.
     */
    private fun audioHealth(player: AudioPlayer?): String = when {
        player == null -> ""
        player.failed -> " audio=failed after ${player.restarts} rebuilds"
        player.restarts > 0 -> " audio=recovered/${player.restarts} rebuilds"
        else -> ""
    }

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

/**
 * Encoded frames waiting for their turn on screen, or in the speakers.
 *
 * The receiver advertised a target playout delay in every RTCP report and implemented none of it:
 * each frame was rendered the moment it arrived, so Wi-Fi jitter mapped one-to-one onto visible
 * judder and the NACK machinery had no window to repair into. This is that window.
 *
 * Scheduling is by **RTP-timestamp delta from the first frame**, which is what makes playout follow
 * capture spacing rather than arrival spacing. Encoded frames are kilobytes, so the ~5 frames a
 * 150 ms buffer holds at 30 fps cost nothing to keep.
 *
 * [timebase] is the stream's own, because audio is timestamped in samples and video at 90 kHz - both
 * halves have to be held for the same *wall-clock* interval or the sound leads the picture.
 *
 * Not thread-safe, and does not need to be: it is filled from `MediaReceiver.pump`'s callback and
 * drained by the loop that calls it, both on the one media coroutine.
 */
private class PlayoutQueue(private val targetDelayMs: Long, private val timebase: Int) {

    private val frames = ArrayDeque<DecodableFrame>()

    /** The sender's RTP clock mapped onto ours, established by the first frame after each reset. */
    private var baseRtp = -1L
    private var baseWallMs = 0L

    /** Times the mapping had to be re-established, each of which resets the buffer's depth. */
    var rebases: Long = 0
        private set

    val depth: Int get() = frames.size

    fun add(frame: DecodableFrame, nowMs: Long) {
        if (baseRtp < 0) rebase(frame.rtpTimestamp, nowMs)
        frames.addLast(frame)
        // **Corrected in both directions.** Each frame should land [targetDelayMs] after it arrives;
        // how far from that it actually lands is how far the mapping has drifted. Correcting only
        // lateness lets the buffer deepen without limit - measured at 7-8 frames, some 600 ms of
        // latency, on a mapping established during a slow patch and never revisited. Re-basing on the
        // *newest* frame keeps the deque ordered for free: playout time is monotonic in the RTP
        // timestamp, so pulling the schedule in makes older frames due sooner, never out of order.
        val drift = scheduleFor(frame.rtpTimestamp) - (nowMs + targetDelayMs)
        if (drift > targetDelayMs || drift < -targetDelayMs) {
            rebases++
            rebase(frame.rtpTimestamp, nowMs)
        }
    }

    /** The next frame whose time has come, or null while the buffer is still filling. */
    fun due(nowMs: Long): DecodableFrame? {
        val head = frames.firstOrNull() ?: return null
        if (scheduleFor(head.rtpTimestamp) > nowMs) return null
        return frames.removeFirst()
    }

    /** Dropped rather than played out: the decoder these were scheduled for has gone. */
    fun clear() {
        frames.clear()
        baseRtp = -1
    }

    private fun rebase(rtpTimestamp: Long, nowMs: Long) {
        baseRtp = rtpTimestamp
        baseWallMs = nowMs + targetDelayMs
    }

    private fun scheduleFor(rtpTimestamp: Long): Long =
        baseWallMs + (rtpTimestamp - baseRtp) * 1_000L / timebase
}
