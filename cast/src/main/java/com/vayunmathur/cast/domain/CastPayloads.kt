package com.vayunmathur.cast.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The CastV2 namespaces this app deals in.
 *
 * A frame's namespace is what decides how its JSON payload is read, so these are the whole
 * routing table. There is no registration or discovery step: every receiver implements all of
 * them. Only the first three are spoken today; [WEBRTC] is where mirroring gets negotiated.
 */
object CastNamespaces {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"

    /**
     * OFFER/ANSWER - where mirroring is negotiated.
     *
     * openscreen `cast/streaming/message_fields.h`, `kCastWebrtcNamespace`. The name is
     * historical; nothing about it is WebRTC.
     */
    const val WEBRTC = "urn:x-cast:com.google.cast.webrtc"
}

/**
 * The Cast Streaming receiver app ids, which are what mirroring launches.
 *
 * Four exist - openscreen names a desktop/Chrome pair and an Android pair - but only the Android
 * pair is used here, because that is what Phase 0 proved works from an unregistered sender on
 * real hardware. **Which one is picked is a correctness boundary, not a preference:** a mismatched
 * pair is refused at LAUNCH, with `NOT_FOUND` for audio-only against a TV and `SYSTEM_ERROR` for
 * A/V against a speaker.
 *
 * openscreen `cast/common/public/cast_streaming_app_ids.h`.
 */
object MirroringAppIds {
    /** `GetAndroidMirroringAudioVideoAppId()`. For a device with a screen. */
    const val AUDIO_VIDEO = "674A0243"

    /** `GetAndroidMirroringAudioOnlyAppId()`. For a speaker or a speaker group. */
    const val AUDIO_ONLY = "8E6C866D"

    fun forKind(kind: CastDeviceKind): String =
        if (kind == CastDeviceKind.Tv) AUDIO_VIDEO else AUDIO_ONLY
}

/** Every sender is `sender-0`; the platform receiver is always `receiver-0`. */
const val SENDER_ID = "sender-0"
const val RECEIVER_ID = "receiver-0"

/** The receiver's TLS port. Not configurable - it is fixed by the protocol. */
const val CAST_PORT = 8009

/**
 * A payload plus where it goes.
 *
 * [CastSession] deals in these rather than in `CastMessage` so it stays free of the
 * transport: the source id is always [SENDER_ID] and the codec fills it in.
 */
data class CastFrame(
    val namespace: String,
    val destinationId: String,
    val payload: String,
)

/**
 * Lenient on the way in, complete on the way out.
 *
 * `ignoreUnknownKeys` is not optional: receiver status carries a large, versioned object graph
 * and a firmware update adding a field must not break a session. `encodeDefaults` is on because
 * a request's `type` is a defaulted constant and the receiver rejects a frame without it, and
 * `explicitNulls` is off so an absent optional stays absent rather than becoming `null` -
 * `SET_VOLUME` with `"muted": null` is an error.
 */
val CastJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Just enough of any payload to route it. Every CastV2 payload has a `type`. */
@Serializable
data class CastEnvelope(
    val type: String = "",
    val requestId: Int? = null,
    /** Present on LAUNCH_ERROR, e.g. `NOT_FOUND` or `SYSTEM_ERROR`. */
    val reason: String? = null,
)

@Serializable
data class CastVolume(
    val level: Double? = null,
    val muted: Boolean? = null,
)

@Serializable
data class ReceiverApplication(
    val appId: String = "",
    val displayName: String? = null,
    val sessionId: String = "",
    val statusText: String? = null,
    /**
     * The id to address the *running app* at, which is not [RECEIVER_ID].
     *
     * For the mirroring receivers this is a UUID and happens to equal [sessionId], where the
     * media receiver used `web-N` - so nothing may assume either shape. A frame sent anywhere
     * else is silently dropped.
     */
    val transportId: String = "",
    val isIdleScreen: Boolean = false,
)

@Serializable
data class ReceiverStatus(
    val applications: List<ReceiverApplication> = emptyList(),
    val volume: CastVolume? = null,
)

@Serializable
data class ReceiverStatusMessage(
    val type: String = "",
    val requestId: Int? = null,
    val status: ReceiverStatus = ReceiverStatus(),
)

/** `CONNECT`, `CLOSE`, `PING`, `PONG` - the payloads with nothing in them but a type. */
@Serializable
data class CastSimpleMessage(val type: String)

@Serializable
data class LaunchRequest(
    val requestId: Int,
    val appId: String,
    val type: String = "LAUNCH",
)

@Serializable
data class StopSessionRequest(
    val requestId: Int,
    val sessionId: String,
    val type: String = "STOP",
)

@Serializable
data class SetVolumeRequest(
    val requestId: Int,
    val volume: CastVolume,
    val type: String = "SET_VOLUME",
)
