package com.vayunmathur.cast.domain

/** How far along the CONNECT -> LAUNCH -> join -> LOAD sequence the session is. */
enum class CastPhase {
    /** No channel, or the receiver closed it. */
    Idle,

    /** CONNECT and LAUNCH are on the wire; waiting for a RECEIVER_STATUS naming the app. */
    Launching,

    /** The app is running and joined: [CastSessionState.transportId] is addressable. */
    Ready,

    /** The receiver refused, and [CastSessionState.failure] says why. */
    Failed,
}

/** The receiver's own player states, verbatim from the media namespace. */
enum class CastPlayerState {
    Idle, Buffering, Playing, Paused;

    companion object {
        fun fromWire(value: String): CastPlayerState = when (value) {
            "BUFFERING" -> Buffering
            "PLAYING" -> Playing
            "PAUSED" -> Paused
            else -> Idle
        }
    }
}

/**
 * Everything the session knows, as one value.
 *
 * [durationSec] and [title] survive a status that omits them: the receiver includes the
 * `media` object only in the first MEDIA_STATUS after a LOAD and leaves it out of the
 * periodic ones, so treating absent as unknown would blank the title a second into playback.
 */
data class CastSessionState(
    val phase: CastPhase = CastPhase.Idle,
    val sessionId: String? = null,
    val transportId: String? = null,
    val mediaSessionId: Int? = null,
    val playerState: CastPlayerState = CastPlayerState.Idle,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double? = null,
    val title: String? = null,
    val volumeLevel: Double = 1.0,
    val muted: Boolean = false,
    val failure: String? = null,
)

/**
 * THROWAWAY (Phase 0): the audio half of the spike's OFFER, hand-transcribed.
 *
 * Field names, the `1/48000` timebase string form and the `rtpProfile` constant come from
 * openscreen `cast/streaming/public/offer_messages.cc` (`Stream::ToJson`, `AudioStream::ToJson`);
 * `rtpPayloadType` 127 is `RtpPayloadType::kAudioHackForAndroidTV` from
 * `cast/streaming/impl/rtp_defines.h`, which is what a real sender puts on the wire because
 * openscreen's `use_android_rtp_hack` defaults to true and Chrome never overrides it.
 * `channels` 2 and `bitRate` 128000 are Chrome's own values from
 * `components/mirroring/service/mirror_settings.cc`. The SSRC is in the high-priority range
 * (1..50000, `cast/streaming/ssrc.cc`).
 *
 * The keys are a fixed literal on purpose: nothing is encrypted during the spike, and a
 * hardcoded key cannot be mistaken for a working crypto path later.
 */
private const val SPIKE_AUDIO_STREAM = """
    {"index":0,"type":"audio_source","codecName":"opus","rtpProfile":"cast",
     "rtpPayloadType":127,"ssrc":20001,"channels":2,"bitRate":128000,
     "targetDelay":400,"timeBase":"1/48000","receiverRtcpEventLog":true,
     "aesKey":"51027e4e2347cbcb49d57ef10177aebc",
     "aesIvMask":"7f12a19be62a36c04ae4116caaeff6d1"}
"""

/**
 * THROWAWAY (Phase 0): the video half, from `VideoStream::ToJson` in the same file.
 *
 * `rtpPayloadType` 96 is `kVideoHackForAndroidTV`, which collides with `kAudioOpus` by design -
 * the receiver disambiguates by index, not by payload type. `maxFrameRate` is a rational
 * *string*, not a number. The SSRC is in the normal-priority range (50001..100000). 720p and
 * 5 Mbit/s are Chrome's starting values; the spike is not measuring picture quality.
 */
private const val SPIKE_VIDEO_STREAM = """
    {"index":1,"type":"video_source","codecName":"h264","rtpProfile":"cast",
     "rtpPayloadType":96,"ssrc":50001,"channels":1,"maxFrameRate":"30000/1000",
     "maxBitRate":5000000,"targetDelay":400,"timeBase":"1/90000",
     "receiverRtcpEventLog":true,"resolutions":[{"width":1280,"height":720}],
     "aesKey":"040d756791711fd3adb939066e6d8690",
     "aesIvMask":"9ff0f022a959150e70a2d05a6c184aed"}
"""

/**
 * The CastV2 sequencing rules, with no I/O.
 *
 * Every method returns the frames to put on the wire and mutates [state]; nothing here
 * touches a socket, a Context or a coroutine, which is what makes the ordering testable on
 * the JVM. The ordering is the part that is easy to get wrong:
 *
 *  1. CONNECT to [RECEIVER_ID], then LAUNCH the app id.
 *  2. The RECEIVER_STATUS that comes back names a `sessionId` **and** a `transportId`.
 *  3. CONNECT again, to that `transportId` - a media command sent before this is dropped
 *     without an error.
 *  4. Only then LOAD.
 *
 * A [load] issued before step 3 is therefore held and replayed rather than rejected, which
 * is what lets the UI treat "pick a device" and "pick a file" as independent.
 *
 * `requestId` is a monotonic counter starting at 1 (zero means "no response wanted" and
 * would lose the correlation), and every response echoes it.
 *
 * [appId] is what gets launched and what a RECEIVER_STATUS is matched against. It is a
 * parameter rather than a constant because mirroring launches a different receiver on the same
 * control plane - the CONNECT -> LAUNCH -> join sequence is identical either way.
 */
class CastSession(private val appId: String = DEFAULT_MEDIA_RECEIVER_APP_ID) {

    var state: CastSessionState = CastSessionState()
        private set

    /**
     * THROWAWAY (Phase 0): every `webrtc` payload, verbatim, for the spike's log.
     *
     * A callback rather than a `Log` call because this class has no Android imports and is not
     * going to acquire one for a spike.
     */
    var onWebrtcPayload: ((String) -> Unit)? = null

    private var nextRequestId = 1

    /** A LOAD asked for before [CastPhase.Ready]; replayed once the app is joined. */
    private var pendingLoad: CastMediaInformation? = null

    fun allocateRequestId(): Int = nextRequestId++

    /** CONNECT to the platform receiver and launch the media receiver app. */
    fun open(): List<CastFrame> {
        state = CastSessionState(phase = CastPhase.Launching)
        return listOf(
            frame(CastNamespaces.CONNECTION, RECEIVER_ID, CastSimpleMessage("CONNECT")),
            frame(CastNamespaces.RECEIVER, RECEIVER_ID, LaunchRequest(allocateRequestId(), appId)),
        )
    }

    /**
     * The keepalive. The receiver drops a channel that has been quiet for about 10 seconds,
     * so this has to be sent on a timer whether or not anything is playing.
     */
    fun heartbeat(): CastFrame =
        frame(CastNamespaces.HEARTBEAT, RECEIVER_ID, CastSimpleMessage("PING"))

    /**
     * Feed one received frame in; get the replies to send back out.
     *
     * Anything unrecognised is ignored rather than treated as an error - receivers send
     * status types and namespaces this app has no interest in.
     */
    fun onMessage(namespace: String, payload: String): List<CastFrame> {
        val envelope = runCatching { CastJson.decodeFromString<CastEnvelope>(payload) }.getOrNull()
            ?: return emptyList()
        return when (namespace) {
            CastNamespaces.HEARTBEAT ->
                if (envelope.type == "PING") {
                    listOf(frame(CastNamespaces.HEARTBEAT, RECEIVER_ID, CastSimpleMessage("PONG")))
                } else {
                    emptyList()
                }
            CastNamespaces.CONNECTION -> {
                if (envelope.type == "CLOSE") onClosed()
                emptyList()
            }
            CastNamespaces.RECEIVER -> onReceiverMessage(envelope, payload)
            CastNamespaces.WEBRTC -> {
                onWebrtcPayload?.invoke(payload)
                emptyList()
            }
            CastNamespaces.MEDIA -> {
                onMediaMessage(envelope, payload)
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Load a URL into the running app, or remember it until there is one.
     *
     * Returns the frames to send, which is empty when the request was held.
     */
    fun load(media: CastMediaInformation): List<CastFrame> {
        state = state.copy(
            failure = null,
            title = media.metadata?.title,
            durationSec = media.duration,
            currentTimeSec = 0.0,
            playerState = CastPlayerState.Buffering,
        )
        val sessionId = state.sessionId
        val transportId = state.transportId
        if (state.phase != CastPhase.Ready || sessionId == null || transportId == null) {
            pendingLoad = media
            return emptyList()
        }
        pendingLoad = null
        return listOf(
            frame(
                CastNamespaces.MEDIA,
                transportId,
                LoadRequest(allocateRequestId(), sessionId, media),
            ),
        )
    }

    /**
     * THROWAWAY (Phase 0): put one hand-written OFFER on the `webrtc` namespace.
     *
     * The envelope is `{"type":"OFFER","seqNum":N,"offer":{...}}` per openscreen
     * `cast/streaming/sender_message.cc`, and `castMode` is `"mirroring"` per
     * `CreateMirroringOffer` in `cast/streaming/public/sender_session.cc`. That same function is
     * why there is no GET_CAPABILITIES first: openscreen sends one only from
     * `NegotiateRemoting`, never from the mirroring path.
     *
     * `seqNum` shares the `requestId` counter. They are separate sequences in the protocol, but
     * one monotonic source cannot collide with itself and the spike only ever sends one OFFER.
     */
    fun sendSpikeOffer(audioOnly: Boolean): List<CastFrame> {
        val transportId = state.transportId ?: return emptyList()
        val streams = if (audioOnly) {
            SPIKE_AUDIO_STREAM
        } else {
            "$SPIKE_AUDIO_STREAM,$SPIKE_VIDEO_STREAM"
        }
        val payload =
            """{"type":"OFFER","seqNum":${allocateRequestId()},""" +
                """"offer":{"castMode":"mirroring","supportedStreams":[$streams]}}"""
        return listOf(CastFrame(CastNamespaces.WEBRTC, transportId, payload))
    }

    fun play(): List<CastFrame> = mediaCommand("PLAY")

    fun pause(): List<CastFrame> = mediaCommand("PAUSE")

    /** Stops playback but leaves the app running, so another LOAD needs no relaunch. */
    fun stopMedia(): List<CastFrame> = mediaCommand("STOP")

    fun refreshMediaStatus(): List<CastFrame> = mediaCommand("GET_STATUS")

    fun seek(positionSec: Double): List<CastFrame> {
        val transportId = state.transportId ?: return emptyList()
        val mediaSessionId = state.mediaSessionId ?: return emptyList()
        // Optimistic: the receiver's next MEDIA_STATUS corrects it, and without this the
        // progress bar snaps back to the old position for as long as the round trip takes.
        state = state.copy(currentTimeSec = positionSec)
        return listOf(
            frame(
                CastNamespaces.MEDIA,
                transportId,
                SeekRequest(allocateRequestId(), mediaSessionId, positionSec),
            ),
        )
    }

    /**
     * Volume is the *receiver's* volume, not the media's, so it goes to [RECEIVER_ID] on the
     * receiver namespace even while media is playing.
     */
    fun setVolume(level: Double): List<CastFrame> {
        val clamped = level.coerceIn(0.0, 1.0)
        state = state.copy(volumeLevel = clamped)
        return listOf(
            frame(
                CastNamespaces.RECEIVER,
                RECEIVER_ID,
                SetVolumeRequest(allocateRequestId(), CastVolume(level = clamped)),
            ),
        )
    }

    fun setMuted(muted: Boolean): List<CastFrame> {
        state = state.copy(muted = muted)
        return listOf(
            frame(
                CastNamespaces.RECEIVER,
                RECEIVER_ID,
                SetVolumeRequest(allocateRequestId(), CastVolume(muted = muted)),
            ),
        )
    }

    /**
     * Tear the session down: stop the receiver app, then CLOSE the channel.
     *
     * Stopping the app is what returns the TV to its backdrop; closing without it leaves the
     * media receiver on screen with the last frame frozen.
     */
    fun close(): List<CastFrame> {
        val frames = buildList {
            state.sessionId?.let {
                add(
                    frame(
                        CastNamespaces.RECEIVER,
                        RECEIVER_ID,
                        StopSessionRequest(allocateRequestId(), it),
                    ),
                )
            }
            state.transportId?.let {
                add(frame(CastNamespaces.CONNECTION, it, CastSimpleMessage("CLOSE")))
            }
            add(frame(CastNamespaces.CONNECTION, RECEIVER_ID, CastSimpleMessage("CLOSE")))
        }
        onClosed()
        return frames
    }

    private fun onClosed() {
        pendingLoad = null
        state = CastSessionState(phase = CastPhase.Idle)
    }

    private fun onReceiverMessage(envelope: CastEnvelope, payload: String): List<CastFrame> {
        if (envelope.type == "LAUNCH_ERROR") {
            state = state.copy(
                phase = CastPhase.Failed,
                failure = envelope.reason ?: envelope.type,
            )
            return emptyList()
        }
        if (envelope.type != "RECEIVER_STATUS") return emptyList()
        val status = runCatching { CastJson.decodeFromString<ReceiverStatusMessage>(payload) }
            .getOrNull()?.status ?: return emptyList()
        status.volume?.let { volume ->
            state = state.copy(
                volumeLevel = volume.level ?: state.volumeLevel,
                muted = volume.muted ?: state.muted,
            )
        }
        val app = status.applications.firstOrNull {
            it.appId == appId && it.transportId.isNotEmpty()
        }
        if (app == null) {
            // The app is gone - either someone else took the receiver over or STOP landed.
            // Not a failure, but there is nothing left to send media to.
            if (state.phase == CastPhase.Ready) {
                state = state.copy(
                    phase = CastPhase.Idle,
                    sessionId = null,
                    transportId = null,
                    mediaSessionId = null,
                    playerState = CastPlayerState.Idle,
                )
            }
            return emptyList()
        }
        // A relaunch produces a new transportId, so join on change rather than on phase:
        // reusing the old one is how a session ends up connected to an app that has exited.
        if (state.phase == CastPhase.Ready && state.transportId == app.transportId) {
            return emptyList()
        }
        state = state.copy(
            phase = CastPhase.Ready,
            sessionId = app.sessionId,
            transportId = app.transportId,
            failure = null,
        )
        return buildList {
            add(frame(CastNamespaces.CONNECTION, app.transportId, CastSimpleMessage("CONNECT")))
            pendingLoad?.let { addAll(load(it)) }
        }
    }

    private fun onMediaMessage(envelope: CastEnvelope, payload: String) {
        if (envelope.type == "LOAD_FAILED" || envelope.type == "LOAD_CANCELLED") {
            state = state.copy(
                playerState = CastPlayerState.Idle,
                failure = envelope.reason ?: envelope.type,
            )
            return
        }
        if (envelope.type != "MEDIA_STATUS") return
        val statuses = runCatching { CastJson.decodeFromString<CastMediaStatusMessage>(payload) }
            .getOrNull()?.status ?: return
        val status = statuses.firstOrNull()
        if (status == null) {
            // An empty status array is how the receiver reports "nothing loaded" after STOP.
            state = state.copy(
                mediaSessionId = null,
                playerState = CastPlayerState.Idle,
                currentTimeSec = 0.0,
                durationSec = null,
                title = null,
            )
            return
        }
        state = state.copy(
            mediaSessionId = status.mediaSessionId,
            playerState = CastPlayerState.fromWire(status.playerState),
            currentTimeSec = status.currentTime,
            durationSec = status.media?.duration ?: state.durationSec,
            title = status.media?.metadata?.title ?: state.title,
        )
    }

    private fun mediaCommand(type: String): List<CastFrame> {
        val transportId = state.transportId ?: return emptyList()
        val mediaSessionId = state.mediaSessionId ?: return emptyList()
        return listOf(
            frame(
                CastNamespaces.MEDIA,
                transportId,
                MediaCommand(allocateRequestId(), mediaSessionId, type),
            ),
        )
    }

    private inline fun <reified T> frame(
        namespace: String,
        destinationId: String,
        payload: T,
    ): CastFrame = CastFrame(namespace, destinationId, CastJson.encodeToString(payload))
}
