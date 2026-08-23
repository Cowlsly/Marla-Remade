package com.vayunmathur.cast.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The protocol version in every `HELLO` and `TV_IDENTITY`.
 *
 * Checked on both sides and refused on mismatch, rather than negotiated down. Two of our own builds
 * that disagree about the wire format are a bug to fix, not a compatibility matrix to maintain -
 * both halves ship from this repo at the same version.
 *
 * 3 is where H.264 stopped being the only codec. [DecoderLimits] became a per-codec list and
 * [StreamConfig] gained a required codec, both reshaped rather than extended with compatibility
 * defaults - a mismatch is refused outright anyway, so the bump is the honest signal.
 *
 * 4 adds [PlaybackState] and [PlaybackCommand], which turn the TV from a display into a remote. A
 * phone on 3 would never send state, so the TV's overlay would be permanently empty, and a TV on 3
 * would drop every command it was sent - both are silent failures, which is exactly what a refused
 * version check is for.
 *
 * 5 is where app content stopped being pixels. [ContentSession] and [PlayMedia] replace
 * `STREAM_CONFIG` for anything with a file behind it: the phone serves the original bytes over HTTPS
 * and the TV decodes them, so no video is encoded, seeking is a byte offset and audio-only becomes
 * expressible at all. Screen mirroring keeps the RTP path unchanged, because it has no file to serve.
 * [StreamConfig.videoCodec] became optional in the same bump, and [DecoderLimits] grew an audio half.
 * [Ping] arrived in the same version, once a served session turned out to be able to go quiet for
 * longer than either end's read timeout allows.
 */
const val PROTOCOL_VERSION = 5

/** The mDNS service type the TV registers and the phone browses for. */
const val MACAST_SERVICE_TYPE = "_macast._tcp"

/**
 * The control-channel sequence, in the order it happens:
 *
 * ```
 * phone  HELLO ─────────────────────────────────►
 *                          ◄───────────── TV_IDENTITY   (public bundle + decoder limits)
 *        SEALED_SECRET ────────────────────────►        (session secret, sealed to that bundle)
 *
 *                          ◄───────────── PAIR_REQUIRED (code, or "prove the key you hold")
 *        PAIR_PROOF ──────────────────────────►
 *                          ◄───────────── PAIR_OK / PAIR_FAILED
 *
 *        STREAM_CONFIG ───────────────────────►         (chosen to fit the reported limits)
 *                          ◄───────────── STREAM_READY  (udp port + receiver SSRCs)
 *        VIDEO_CODEC_CONFIG ─────────────────►          (AV1 only, and repeated on every PLI)
 *        … RTP over UDP …
 *        PLAYBACK_STATE ─────────────────────►          (app content only, ~2/s while it plays)
 *                          ◄───────────── PLAYBACK_COMMAND (the TV remote, whenever it is pressed)
 *        BYE ─────────────────────────────────►
 * ```
 *
 * Screen mirroring takes that path. **App content takes a different one from `STREAM_CONFIG`
 * onward**, because it has a file behind it and does not need to be re-encoded:
 *
 * ```
 *        CONTENT_SESSION ─────────────────────►         (proxy host, port, cert fingerprint, token)
 *                          ◄───────────── CONTENT_READY (accepted, or refused with a reason)
 *        PLAY_MEDIA ──────────────────────────►         (which resource, and what is in it)
 *        … the TV fetches byte ranges over HTTPS and decodes them itself …
 *        PING ────────────────────────────────►         (every 20 s, so neither read deadline expires)
 *                          ◄───────────── PING          (echoed straight back)
 *        BYE ─────────────────────────────────►
 * ```
 *
 * Everything from `PAIR_REQUIRED` onward is AES-256-GCM under the derived control key; the first
 * three messages cannot be, because they are what establishes it. Both ends install the cipher at
 * the same point in the sequence, so they can never disagree about which frames are encrypted.
 */
@Serializable
sealed interface ControlMessage

/** Who is calling, and whether it expects to be remembered. */
@Serializable
@SerialName("HELLO")
data class Hello(
    val version: Int = PROTOCOL_VERSION,
    val senderName: String,
    /**
     * A stable id for this phone, so the TV can look up a remembered device key before it knows
     * anything else. Not a secret: it only decides whether a code will be asked for.
     */
    val senderId: String,
    /**
     * True when the phone holds a device key for this TV.
     *
     * The TV still decides - it may have forgotten the phone, or been factory reset - but this is
     * what lets it avoid putting a pair code on screen for a phone that does not need one.
     */
    val paired: Boolean = false,
) : ControlMessage

/** What the TV is, what it can decode, and the key to seal a secret to. */
@Serializable
@SerialName("TV_IDENTITY")
data class TvIdentity(
    val version: Int = PROTOCOL_VERSION,
    val receiverName: String,
    val receiverId: String,
    /** Base64 ML-KEM + ML-DSA public bundle, from `PqcIdentity.publicBundle`. */
    val publicBundle: String,
    val limits: DecoderLimits,
) : ControlMessage

/**
 * What the TV's decoder will actually accept.
 *
 * **The single clearest benefit of owning the receiver.** The old sender had to guess, settled on
 * 1080p because that was Chrome's mirroring policy, and letterboxed a portrait phone screen into it
 * by hand - wasting most of the frame. These come from the TV's real
 * `MediaCodecInfo.VideoCapabilities`, so the sender can send the phone's own aspect ratio at
 * whatever size fits and let the TV pad it.
 *
 * **One envelope per codec, and no flat AVC-shaped fields.** Those four numbers were
 * `getCapabilitiesForType(AVC)` output; with AVC gone they would be a lie, and an H.265 decoder's
 * ceiling is not an AV1 decoder's. An empty list is a receiver that could enumerate no hardware
 * decoder at all, and the phone refuses with a named failure rather than guessing.
 */
@Serializable
data class DecoderLimits(
    val videoCodecs: List<CodecLimits> = emptyList(),
    /**
     * The audio codecs this TV can decode, which until now nothing asked about.
     *
     * Audio used to need no negotiation because it was never the only thing in a session: a TV with
     * no Opus decoder simply played no sound over a picture that still worked. An audio-only session
     * has no picture to carry it, so the same TV would sit in silence with nothing to explain it -
     * and there was no failure state to report, because there was never a question.
     *
     * Empty means the TV enumerated no audio decoder, and an audio-only session is refused by name.
     * Defaulted so a TV built against the older contract still parses; it will advertise nothing,
     * which is the honest reading of a receiver that was never asked.
     */
    val audioCodecs: List<AudioCodec> = emptyList(),
) {
    fun forCodec(codec: VideoCodec): CodecLimits? = videoCodecs.firstOrNull { it.codec == codec }

    val codecs: List<VideoCodec> get() = videoCodecs.map { it.codec }
}

/**
 * The audio codecs this protocol carries, which is one.
 *
 * Every audio path in the repo already ends at 48 kHz Opus - `:musicbrainz` normalises its downloads
 * to it, the RTP timebase *is* its sample rate, and the phone's encoder produces nothing else - so a
 * list of one is not a placeholder for a negotiation that never happened. It is an enum rather than
 * a boolean because it is the wire form of "which", and a boolean would have to be renamed the day a
 * second codec appears.
 *
 * It is also the single definition of the Opus MIME type, which was previously written out
 * independently on each side: the phone's encoder support and the TV's decoder lookup are the two
 * ends of one agreement, and two literals could drift.
 *
 * [mimeType] is spelled out rather than taken from `MediaFormat`, for the reason [VideoCodec] gives.
 */
@Serializable
enum class AudioCodec(val mimeType: String, val label: String) {
    @SerialName("OPUS")
    Opus("audio/opus", "Opus"),
}

/**
 * The video codecs this protocol carries, in no particular order - [CodecNegotiation] holds the
 * preference.
 *
 * **H.264 is deliberately absent.** H.265 and AV1 reach the same quality at roughly half the
 * bitrate, and on a link that answered 24 Mbit/s with 8.5% packet loss the codec is what buys back
 * the headroom. A device with neither hardware codec refuses to mirror and says which were missing,
 * rather than falling back to the thing that caused the problem.
 *
 * [mimeType] is spelled out rather than taken from `MediaFormat`: this module is an Android library
 * only because [SecretSealing] needs one, and every test in it runs on a plain JVM.
 */
@Serializable
enum class VideoCodec(val mimeType: String, val label: String) {
    @SerialName("H265")
    Hevc("video/hevc", "H.265"),

    @SerialName("AV1")
    Av1("video/av01", "AV1"),
    ;

    /**
     * Whether the decoder must be handed its codec configuration out of band, through
     * [VideoCodecConfig], before it can be configured at all.
     *
     * H.265 inherits the H.264 contract unchanged: VPS/SPS/PPS are Annex-B start-code units, so the
     * sender prepends them to the first IDR and the receiver finds them in the stream. AV1's
     * sequence header is an OBU that `MediaCodec` expects as `csd-0`, and the receiver configures its
     * decoder *before* any frame arrives - so for AV1 there is nowhere in the stream to read it from.
     */
    val needsCodecConfig: Boolean get() = this == Av1
}

/** One codec's envelope, as reported by whichever end owns the codec. */
@Serializable
data class CodecLimits(
    val codec: VideoCodec,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val maxBitRate: Int,
) {
    /**
     * The largest frame with [width] x [height]'s aspect ratio that fits this envelope.
     *
     * Both dimensions are checked independently, because a decoder's limits are not necessarily the
     * same shape as a phone's screen: a 3840x2160 decoder still cannot take a 1440x3120 portrait
     * frame unrotated, and scaling by the tighter of the two ratios is what respects both.
     *
     * Lives here rather than in the sender so codec selection can be a pure function - the choice
     * depends on the *fitted* frame, and a copy of this arithmetic on each side would be a copy that
     * could disagree.
     */
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        if (maxWidth <= 0 || maxHeight <= 0) return width to height
        if (width <= maxWidth && height <= maxHeight) return width to height
        val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        return (width * scale).toInt() to (height * scale).toInt()
    }

    /** Whether this envelope takes exactly [width] x [height] at [frameRate]. */
    fun admits(width: Int, height: Int, frameRate: Int): Boolean =
        width in 1..maxWidth && height in 1..maxHeight && frameRate <= maxFrameRate
}

/** The session secret, sealed to [TvIdentity.publicBundle]. Base64 of [SecretSealing.seal]. */
@Serializable
@SerialName("SEALED_SECRET")
data class SealedSecret(val sealed: String) : ControlMessage

/**
 * The TV's answer to `SEALED_SECRET`, and always exactly one message so the sequence has no branch
 * the phone has to guess at.
 *
 * [code] false means the TV remembers this phone and wants only a proof of the device key it holds;
 * true means it does not, and six digits are on screen. Making this explicit rather than letting the
 * phone infer it from its own stored state matters: the TV may have been factory reset, and a phone
 * that guessed wrong would spend one of three pair attempts finding out.
 */
@Serializable
@SerialName("PAIR_REQUIRED")
data class PairRequired(
    val code: Boolean,
    val attemptsLeft: Int = PairCode.MAX_ATTEMPTS,
) : ControlMessage

/**
 * Either the code proof or the remembered-device proof - the TV knows which it asked for, so one
 * message carries both cases.
 */
@Serializable
@SerialName("PAIR_PROOF")
data class PairProof(val proof: String) : ControlMessage

/**
 * Paired.
 *
 * [deviceKey] is a fresh random key, present only when this was a code pairing; both ends persist it
 * and later sessions authenticate with it instead of a code. Absent when the phone had already
 * proved possession of one.
 */
@Serializable
@SerialName("PAIR_OK")
data class PairOk(val deviceKey: String? = null) : ControlMessage

/**
 * Wrong proof.
 *
 * [attemptsLeft] is how many tries remain against the code currently on screen. [codeChanged] means
 * the allowance ran out and the TV has replaced the code, so the user has to look at the screen again
 * rather than retype what they had - stated outright rather than left to be inferred from a full
 * allowance coming back from a failure.
 */
@Serializable
@SerialName("PAIR_FAILED")
data class PairFailed(
    val attemptsLeft: Int,
    val codeChanged: Boolean = false,
) : ControlMessage

/**
 * What the phone is about to send.
 *
 * The SSRCs are the phone's own. The receiver answers with its own in [StreamReady]; neither may
 * derive the other's, because the pair is what identifies a stream in every RTCP packet.
 */
@Serializable
@SerialName("STREAM_CONFIG")
data class StreamConfig(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int,
    val audio: Boolean,
    val video: Boolean,
    val audioSsrc: Long,
    val videoSsrc: Long,
    /**
     * The codec the payload is in, or null when the session carries no video.
     *
     * Non-null exactly when [video] is set. Nullable rather than absent because an audio-only
     * session has no video codec to name and a defaulted one would be a receiver silently agreeing
     * to decode something nobody mentioned - the failure the required field was added to prevent.
     * The receiver builds its decoder from this, so it is also what decides whether a
     * [VideoCodecConfig] has to arrive before the picture can start.
     */
    val videoCodec: VideoCodec? = null,
    /**
     * The name of the app whose content this is, or empty for screen mirroring.
     *
     * The receiver shows it instead of the phone's name, so "Receiving from YouPipe" rather than
     * "Receiving from Pixel 9". Never self-reported by the streaming app: `:cast` resolves it from the
     * `callingPackage` the framework attaches to its picker Activity, so it is an identity the sender
     * could not have forged.
     */
    val appLabel: String = "",
) : ControlMessage

/** Where to send it, and which SSRCs the feedback will come from. */
@Serializable
@SerialName("STREAM_READY")
data class StreamReady(
    val udpPort: Int,
    val audioSsrc: Long,
    val videoSsrc: Long,
) : ControlMessage

/**
 * The phone is serving app content over HTTPS instead of encoding it. Everything the TV needs to
 * fetch it.
 *
 * This is what replaces [StreamConfig] for anything with a file behind it, and the reason the whole
 * pixel path can go away for app content: the TV fetches byte ranges of the original media and owns
 * its own decoder, clock and buffer. Seeking becomes an offset instead of a key-frame renegotiation,
 * a pause is the TV's business alone, and the phone neither decodes nor encodes a frame.
 *
 * **The fingerprint is why this can be TLS with no certificate authority.** It arrives here, on a
 * channel already AES-256-GCM under a key derived from the ML-KEM handshake and bound to the pairing
 * transcript - so pinning it is strictly stronger than trusting a CA to attest to a name on a LAN.
 * The TV must pin it and trust nothing else; a permissive trust manager would throw away the only
 * guarantee the pairing bought.
 *
 * [token] is a capability, not an identifier. It goes in the URL path so that it travels with a bare
 * URL handed to a player, and it is the only thing between another device on the LAN that has found
 * the port and the user's media.
 */
@Serializable
@SerialName("CONTENT_SESSION")
data class ContentSession(
    /** The phone's address on this network, as the TV should dial it. */
    val host: String,
    val port: Int,
    /** Base64 of SHA-256 over the proxy certificate's DER encoding. */
    val certificateFingerprint: String,
    val token: String,
    /**
     * False for an audio-only session, where there is no picture and the TV shows a now-playing
     * screen instead of a black surface.
     */
    val video: Boolean,
    /** The app whose content this is, resolved by `:cast` from the picker's `callingPackage`. */
    val appLabel: String = "",
) : ControlMessage

/**
 * Whether the TV can serve a [ContentSession], and if not, why.
 *
 * An answer rather than silence because the failures here are the kind that otherwise look like
 * success: a TV with no Opus decoder would accept an audio-only session and then play nothing, with
 * no picture to make the fault visible and nothing on screen to explain it. The phone refuses the
 * session instead and says so, which is the whole argument for the reply existing.
 */
@Serializable
@SerialName("CONTENT_READY")
data class ContentReady(
    val accepted: Boolean,
    /** For the log and for the phone's message to the user. Empty when accepted. */
    val detail: String = "",
) : ControlMessage

/**
 * Play this, from the proxy.
 *
 * [resourceId] is the app's own name for it, appended to the proxy's path; the phone maps it back to
 * a file descriptor when the TV asks. Sent again for each new item, which is how a queue advances -
 * the queue itself stays on the phone, because it owns the artwork, the metadata and the ordering.
 */
@Serializable
@SerialName("PLAY_MEDIA")
data class PlayMedia(
    val resourceId: String,
    /** What the TV should expect, so its player does not have to sniff before it can start. */
    val mimeType: String,
    /**
     * Length in milliseconds, or 0 when the phone does not know.
     *
     * Carried even though the container states it, because the TV's seek bar can then be drawn
     * before the first byte arrives rather than appearing a moment into playback.
     */
    val durationMs: Long = 0,
) : ControlMessage

/**
 * The video decoder's configuration bytes, phone to TV, for a codec that cannot carry them in-band.
 *
 * [csd] is Base64 of whatever `MediaCodec` handed the sender in its `BUFFER_FLAG_CODEC_CONFIG`
 * buffer, passed through untouched: for AV1 that is an `av1C` configuration record or a raw sequence
 * header OBU, and which one it is turns out to be a device fact rather than an API fact. The receiver
 * installs it as `csd-0`.
 *
 * **Re-sent on every key-frame request, not delivered once.** `CODEC_CONFIG` is emitted only at
 * `start()`, so a single-shot delivery has no repair path and losing it would be a permanent black
 * screen. Re-sending keeps PLI as the one repair primitive for both codecs.
 */
@Serializable
@SerialName("VIDEO_CODEC_CONFIG")
data class VideoCodecConfig(val csd: String) : ControlMessage

/**
 * Where playback is, phone to TV, so a television can draw a seek bar for something it is only
 * decoding.
 *
 * **A snapshot, never a delta.** Sent on any material change and otherwise at a slow heartbeat, and
 * every field is absolute - so a lost message costs at most one heartbeat of staleness and repairs
 * itself, with no sequence numbers, no acknowledgements and no resynchronisation path to get wrong.
 *
 * **The phone owns the truth.** The TV cannot compute [positionMs] for itself: what it holds is a
 * 150 ms RTP jitter buffer, which is a smoothing device and not a content clock. Between snapshots it
 * interpolates from the last one it received, and every fresh snapshot re-anchors that estimate.
 *
 * [hasNext] and [hasPrevious] are carried because the TV must not offer a button that does nothing -
 * and it has no way to know whether there is anything to skip to, since the queue is a list of
 * related videos that only the phone can see.
 */
@Serializable
@SerialName("PLAYBACK_STATE")
data class PlaybackState(
    val positionMs: Long,
    /** Zero or negative for a stream with no known end, which the TV renders without a bar. */
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    /** The tempo multiplier, 1.0 being normal. */
    val speed: Float = 1f,
    /** Media volume as 0..1, the same level on both ends - see `PLAYBACK_COMMAND`'s `SET_VOLUME`. */
    val volume: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) : ControlMessage {

    /**
     * Where playback will be [elapsedMs] after this snapshot arrived.
     *
     * The TV redraws at its display's rate from snapshots that land twice a second, so a bar plotted
     * at [positionMs] would visibly step. It records the wall-clock time each snapshot arrived and
     * asks this for the position now; every fresh snapshot re-anchors, so the estimate can drift for
     * at most one heartbeat and never accumulates.
     *
     * A pure function of two numbers on purpose - it is the one piece of this feature that can be
     * pinned down without a television in the room.
     *
     * Paused holds still, [speed] scales the advance, and the result never runs past [durationMs].
     * Buffering needs no case of its own: the phone reports [playing] from the player's own
     * `isPlaying`, which is already false while it stalls.
     */
    fun interpolated(elapsedMs: Long): Long {
        if (!playing || elapsedMs <= 0) return positionMs.coerceAtLeast(0)
        val advanced = positionMs + (elapsedMs * speed.toDouble()).toLong()
        return if (durationMs > 0) advanced.coerceIn(0, durationMs) else advanced.coerceAtLeast(0)
    }
}

/**
 * A press on the television's remote, TV to phone.
 *
 * **One message with an action enum rather than ten message types.** The set of things a remote can
 * ask for is going to grow, and a TV that needed a protocol bump to gain a button would mean shipping
 * both apps again for a change that is entirely on one side. The phone ignores an action it does not
 * recognise, which is the only forward compatibility this needs.
 *
 * The phone is free to refuse any of these. A [PlaybackAction.Next] with nothing to play next is
 * dropped rather than answered, and the TV finds out the ordinary way - the next [PlaybackState]
 * simply does not change.
 */
@Serializable
@SerialName("PLAYBACK_COMMAND")
data class PlaybackCommand(
    val action: PlaybackAction,
    /**
     * The action's argument, or null for the ones that take none.
     *
     * One nullable `Double` rather than a field per type: milliseconds for [PlaybackAction.SeekTo], a
     * multiplier for [PlaybackAction.SetSpeed], 0..1 for [PlaybackAction.SetVolume]. A `Double`
     * represents every millisecond of a plausible video exactly, so nothing is lost by not having a
     * `Long` here, and the alternative is three mostly-null fields that can disagree.
     */
    val value: Double? = null,
) : ControlMessage

/**
 * What the remote asked for.
 *
 * Serial names are pinned because these cross the wire; the Kotlin identifiers are free to change.
 */
@Serializable
enum class PlaybackAction {
    @SerialName("PLAY")
    Play,

    @SerialName("PAUSE")
    Pause,

    /**
     * Whichever of the two the phone is not currently doing.
     *
     * Distinct from [Play] and [Pause] on purpose: a D-pad centre press means "the other one", and
     * asking the TV to decide from its own possibly-stale snapshot is how a double press ends up
     * doing nothing.
     */
    @SerialName("TOGGLE")
    Toggle,

    /** [PlaybackCommand.value] is an absolute position in milliseconds. */
    @SerialName("SEEK_TO")
    SeekTo,

    /** The phone's own skip interval, so the two ends cannot disagree about how far ten seconds is. */
    @SerialName("SKIP_FORWARD")
    SkipForward,

    @SerialName("SKIP_BACK")
    SkipBack,

    @SerialName("NEXT")
    Next,

    @SerialName("PREVIOUS")
    Previous,

    /** [PlaybackCommand.value] is a tempo multiplier. */
    @SerialName("SET_SPEED")
    SetSpeed,

    /** [PlaybackCommand.value] is 0..1, and moves the phone's media volume as well as the TV's. */
    @SerialName("SET_VOLUME")
    SetVolume,
}

/**
 * Nothing to say, said out loud.
 *
 * **A read timeout is the only liveness check either end has, and it cannot tell a quiet session
 * from a dead one.** Both ends give a read 60 seconds, which was sized for the longest legitimate
 * wait in a handshake - a human reading six digits off a screen. A live content session has no such
 * guarantee: the phone may have nothing whatever to say for minutes, because the TV is fetching the
 * media over HTTPS on its own and owns its own clock. So a perfectly healthy session used to be
 * torn down a minute in, by whichever end read first.
 *
 * Sent by the phone every [PING_INTERVAL_MS] while a content session is live, and **echoed straight
 * back by the receiver**. The echo is not politeness: `SO_TIMEOUT` is a *read* timeout, so the
 * phone's own outbound ping does nothing for the phone's deadline, and without the reply the phone
 * would drop the session at 60 seconds exactly as the TV used to.
 *
 * Carries no payload. Anything worth knowing is already in [PlaybackState], and a keep-alive that
 * grew fields would become a second, competing source of truth about the same session.
 */
@Serializable
@SerialName("PING")
data object Ping : ControlMessage

/**
 * How often a ping goes out, at a third of the 60 s read timeout.
 *
 * A third rather than a half so that two consecutive pings can be lost - to a stall, a scheduler
 * that did not run the coroutine, a moment of packet loss - before either end concludes the other
 * has gone.
 */
const val PING_INTERVAL_MS = 20_000L

/**
 * Either end, at any point after the secret is established.
 *
 * Sent rather than just closing the socket, so the TV can return to its idle screen instead of
 * holding the last frame while it waits for a read to time out.
 */
@Serializable
@SerialName("BYE")
data class Bye(val reason: String = "") : ControlMessage

/**
 * The [Bye.reason] values the *phone* acts on rather than only logs.
 *
 * A free-text reason is right for everything a human reads in a log, but an AV1 failure has to reach
 * the sender as a decision: it is what makes the next session start at H.265 instead. Naming the
 * string here is what stops the two ends from disagreeing about its spelling.
 */
object ByeReason {
    /** The TV had a decoder and a surface, and the codec config it needed never arrived. */
    const val MISSING_CODEC_CONFIG = "missing codec config"
}

/**
 * Strict on the way in, complete on the way out.
 *
 * `ignoreUnknownKeys` because a field added by a newer build must not kill a session outright, and
 * `encodeDefaults` because the discriminator and the version are defaulted constants that the peer
 * genuinely needs. `explicitNulls` off so an absent `deviceKey` stays absent.
 */
val ControlJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
