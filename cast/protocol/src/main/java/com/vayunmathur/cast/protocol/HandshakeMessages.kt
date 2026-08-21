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
 */
const val PROTOCOL_VERSION = 1

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
 *        … RTP over UDP …
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
 */
@Serializable
data class DecoderLimits(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val maxBitRate: Int,
)

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
 * Either end, at any point after the secret is established.
 *
 * Sent rather than just closing the socket, so the TV can return to its idle screen instead of
 * holding the last frame while it waits for a read to time out.
 */
@Serializable
@SerialName("BYE")
data class Bye(val reason: String = "") : ControlMessage

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
