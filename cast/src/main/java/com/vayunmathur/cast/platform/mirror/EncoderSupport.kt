package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.vayunmathur.cast.protocol.CodecLimits
import com.vayunmathur.cast.protocol.CodecNegotiation
import com.vayunmathur.cast.protocol.VideoCodec

private const val TAG = "EncoderSupport"

/**
 * What this device can actually encode.
 *
 * Deliberately **not** built on `camera/.../CodecSupport.kt`: that asks CameraX what the camera
 * pipeline supports, which says nothing about a `MediaCodec` fed by a `VirtualDisplay`. The two
 * questions only look alike.
 *
 * Both answers gate real behaviour rather than being informational: without a hardware surface
 * encoder for H.265 or AV1 there is nothing to mirror, and `audio/opus` encoders are genuinely absent
 * on some devices, which the UI has to be able to explain.
 *
 * **Hardware is a requirement, not a preference.** This used to take the first `MediaCodecList` match
 * for one MIME type, which worked only because every device has a hardware H.264 encoder listed
 * first. With AV1 in the picture that assumption breaks: `c2.android.av1.encoder` is a software codec
 * that publishes a very large envelope, so trusting list order would advertise 1344x2992 realtime
 * from something that cannot do it - and the negotiation would believe it.
 */
object EncoderSupport {

    const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

    fun audioEncoderName(): String? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(AUDIO_MIME, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(AUDIO_MIME) }.isSuccess
        }?.name
    }

    /**
     * Every codec this phone has a hardware surface encoder for, as the envelope that encoder
     * advertises.
     *
     * This is one half of what [CodecNegotiation.choose] intersects; the other comes off the wire.
     * An empty list means this device cannot mirror at all, which is a named refusal rather than a
     * fallback - there is no H.264 to drop back to any more.
     */
    fun videoCodecs(): List<CodecLimits> = CodecNegotiation.PREFERENCE.mapNotNull { codec ->
        val info = hardwareSurfaceEncoder(codec) ?: run {
            Log.i(TAG, "no hardware surface encoder for ${codec.label} (${codec.mimeType})")
            return@mapNotNull null
        }
        val video = info.videoCapabilities(codec) ?: return@mapNotNull null
        val limits = runCatching {
            CodecLimits(
                codec = codec,
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                maxFrameRate = video.supportedFrameRates.upper.toInt(),
                maxBitRate = video.bitrateRange.upper,
            )
        }.getOrNull() ?: return@mapNotNull null
        Log.i(
            TAG,
            "${codec.label} encodes up to ${limits.maxWidth}x${limits.maxHeight} @ " +
                "${limits.maxFrameRate}fps, ${limits.maxBitRate / 1_000_000.0} Mbit/s on ${info.name}",
        )
        limits
    }

    /**
     * The hardware encoder [codec] will be instantiated on.
     *
     * `COLOR_FormatSurface` is the requirement that matters. An encoder that only accepts byte
     * buffers would mean reading pixels back from the display and pushing them in by hand, which is
     * both slow and pointless when `createInputSurface()` exists.
     */
    fun videoEncoderName(codec: VideoCodec): String? = hardwareSurfaceEncoder(codec)?.name

    /**
     * CBR if this encoder advertises it, VBR otherwise.
     *
     * CBR is what makes a screen encoder spend the bitrate it was given - left on VBR, this one
     * answered a 12 Mbit/s target with a measured 4.18 Mbit/s. It is asked for rather than assumed
     * because `configure()` throws on a mode the encoder does not support, and no picture at all
     * would be a worse outcome than a soft one.
     */
    fun videoBitrateMode(codec: VideoCodec, name: String): Int {
        val supported = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .first { it.name == name }
                .getCapabilitiesForType(codec.mimeType)
                .encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }.getOrNull() == true
        return if (supported) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
    }

    /**
     * Reduce [width] x [height] to something [codec]'s encoder will accept **at [frameRate]**.
     *
     * Native phone resolutions are not always encodable: `MediaCodec` advertises a maximum size, an
     * alignment requirement, and a supported-performance envelope, and a portrait 1344x2992 sits
     * outside what some encoders will take even though the same pixel count in landscape is fine.
     * Rather than let `configure()` throw, the aspect ratio is preserved and the size stepped down
     * until the codec agrees.
     *
     * **The rate is part of the question, and used not to be.** This asked `isSizeSupported`, which
     * answers only whether the frame fits the envelope - a 4-megapixel portrait frame an encoder will
     * take at 15 fps passes it. Nothing then held the encoder to the 30 fps the handshake had already
     * advertised, so it under-delivered in silence: measured at 11 fps against a negotiated 30. Frame
     * rate is the floor here and resolution is what yields to it.
     *
     * Returns the input unchanged when there is no encoder to ask, so the caller's own failure
     * handling stays the single place that deals with "this device cannot encode".
     */
    fun clampToEncoder(codec: VideoCodec, width: Int, height: Int, frameRate: Int): Pair<Int, Int> {
        val info = hardwareSurfaceEncoder(codec) ?: return width to height
        val video = info.videoCapabilities(codec) ?: return width to height

        // **Stepped down, not transposed.** A previous version of this comment claimed it would
        // "believe the transposed limits instead" when an encoder reports only a landscape envelope,
        // which the code never did - and could not: transposing the limits would mean sending a
        // rotated picture, since nothing between here and the TV turns the frame back. Portrait
        // 1344x2992 against a landscape-only envelope therefore rides the ladder down to something
        // that fits, which costs resolution and is logged rather than silent.
        if (video.realtime(width, height, frameRate)) return width to height
        val ceiling = runCatching {
            video.getSupportedFrameRatesFor(width, height).upper
        }.getOrNull()
        var scale = 1.0
        repeat(MAX_CLAMP_STEPS) {
            scale *= CLAMP_STEP
            val w = (width * scale).toInt().alignedDown(video.widthAlignment)
            val h = (height * scale).toInt().alignedDown(video.heightAlignment)
            if (w <= 0 || h <= 0) return width to height
            if (video.realtime(w, h, frameRate)) {
                Log.i(
                    TAG,
                    "${width}x$height tops out at ${ceiling ?: "an unstated rate"}fps on " +
                        "${info.name}; stepping down to ${w}x$h to hold ${frameRate}fps",
                )
                return w to h
            }
        }
        // Nothing in the ladder satisfied both. The caller's size is returned rather than a guess,
        // and this line is the only warning that the advertised frame rate is now aspirational.
        Log.w(
            TAG,
            "no size with this aspect ratio does ${frameRate}fps on ${info.name}; " +
                "sending ${width}x$height, which tops out at ${ceiling ?: "an unstated rate"}fps",
        )
        return width to height
    }

    /**
     * Whether this encoder will do [width] x [height] at [frameRate] *in realtime*.
     *
     * **Performance points where they exist, and `areSizeAndRateSupported` only as a fallback.** The
     * size-and-rate answer is derived from a macroblock-throughput figure, which software codecs
     * publish generously - it is exactly why a software AV1 encoder would say yes to 1344x2992@30.
     * `getSupportedPerformancePoints` is the platform's own statement of what it can sustain, and it
     * compares orientation-independently, so a landscape-only list still covers a portrait frame.
     *
     * A null list is "this codec did not say", which is treated as allowed - the hardware filter has
     * already run by the time anything asks, so the remaining risk is a hardware encoder being
     * refused a size it would in fact have taken.
     */
    private fun MediaCodecInfo.VideoCapabilities.realtime(
        width: Int,
        height: Int,
        frameRate: Int,
    ): Boolean = runCatching {
        val points = supportedPerformancePoints
        if (points.isNullOrEmpty()) {
            areSizeAndRateSupported(width, height, frameRate.toDouble())
        } else {
            val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, frameRate)
            points.any { it.covers(wanted) }
        }
    }.getOrDefault(false)

    private fun Int.alignedDown(alignment: Int): Int =
        if (alignment <= 1) this / 2 * 2 else this / alignment * alignment

    private const val CLAMP_STEP = 0.9
    private const val MAX_CLAMP_STEPS = 12

    private fun MediaCodecInfo.videoCapabilities(
        codec: VideoCodec,
    ): MediaCodecInfo.VideoCapabilities? = runCatching {
        getCapabilitiesForType(codec.mimeType).videoCapabilities
    }.getOrNull()

    /**
     * The hardware surface encoder for [codec], or null.
     *
     * **Filtered explicitly and then ranked, rather than taking the first match.** `REGULAR_CODECS`
     * so the list matches what an ordinary app may instantiate; `isHardwareAccelerated` and
     * `!isSoftwareOnly` because a software encoder's advertised envelope is not a promise it can keep
     * at frame rate; and the largest advertised frame wins among what is left, so nothing here
     * depends on the order the platform happens to list its codecs in.
     */
    private fun hardwareSurfaceEncoder(codec: VideoCodec): MediaCodecInfo? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.filter { info ->
            info.isEncoder &&
                info.isHardwareAccelerated &&
                !info.isSoftwareOnly &&
                info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) } &&
                runCatching {
                    val caps = info.getCapabilitiesForType(codec.mimeType)
                    caps.videoCapabilities != null &&
                        caps.colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                }.getOrDefault(false)
        }.maxByOrNull { info ->
            val video = info.videoCapabilities(codec)
            if (video == null) {
                0L
            } else {
                video.supportedWidths.upper.toLong() * video.supportedHeights.upper
            }
        }
    }
}
