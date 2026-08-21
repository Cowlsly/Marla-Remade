package com.vayunmathur.cast.domain.streaming

import com.vayunmathur.cast.domain.CastJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OFFER/ANSWER wire types, transcribed from openscreen.
 *
 * Sources, checked rather than recalled:
 *  - the envelopes from `cast/streaming/sender_message.cc` and
 *    `cast/streaming/public/receiver_message.cc`
 *  - the offer body from `cast/streaming/public/offer_messages.cc` (`Offer::ToJson`,
 *    `Stream::ToJson`, `AudioStream::ToJson`, `VideoStream::ToJson`)
 *  - the answer body from `cast/streaming/public/answer_messages.cc` (`Answer::TryParse`)
 *
 * Reuses [CastJson], whose `explicitNulls = false` is what keeps absent optionals absent - the
 * receiver is not tolerant of `"codecParameter": null` where openscreen would have omitted it.
 */

/** `{"type":"OFFER","seqNum":N,"offer":{...}}`. */
@Serializable
data class OfferMessage(
    val seqNum: Int,
    val offer: Offer,
    val type: String = "OFFER",
)

@Serializable
data class Offer(
    val supportedStreams: List<OfferStream>,
    /** `"mirroring"` or `"remoting"`; only mirroring is used here. */
    val castMode: String = "mirroring",
)

/**
 * One stream descriptor. Audio and video share every field except the last few, so this is one
 * type with optionals rather than a sealed hierarchy - the wire format is one JSON object shape
 * discriminated by [type], and mirroring that keeps the serialiser honest.
 *
 * Field order here follows `Stream::ToJson` so a diff against openscreen stays readable.
 */
@Serializable
data class OfferStream(
    val index: Int,
    /** `audio_source` or `video_source`. */
    val type: String,
    val codecName: String,
    /** Always `cast`. Required by the spec even though it never varies. */
    val rtpProfile: String = "cast",
    /**
     * 127 for audio and 96 for video - the "Android TV hack" values from
     * `cast/streaming/impl/rtp_defines.h`. Not the obvious `kAudioOpus`/`kVideoH264`: openscreen's
     * `use_android_rtp_hack` defaults to true and Chrome never overrides it, so these are what a
     * receiver actually expects. 96 deliberately collides with `kAudioOpus`; the receiver
     * disambiguates by [index], not by payload type.
     */
    val rtpPayloadType: Int,
    val ssrc: Long,
    /** Audio is 2, video is 1 - `mirror_settings.cc`. */
    val channels: Int,
    val targetDelay: Int,
    /** Hex, 16 bytes. */
    val aesKey: String,
    /** Hex, 16 bytes. */
    val aesIvMask: String,
    val receiverRtcpEventLog: Boolean = true,
    /** A *string* of the form `1/90000`, not a number. */
    val timeBase: String,
    // Audio only.
    val bitRate: Int? = null,
    // Video only.
    /** A rational *string* such as `30000/1000`, not a number. */
    val maxFrameRate: String? = null,
    val maxBitRate: Int? = null,
    val resolutions: List<Resolution>? = null,
)

@Serializable
data class Resolution(val width: Int, val height: Int)

/**
 * `{"type":"ANSWER","seqNum":N,"result":"ok","answer":{...}}`, or `"result":"error"` with an
 * `error` object instead.
 */
@Serializable
data class AnswerMessage(
    val type: String = "",
    val seqNum: Int? = null,
    val result: String = "error",
    val answer: Answer? = null,
    val error: AnswerError? = null,
)

@Serializable
data class AnswerError(
    val code: Int = 0,
    val description: String = "",
)

/**
 * What the receiver agreed to.
 *
 * [sendIndexes] is the authoritative stream set: a speaker answers `[0]` however much was
 * offered, which is how the audio-only case is really decided. [ssrcs] are the *receiver's*
 * SSRCs, positionally matching [sendIndexes].
 *
 * Everything except those three is optional because the two receiver classes disagree about what
 * to send: a TV answered with `display` and nothing else, a speaker with `castMode`,
 * `receiverRtcpEventLog` and `rtpExtensions` but no `display` at all.
 */
@Serializable
data class Answer(
    val udpPort: Int,
    val sendIndexes: List<Int>,
    val ssrcs: List<Long>,
    /** Absent on a device with no screen, which is the natural "audio only" signal. */
    val display: DisplayDescription? = null,
    val castMode: String? = null,
    /**
     * An int array here, though the same-named OFFER field is a per-stream boolean. The asymmetry
     * is openscreen's: `answer_messages.cc` parses it with `TryParseIntArray`.
     */
    val receiverRtcpEventLog: List<Int>? = null,
    /** A *nested* array here - `TryParseNestedStringArray` in the same file. */
    val rtpExtensions: List<List<String>>? = null,
)

@Serializable
data class DisplayDescription(
    val dimensions: Dimensions? = null,
    /** `sender` means the receiver expects us to scale before encoding. */
    val scaling: String? = null,
)

@Serializable
data class Dimensions(
    val width: Int = 0,
    val height: Int = 0,
    /** A string, not a number. */
    @SerialName("frameRate") val frameRate: String? = null,
)

/** Serialise an OFFER for the wire. */
fun OfferMessage.encode(): String = CastJson.encodeToString(this)

/** Parse an ANSWER, or null when the payload is not one. */
fun parseAnswer(payload: String): AnswerMessage? =
    runCatching { CastJson.decodeFromString<AnswerMessage>(payload) }
        .getOrNull()
        ?.takeIf { it.type == "ANSWER" }
