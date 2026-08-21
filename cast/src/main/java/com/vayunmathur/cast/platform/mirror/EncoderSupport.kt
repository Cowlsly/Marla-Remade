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
