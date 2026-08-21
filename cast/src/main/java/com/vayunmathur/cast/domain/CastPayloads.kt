package com.vayunmathur.cast.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The CastV2 namespaces this app speaks.
 *
 * A frame's namespace is what decides how its JSON payload is read, so these are the whole
 * routing table. There is no registration or discovery step: every receiver implements the
 * first four, and the Default Media Receiver's media namespace is the public one.
 */
object CastNamespaces {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"

    /**
     * OFFER/ANSWER - the Cast Streaming control plane, which is where mirroring is negotiated
     * instead of on [MEDIA].
     *
     * openscreen `cast/streaming/message_fields.h`, `kCastWebrtcNamespace`. The name is
     * historical; nothing about it is WebRTC.
     */
    const val WEBRTC = "urn:x-cast:com.google.cast.webrtc"
}

/**
 * The Default Media Receiver.
 *
 * The one app id a sender can launch without registering as a Google Cast developer, which
 * is the entire reason this app is buildable. It plays a media URL and nothing else - screen
 * mirroring uses [MirroringAppIds] instead.
 */
const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

/**
 * The Cast Streaming receiver app ids, which are what mirroring has to launch.
 *
 * Four, not two: openscreen names a desktop/Chrome pair *and* an Android pair, and there is
 * no documentation saying which of them will accept an unregistered sender. Deciding that is
 * the entire job of the Phase 0 spike, so all four are listed and all four get tried.
 *
 * openscreen `cast/common/public/cast_streaming_app_ids.h`.
 */
object MirroringAppIds {
    /** `GetCastStreamingAudioVideoAppId()` - what desktop Chrome mirrors with. */
    const val AUDIO_VIDEO = "0F5096E8"

    /** `GetCastStreamingAudioOnlyAppId()`. */
    const val AUDIO_ONLY = "85CDB22F"

    /** `GetAndroidMirroringAudioVideoAppId()`. */
    const val ANDROID_AUDIO_VIDEO = "674A0243"

    /** `GetAndroidMirroringAudioOnlyAppId()`. */
    const val ANDROID_AUDIO_ONLY = "8E6C866D"

    val all: List<String> =
        listOf(AUDIO_VIDEO, AUDIO_ONLY, ANDROID_AUDIO_VIDEO, ANDROID_AUDIO_ONLY)

    /** An audio-only receiver is offered no video stream; a speaker has nothing to draw on. */
    fun isAudioOnly(appId: String): Boolean = appId == AUDIO_ONLY || appId == ANDROID_AUDIO_ONLY
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
 * `ignoreUnknownKeys` is not optional: receiver and media status carry a large, versioned
 * object graph and a firmware update adding a field must not break playback.
 * `encodeDefaults` is on because a request's `type` is a defaulted constant and the receiver
 * rejects a frame without it, and `explicitNulls` is off so an absent optional stays absent
 * rather than becoming `null` - `SET_VOLUME` with `"muted": null` is an error.
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
    /** Present on LAUNCH_ERROR / LOAD_FAILED, e.g. `CANCELLED` or `NOT_AVAILABLE`. */
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
     * The id to address the *running app* at, which is not [sessionId] and not
     * [RECEIVER_ID]. Media commands sent anywhere else are silently dropped.
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

@Serializable
data class CastMediaMetadata(
    /** 0 = GENERIC, which is all the Default Media Receiver needs to draw a title. */
    val metadataType: Int = 0,
    val title: String? = null,
    val subtitle: String? = null,
)

@Serializable
data class CastMediaInformation(
    /** The URL the receiver fetches. Local files are served by `MediaFileServer`. */
    val contentId: String = "",
    val contentType: String = "",
    /** BUFFERED for a seekable file, LIVE for a stream with no known end. */
    val streamType: String = "BUFFERED",
    val duration: Double? = null,
    val metadata: CastMediaMetadata? = null,
)

@Serializable
data class CastMediaStatus(
    val mediaSessionId: Int = 0,
    /** IDLE, BUFFERING, PLAYING or PAUSED. */
    val playerState: String = "IDLE",
    val currentTime: Double = 0.0,
    val media: CastMediaInformation? = null,
    val volume: CastVolume? = null,
    val idleReason: String? = null,
)

@Serializable
data class CastMediaStatusMessage(
    val type: String = "",
    val requestId: Int? = null,
    /** Empty when the receiver has no media loaded, which is how STOP is reported. */
    val status: List<CastMediaStatus> = emptyList(),
)

/** `CONNECT`, `CLOSE`, `PING`, `PONG` - the payloads with nothing in them but a type. */
@Serializable
data class CastSimpleMessage(val type: String)

/** `GET_STATUS` on the receiver namespace, and anything else that is type + requestId. */
@Serializable
data class CastRequest(val type: String, val requestId: Int)

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

@Serializable
data class LoadRequest(
    val requestId: Int,
    val sessionId: String,
    val media: CastMediaInformation,
    val autoplay: Boolean = true,
    val currentTime: Double = 0.0,
    val type: String = "LOAD",
)

/** `PLAY`, `PAUSE`, `STOP` and `GET_STATUS` on the media namespace. */
@Serializable
data class MediaCommand(
    val requestId: Int,
    val mediaSessionId: Int,
    val type: String,
)

@Serializable
data class SeekRequest(
    val requestId: Int,
    val mediaSessionId: Int,
    val currentTime: Double,
    val type: String = "SEEK",
)
