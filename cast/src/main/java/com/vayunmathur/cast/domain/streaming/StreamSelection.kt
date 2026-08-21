package com.vayunmathur.cast.domain.streaming

import com.vayunmathur.cast.domain.CastDeviceKind
import java.security.SecureRandom

/**
 * What to offer a given device, and the keys and identifiers to offer it with.
 *
 * This is where the audio-only requirement lives. It is a correctness boundary rather than a
 * preference: Phase 0 established on real hardware that a receiver refuses a mismatched app id at
 * LAUNCH, and that a speaker answers with the audio stream index alone whatever was offered.
 *
 * Pure, so all of it is testable on the JVM.
 */
object StreamSelection {

    /** `kDefaultTargetPlayoutDelay` - openscreen `cast/streaming/public/constants.h`. */
    const val TARGET_DELAY_MS = 400

    /** The spec fixes video at 90 kHz; `kRtpVideoTimebase` in the same file. */
    const val VIDEO_TIMEBASE = 90_000

    /** `kAudioTimebase` - Chrome's `components/mirroring/service/mirror_settings.cc`. */
    const val AUDIO_TIMEBASE = 48_000

    const val AUDIO_CHANNELS = 2
    const val AUDIO_BITRATE = 128_000

    /** `kStartVideoBitrate` from `mirror_settings.cc`. */
    const val VIDEO_MAX_BITRATE = 5_000_000

    const val VIDEO_MAX_FRAME_RATE = 30

    /**
     * `kAudioHackForAndroidTV` and `kVideoHackForAndroidTV` -
     * openscreen `cast/streaming/impl/rtp_defines.h`. See [OfferStream.rtpPayloadType].
     */
    const val AUDIO_PAYLOAD_TYPE = 127
    const val VIDEO_PAYLOAD_TYPE = 96

    /**
     * SSRC ranges from openscreen `cast/streaming/ssrc.cc`. Audio is "high priority" and video is
     * "normal"; the ranges are arbitrary but long-established, and `ComparePriority` is a plain
     * numeric comparison, so staying inside them is what makes audio win under contention.
     */
    private const val AUDIO_SSRC_MIN = 1
    private const val AUDIO_SSRC_MAX = 50_000
    private const val VIDEO_SSRC_MIN = 50_001
    private const val VIDEO_SSRC_MAX = 100_000

    /**
     * Whether a device can take video at all.
     *
     * From the mDNS capability bitmask, so it is known before anything is negotiated - that is
     * what lets the UI say "audio only" while the receiver is still being joined.
     */
    fun isAudioOnly(kind: CastDeviceKind): Boolean = kind != CastDeviceKind.Tv

    /**
     * Build the OFFER for [kind].
     *
     * Stream indexes are load-bearing: `CreateMirroringOffer` in openscreen's `sender_session.cc`
     * puts audio at `[0..N-1]` and video at `[N..K]`, and the ANSWER's `sendIndexes` refers back
     * to them. Audio is therefore always index 0 and video, when present, index 1.
     */
    fun offer(kind: CastDeviceKind, random: SecureRandom = SecureRandom()): StreamPlan {
        val audio = StreamKeys(random.bytes16(), random.bytes16())
        val audioSsrc = random.ssrc(AUDIO_SSRC_MIN, AUDIO_SSRC_MAX)
        val audioStream = OfferStream(
            index = AUDIO_INDEX,
            type = "audio_source",
            codecName = "opus",
            rtpPayloadType = AUDIO_PAYLOAD_TYPE,
            ssrc = audioSsrc,
            channels = AUDIO_CHANNELS,
            targetDelay = TARGET_DELAY_MS,
            aesKey = audio.key.toHex(),
            aesIvMask = audio.ivMask.toHex(),
            timeBase = "1/$AUDIO_TIMEBASE",
            bitRate = AUDIO_BITRATE,
        )
        if (isAudioOnly(kind)) {
            return StreamPlan(
                streams = listOf(audioStream),
                audioKeys = audio,
                audioSsrc = audioSsrc,
            )
        }
        val video = StreamKeys(random.bytes16(), random.bytes16())
        val videoSsrc = random.ssrc(VIDEO_SSRC_MIN, VIDEO_SSRC_MAX)
        val videoStream = OfferStream(
            index = VIDEO_INDEX,
            type = "video_source",
            codecName = "h264",
            rtpPayloadType = VIDEO_PAYLOAD_TYPE,
            ssrc = videoSsrc,
            channels = 1,
            targetDelay = TARGET_DELAY_MS,
            aesKey = video.key.toHex(),
            aesIvMask = video.ivMask.toHex(),
            timeBase = "1/$VIDEO_TIMEBASE",
            maxFrameRate = "${VIDEO_MAX_FRAME_RATE * 1000}/1000",
            maxBitRate = VIDEO_MAX_BITRATE,
            // Only an upper bound. The TV answered `scaling: "sender"` with a 4K display, so the
            // capture resolution is ours to choose and this is what we promise not to exceed.
            resolutions = listOf(Resolution(1280, 720)),
        )
        return StreamPlan(
            streams = listOf(audioStream, videoStream),
            audioKeys = audio,
            audioSsrc = audioSsrc,
            videoKeys = video,
            videoSsrc = videoSsrc,
        )
    }

    const val AUDIO_INDEX = 0
    const val VIDEO_INDEX = 1

    private fun SecureRandom.bytes16(): ByteArray = ByteArray(16).also { nextBytes(it) }

    private fun SecureRandom.ssrc(min: Int, max: Int): Long =
        (min + nextInt(max - min + 1)).toLong()
}

/** The AES-128 key and the IV mask for one stream. Per-stream, never shared. */
data class StreamKeys(val key: ByteArray, val ivMask: ByteArray) {
    // Arrays, so the generated equals/hashCode would compare identity. Content comparison is what
    // a test asserting a round-trip means.
    override fun equals(other: Any?): Boolean =
        other is StreamKeys && key.contentEquals(other.key) && ivMask.contentEquals(other.ivMask)

    override fun hashCode(): Int = 31 * key.contentHashCode() + ivMask.contentHashCode()
}

/** An OFFER plus the sender-side secrets it commits us to. */
data class StreamPlan(
    val streams: List<OfferStream>,
    val audioKeys: StreamKeys,
    val audioSsrc: Long,
    val videoKeys: StreamKeys? = null,
    val videoSsrc: Long? = null,
) {
    val hasVideo: Boolean get() = videoKeys != null

    fun message(seqNum: Int): OfferMessage = OfferMessage(seqNum, Offer(streams))
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
