package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * What this device can actually encode.
 *
 * Deliberately **not** built on `camera/.../CodecSupport.kt`: that asks CameraX what the camera
 * pipeline supports, which says nothing about a `MediaCodec` fed by a `VirtualDisplay`. The two
 * questions only look alike.
 *
 * Both answers gate real behaviour rather than being informational: without an H.264 surface
 * encoder there is nothing to mirror, and `audio/opus` encoders are genuinely absent on some
 * devices, which the UI has to be able to explain.
 */
object EncoderSupport {

    const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

    /**
     * An H.264 encoder that can take frames from a `Surface`.
     *
     * `COLOR_FormatSurface` is the requirement that matters. An encoder that only accepts byte
     * buffers would mean reading pixels back from the display and pushing them in by hand, which is
     * both slow and pointless when `createInputSurface()` exists.
     */
    fun videoEncoderName(): String? = findEncoder(VIDEO_MIME) { caps ->
        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }

    fun audioEncoderName(): String? = findEncoder(AUDIO_MIME) { true }

    /**
     * Reduce [width] x [height] to something the H.264 encoder will actually accept.
     *
     * Native phone resolutions are not always encodable: `MediaCodec` advertises a maximum size, an
     * alignment requirement, and a supported-performance envelope, and a portrait 1344x2992 sits
     * outside what some encoders will take even though the same pixel count in landscape is fine.
     * Rather than let `configure()` throw, the aspect ratio is preserved and the size stepped down
     * until the codec agrees.
     *
     * Returns the input unchanged when there is no encoder to ask, so the caller's own failure
     * handling stays the single place that deals with "this device cannot encode".
     */
    fun clampToEncoder(width: Int, height: Int): Pair<Int, Int> {
        val name = videoEncoderName() ?: return width to height
        val video = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .first { it.name == name }
                .getCapabilitiesForType(VIDEO_MIME)
                .videoCapabilities
        }.getOrNull() ?: return width to height

        // Some encoders only report a landscape envelope, so try the orientation as given and, if
        // that is refused, believe the transposed limits instead.
        if (runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)) {
            return width to height
        }
        var scale = 1.0
        repeat(MAX_CLAMP_STEPS) {
            scale *= CLAMP_STEP
            val w = (width * scale).toInt().alignedDown(video.widthAlignment)
            val h = (height * scale).toInt().alignedDown(video.heightAlignment)
            if (w <= 0 || h <= 0) return width to height
            if (runCatching { video.isSizeSupported(w, h) }.getOrDefault(false)) return w to h
        }
        return width to height
    }

    private fun Int.alignedDown(alignment: Int): Int =
        if (alignment <= 1) this / 2 * 2 else this / alignment * alignment

    private const val CLAMP_STEP = 0.9
    private const val MAX_CLAMP_STEPS = 12

    /** REGULAR_CODECS, so the list matches what an ordinary app is allowed to instantiate. */
    private inline fun findEncoder(
        mime: String,
        predicate: (MediaCodecInfo.CodecCapabilities) -> Boolean,
    ): String? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in codecs) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (predicate(caps)) return info.name
        }
        return null
    }
}
