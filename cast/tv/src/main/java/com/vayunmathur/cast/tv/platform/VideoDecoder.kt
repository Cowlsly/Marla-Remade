package com.vayunmathur.cast.tv.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.DecoderLimits

private const val TAG = "VideoDecoder"

private const val TIMEOUT_US = 10_000L

/**
 * H.264 access units in, pixels on a `Surface` out.
 *
 * The mirror of `:cast`'s `VideoEncoder`, and its `KEY_LOW_LATENCY` twin: without it a decoder is
 * entitled to buffer several frames before emitting the first, which for mirroring shows up as a
 * screen that lags the phone by a visible fraction of a second for no reason.
 *
 * **Parameter sets are expected in-band, at the front of the first key frame.** That is what the
 * sender does - it caches the codec-config buffer and prepends it to the first IDR - so this
 * configures without a `csd-0`. A decoder handed an IDR with no SPS/PPS produces nothing and says
 * nothing, which is exactly the failure mode this pairing exists to make impossible.
 */
class VideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null

    /** False when there is no usable decoder, which is not recoverable and has to be said out loud. */
    fun start(width: Int, height: Int): Boolean {
        val name = decoderName()
        if (name == null) {
            Log.w(TAG, "no $VIDEO_MIME decoder on this TV")
            return false
        }
        return try {
            val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                // Mirroring is interactive: every millisecond of decoder buffering is a millisecond
                // the picture lags the phone.
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            Log.i(TAG, "decoding ${width}x$height with $name")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the video decoder", e)
            release()
            false
        }
    }

    /**
     * Queue one access unit.
     *
     * [presentationTimeUs] comes from the RTP timestamp rather than from arrival time, so frames are
     * presented at the spacing they were captured at instead of the spacing the network delivered
     * them at.
     *
     * Returns false when the codec would not take it, which at 30 fps is worth dropping a frame over
     * rather than blocking the socket loop.
     */
    fun decode(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean): Boolean {
        val active = codec ?: return false
        return try {
            val index = active.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return false
            val input = active.getInputBuffer(index) ?: return false
            input.clear()
            input.put(data)
            val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            active.queueInputBuffer(index, 0, data.size, presentationTimeUs, flags)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not queue a frame", e)
            false
        }
    }

    /**
     * Hand whatever the decoder has finished to the surface.
     *
     * `releaseOutputBuffer(index, true)` is what actually renders, so this has to be pumped or the
     * picture never appears however well the decode went.
     */
    fun render() {
        val active = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                active.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "decoder went away mid-render", e)
                return
            }
            if (index < 0) return
            active.releaseOutputBuffer(index, true)
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    companion object {
        const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * What to put in `TV_IDENTITY` - the whole reason the receiver is ours.
         *
         * These are the decoder's own numbers, so the sender can stop guessing. The 1080p ceiling
         * the old sender used was openscreen's *mirroring policy* (`"Currently mirroring only
         * supports 1080P"` in `capture_recommendations.h`), never a decoder limit, so it does not
         * apply here: a TV that reports 4K gets 4K-shaped frames, subject to what the phone can
         * encode.
         *
         * Falls back to 1080p at 30 fps when the platform will not answer, because a receiver that
         * advertised nothing would be sent nothing.
         */
        fun limits(): DecoderLimits {
            val caps = runCatching {
                val name = decoderName() ?: return@runCatching null
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .first { it.name == name }
                    .getCapabilitiesForType(VIDEO_MIME)
            }.getOrNull() ?: return FALLBACK_LIMITS
            val video = caps.videoCapabilities ?: return FALLBACK_LIMITS
            return runCatching {
                DecoderLimits(
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    maxFrameRate = video.supportedFrameRates.upper.toInt(),
                    maxBitRate = video.bitrateRange.upper,
                )
            }.getOrDefault(FALLBACK_LIMITS)
        }

        private val FALLBACK_LIMITS = DecoderLimits(
            maxWidth = 1920,
            maxHeight = 1080,
            maxFrameRate = 30,
            maxBitRate = 10_000_000,
        )

        /**
         * REGULAR_CODECS, so the list matches what an ordinary app may instantiate.
         *
         * Hardware decoders are preferred by taking the first match: the platform lists them ahead
         * of the software ones, and a software H.264 decoder on TV silicon cannot keep up with 1080p30
         * at all.
         */
        private fun decoderName(): String? {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecs) {
                if (info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(VIDEO_MIME, ignoreCase = true) }) continue
                val caps: MediaCodecInfo.CodecCapabilities =
                    runCatching { info.getCapabilitiesForType(VIDEO_MIME) }.getOrNull() ?: continue
                if (caps.videoCapabilities != null) return info.name
            }
            return null
        }
    }
}
