package com.vayunmathur.cast.platform

import android.os.Build
import android.util.Log
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.protocol.VideoCodecConfig
import java.security.SecureRandom

private const val TAG = "MirrorClient"

/** Whether the TV took a content session, and why not if it did not. */
sealed interface ContentOutcome {

    data object Accepted : ContentOutcome

    /**
     * [detail] is the TV's own words, so the phone can say something true rather than "failed".
     *
     * The refusal that matters is a TV with no Opus decoder on an audio-only session: before it was
     * negotiated, that TV accepted the session and played silence, which is the failure this whole
     * reply exists to make visible.
     */
    data class Refused(val detail: String) : ContentOutcome
}

/** What a handshake step produced, or why it stopped. */
sealed interface HandshakeOutcome {

    /** Trusted. [deviceKey] is set only when this was a code pairing, and is what to persist. */
    data class Paired(val deviceKey: ByteArray?) : HandshakeOutcome {
        override fun equals(other: Any?): Boolean =
            other is Paired && deviceKey.contentEquals(other.deviceKey)

        override fun hashCode(): Int = deviceKey?.contentHashCode() ?: 0
    }

    /**
     * The TV is showing six digits.
     *
     * [codeChanged] means the previous three attempts were used up and the code on screen is a new
     * one, so the user has to look again rather than retype what they had.
     */
    data class NeedsCode(val attemptsLeft: Int, val codeChanged: Boolean) : HandshakeOutcome

    /** Configured. The media pipeline is built from [negotiation]. */
    data class Ready(val negotiation: Negotiation) : HandshakeOutcome

    data class Failed(val reason: ClientFailure) : HandshakeOutcome
}

/**
 * The phone's half of the MA Cast handshake: what `CastSession` used to be, minus a control plane that
 * was never ours to define.
 *
 * Driven step by step by [CastController] rather than running itself, for one reason: pairing needs six
 * digits from a human, so the sequence genuinely has to stop and wait for the UI. Everything either
 * side of that is a straight-line exchange.
 *
 * Every method blocks on the socket, so all of them must be called from an IO context. That is a
 * deliberate trade against `CastSession`'s purity: the *rules* worth unit-testing - the key schedule,
 * the proofs, the attempt limit, the route construction - all live in `:cast:protocol` and are tested
 * there. What is left here is sequencing, which is only meaningfully tested against a real socket.
 */
class MirrorClient(
    private val socket: ControlSocket,
    private val senderId: String,
    private val storedDeviceKey: ByteArray?,
    private val senderName: String = deviceName(),
) {

    private val transcript = Transcript()

    private var keys: SessionKeys? = null

    /**
     * The transcript, frozen after `SEALED_SECRET`.
     *
     * Frozen at exactly that point because it has to cover the TV's public bundle: that is what makes
     * a pair proof unforgeable by a man in the middle, which substituted a bundle of its own.
     */
    private var transcriptValue: ByteArray? = null

    var receiverName: String? = null
        private set

    var receiverId: String? = null
        private set

    /** The TV's real decoder limits, one envelope per codec, which the frame size is chosen against. */
    var limits: DecoderLimits? = null
        private set

    /**
     * `HELLO` → `TV_IDENTITY` → `SEALED_SECRET` → `PAIR_REQUIRED`, then the device proof if we hold a
     * key the TV accepts.
     *
     * Returns [HandshakeOutcome.NeedsCode] when the user has to type something, and
     * [HandshakeOutcome.Paired] when a remembered device key was enough.
     */
    fun begin(): HandshakeOutcome {
        transcript.add(
            socket.send(
                Hello(
                    senderName = senderName,
                    senderId = senderId,
                    paired = storedDeviceKey != null,
                ),
            ),
        )

        val identityFrame = socket.receive()
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        val identity = identityFrame.message as? TvIdentity
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        if (identity.version != PROTOCOL_VERSION) {
            Log.w(TAG, "the TV speaks version ${identity.version}; we speak $PROTOCOL_VERSION")
            return HandshakeOutcome.Failed(ClientFailure.VersionMismatch)
        }
        transcript.add(identityFrame.body)
        receiverName = identity.receiverName
        receiverId = identity.receiverId
        limits = identity.limits
        if (identity.limits.videoCodecs.isEmpty()) {
            // Not a failure here - CastController names the codecs it checked - but the log is where
            // it would be diagnosed, and "the TV advertised nothing" is invisible otherwise.
            Log.w(TAG, "'${identity.receiverName}' advertised no hardware video decoder at all")
        }
        for (codec in identity.limits.videoCodecs) {
            Log.i(
                TAG,
                "'${identity.receiverName}' decodes ${codec.codec.label} up to " +
                    "${codec.maxWidth}x${codec.maxHeight} @ ${codec.maxFrameRate}fps, " +
                    "${codec.maxBitRate / 1_000_000.0} Mbit/s",
            )
        }

        val bundle = ProtocolBase64.decode(identity.publicBundle)
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        val secret = SessionKeys.newSecret()
        val sealed = runCatching { SecretSealing.seal(bundle, secret) }.getOrNull()
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        transcript.add(socket.send(SealedSecret(sealed = ProtocolBase64.encode(sealed))))

        val sessionKeys = SessionKeys.of(secret)
        keys = sessionKeys
        // From here on the control channel is AES-256-GCM. Both ends install the cipher at exactly this
        // point, which is what keeps them in step without a per-frame flag an attacker could clear.
        socket.codec.useSessionKey(sessionKeys.control)
        val frozen = transcript.value()
        transcriptValue = frozen

        val required = socket.receive()?.message as? PairRequired
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        if (required.code) {
            // The TV does not know us - either we have never paired, or it was reset since. Either way
            // the key we may be holding is worthless and the user has to read the screen.
            return HandshakeOutcome.NeedsCode(required.attemptsLeft, codeChanged = false)
        }
        val deviceKey = storedDeviceKey ?: return protocolFailure()
        return proof(sessionKeys.deviceProof(deviceKey, frozen))
    }

    /**
     * Prove the six digits the user read off the TV.
     *
     * A wrong code is a routine outcome rather than an error: the session stays open and
     * [HandshakeOutcome.NeedsCode] comes back with one fewer attempt, so the user simply types it
     * again.
     */
    fun enterCode(code: String): HandshakeOutcome {
        if (!PairCode.isWellFormed(code)) {
            // Refused here rather than on the wire: a typo must not spend one of three attempts.
            return HandshakeOutcome.NeedsCode(attemptsLeft = -1, codeChanged = false)
        }
        val sessionKeys = keys ?: return protocolFailure()
        val frozen = transcriptValue ?: return protocolFailure()
        return proof(sessionKeys.pairProof(code, frozen))
    }

    private fun proof(bytes: ByteArray): HandshakeOutcome {
        socket.send(PairProof(proof = ProtocolBase64.encode(bytes)))
        val reply = socket.receive() ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        return when (val message = reply.message) {
            is PairOk -> {
                Log.i(TAG, "paired with '$receiverName'")
                HandshakeOutcome.Paired(message.deviceKey?.let { ProtocolBase64.decode(it) })
            }
            is PairFailed -> HandshakeOutcome.NeedsCode(
                attemptsLeft = message.attemptsLeft,
                codeChanged = message.codeChanged,
            )
            // The answer to a *device* proof the TV would not accept: it has forgotten us - a reinstall
            // or a reset - and has put a code on screen instead. Not a failure; the user just has to
            // read it.
            is PairRequired -> {
                Log.i(TAG, "'$receiverName' no longer remembers this phone; a code is on screen")
                HandshakeOutcome.NeedsCode(message.attemptsLeft, codeChanged = false)
            }
            else -> HandshakeOutcome.Failed(ClientFailure.Protocol)
        }
    }

    /**
     * `STREAM_CONFIG` → `STREAM_READY`.
     *
     * [width] and [height] are the phone's own choice within the TV's reported limits: it sends its
     * native aspect ratio and the TV letterboxes, which is the whole reason for owning the receiver.
     *
     * [appLabel] is empty for screen mirroring and the streaming app's name for an SDK session; the TV
     * shows it instead of the phone's name.
     */
    fun configureStream(
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        videoCodec: VideoCodec,
        audio: Boolean,
        video: Boolean,
        appLabel: String = "",
    ): HandshakeOutcome {
        val sessionKeys = keys ?: return protocolFailure()
        val random = SecureRandom()
        val config = StreamConfig(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = bitRate,
            audio = audio,
            video = video,
            audioSsrc = random.ssrc(StreamConstants.AUDIO_SSRC_MIN, StreamConstants.AUDIO_SSRC_MAX),
            videoSsrc = random.ssrc(StreamConstants.VIDEO_SSRC_MIN, StreamConstants.VIDEO_SSRC_MAX),
            videoCodec = videoCodec,
            appLabel = appLabel,
        )
        socket.send(config)
        val ready = socket.receive()?.message as? StreamReady
            ?: return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        if (ready.udpPort !in 1..65535) {
            Log.w(TAG, "the TV named udp port ${ready.udpPort}")
            return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        }
        Log.i(TAG, "streaming ${videoCodec.label} ${width}x$height to udp ${ready.udpPort}")
        return HandshakeOutcome.Ready(Negotiation.of(config, ready, sessionKeys))
    }

    /**
     * `CONTENT_SESSION` → `CONTENT_READY`, for app content that has a file behind it.
     *
     * The replacement for [configureStream] on that path, and there is no `STREAM_READY` and no UDP
     * port because there is no RTP: the TV fetches byte ranges from the proxy instead. Nothing here
     * negotiates a codec, because nothing is being encoded - the TV decodes whatever the file already
     * is, which is the entire point.
     *
     * [certificateFingerprint] travels on this channel precisely because it is already encrypted and
     * bound to the pairing transcript. That is what lets the TV pin one certificate instead of
     * trusting an authority to vouch for an address on a LAN.
     */
    fun openContentSession(
        host: String,
        port: Int,
        certificateFingerprint: ByteArray,
        token: String,
        video: Boolean,
        appLabel: String,
    ): ContentOutcome {
        socket.send(
            ContentSession(
                host = host,
                port = port,
                certificateFingerprint = ProtocolBase64.encode(certificateFingerprint),
                token = token,
                video = video,
                appLabel = appLabel,
            ),
        )
        val ready = socket.receive()?.message as? ContentReady
            ?: return ContentOutcome.Refused("the TV did not answer")
        if (!ready.accepted) {
            Log.w(TAG, "the TV refused a content session: ${ready.detail}")
            return ContentOutcome.Refused(ready.detail)
        }
        Log.i(TAG, "serving ${if (video) "audio and video" else "audio"} from $host:$port")
        return ContentOutcome.Accepted
    }

    /**
     * Tell the TV what to play next from the proxy.
     *
     * Sent once per item rather than as a playlist, because the queue stays on the phone: it owns the
     * ordering, the artwork and the metadata, so "next" is a decision this end makes and then reports.
     */
    fun playMedia(media: PlayMedia) {
        Log.i(TAG, "asking the TV to play ${media.resourceId}")
        runCatching { socket.send(media) }
            .onFailure { Log.w(TAG, "could not send the play request", it) }
    }

    /**
     * Hand the TV the video codec configuration, for a codec that cannot carry it in-band.
     *
     * Sent on the already-open control channel rather than in a media packet, because the receiver
     * needs it *before* it configures its decoder - there is no frame yet to attach it to. Called
     * repeatedly and idempotent: every key-frame request re-sends it, which is what gives it a repair
     * path at all.
     */
    fun sendCodecConfig(csd: ByteArray) {
        Log.i(TAG, "sending codec config: ${csd.size} bytes")
        runCatching { socket.send(VideoCodecConfig(csd = ProtocolBase64.encode(csd))) }
            .onFailure { Log.w(TAG, "could not send the codec config", it) }
    }

    /**
     * Put a playback snapshot on the control channel, for the TV's seek bar.
     *
     * Follows [sendCodecConfig] in being non-throwing, and for the same kind of reason: this is called
     * from a poll loop, so a failure would recur every tick and there is nothing useful to do about
     * one. A socket that has actually died is [awaitEnd]'s business and it will say so.
     *
     * Not logged per send. Two of these a second would bury everything else in the log, which is the
     * opposite of what the logging in this class is for.
     */
    fun sendPlaybackState(state: PlaybackState) {
        runCatching { socket.send(state) }
            .onFailure { Log.w(TAG, "could not send the playback state", it) }
    }

    /**
     * Wait for the TV to say goodbye, or for the socket to die, acting on anything else it sends.
     *
     * Held open for the whole session so that a TV going away is noticed at once, rather than only
     * when UDP starts failing - a control channel nobody reads is a control channel that cannot report
     * anything.
     *
     * Returns the `BYE` reason, or null when the socket died without one. The reason is carried back
     * rather than only logged because one of them - [ByeReason.MISSING_CODEC_CONFIG] - is a decision
     * the caller has to act on, not a note for a human.
     *
     * **A dispatch loop rather than a drain.** This used to discard everything that was not a `Bye`,
     * which was right while the TV had nothing else to say. It has a remote now, and its
     * [PlaybackCommand]s arrive on this same socket - and cannot arrive anywhere else, because there
     * is one socket with one read position and this is already the only reader.
     *
     * [onCommand] is null for screen mirroring, which has no transport to control. A command that
     * turns up anyway is logged and dropped rather than acted on.
     */
    fun awaitEnd(onCommand: ((PlaybackCommand) -> Unit)? = null): String? {
        while (true) {
            val next = socket.receive() ?: return null
            when (val message = next.message) {
                is Bye -> {
                    Log.i(TAG, "'$receiverName' said goodbye: ${message.reason.ifBlank { "no reason" }}")
                    return message.reason
                }
                is PlaybackCommand -> {
                    val argument = message.value?.let { " $it" }.orEmpty()
                    if (onCommand == null) {
                        Log.w(TAG, "'$receiverName' asked for ${message.action}$argument, " +
                            "but there is no transport to control")
                    } else {
                        Log.i(TAG, "'$receiverName' asked for ${message.action}$argument")
                        onCommand(message)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Say goodbye.
     *
     * Sent rather than just closing the socket, so the TV returns to its idle screen at once instead of
     * holding the last frame until a read times out.
     */
    fun sayGoodbye(reason: String) {
        runCatching { socket.send(Bye(reason = reason)) }
    }

    private fun protocolFailure() = HandshakeOutcome.Failed(ClientFailure.Protocol)

    private fun SecureRandom.ssrc(min: Int, max: Int): Long = (min + nextInt(max - min + 1)).toLong()

    companion object {
        /** What the TV shows while it waits for a code, and what its logs name us as. */
        fun deviceName(): String =
            listOf(Build.MODEL, Build.DEVICE).firstOrNull { !it.isNullOrBlank() } ?: "Phone"
    }
}
