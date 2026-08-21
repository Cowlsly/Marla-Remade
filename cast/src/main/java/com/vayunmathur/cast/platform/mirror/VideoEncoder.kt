package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "VideoEncoder"

private const val TIMEOUT_US = 10_000L

/** One encoded access unit, straight off the codec. */
data class EncodedChunk(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedChunk &&
            presentationTimeUs == other.presentationTimeUs &&
            isKeyFrame == other.isKeyFrame &&
            data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        return result
    }
}

/**
 * H.264 encoder fed by a `Surface`, producing raw access units.
 *
 * The drain-loop shape is borrowed from `camera/.../MotionPhotoEncoder.kt`, with two inversions
 * that matter:
 *
 *  - **It never touches pixels.** [inputSurface] is handed to a `VirtualDisplay`, which writes into
 *    it directly, so there is no input-buffer queueing and no `fillI420` colour conversion.
 *  - **It keeps `BUFFER_FLAG_CODEC_CONFIG`.** `MotionPhotoEncoder` discards it because `MediaMuxer`
 *    writes SPS/PPS into the container itself. There is no container here, so the parameter sets are
 *    cached and prepended to the first IDR - a receiver handed an IDR with no SPS/PPS shows nothing
 *    at all, and shows it silently.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
) {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** SPS/PPS, cached from the codec-config buffer and prepended to the first key frame. */
    private var parameterSets: ByteArray? = null
    private var sentParameterSets = false

    var inputSurface: Surface? = null
        private set

    /** False when the device has no usable surface encoder, which is not recoverable. */
    fun start(): Boolean {
        val name = EncoderSupport.videoEncoderName()
        if (name == null) {
            Log.w(TAG, "no ${EncoderSupport.VIDEO_MIME} encoder taking COLOR_FormatSurface")
            return false
        }
        return try {
            val format = MediaFormat.createVideoFormat(EncoderSupport.VIDEO_MIME, width, height)
                .apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    // One second. A receiver that joins or loses sync recovers at the next IDR, and
                    // the RTCP path can ask for one sooner when it actually needs it.
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = created.createInputSurface()
            created.start()
            codec = created
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the video encoder", e)
            release()
            false
        }
    }

    /**
     * Everything the encoder has ready right now.
     *
     * Non-blocking beyond [TIMEOUT_US], so the caller's loop stays responsive to a stop request
     * even when the encoder has produced nothing.
     */
    fun drain(): List<EncodedChunk> {
        val active = codec ?: return emptyList()
        val out = mutableListOf<EncodedChunk>()
        while (true) {
            val index = try {
                active.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder went away mid-drain", e)
                return out
            }
            if (index < 0) return out
            val buffer = active.getOutputBuffer(index)
            if (buffer != null && bufferInfo.size > 0) {
                val bytes = buffer.readChunk()
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Not a frame: the parameter sets, which must lead the first IDR.
                    parameterSets = bytes
                } else {
                    val isKeyFrame =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val payload = if (isKeyFrame && !sentParameterSets) {
                        sentParameterSets = true
                        (parameterSets ?: ByteArray(0)) + bytes
                    } else {
                        bytes
                    }
                    out += EncodedChunk(payload, bufferInfo.presentationTimeUs, isKeyFrame)
                }
            }
            active.releaseOutputBuffer(index, false)
        }
    }

    /**
     * Ask for an IDR at the next opportunity.
     *
     * Called when RTCP feedback says a receiver has fallen further behind than the retransmit
     * buffer can repair, which is the only way it can recover.
     */
    fun requestKeyFrame() {
        val active = codec ?: return
        runCatching {
            active.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }.onFailure { Log.w(TAG, "could not request a key frame", it) }
        // The new IDR needs SPS/PPS in front of it too: a receiver asking for a key frame is one
        // that may never have seen the originals.
        sentParameterSets = false
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
    }

    private fun ByteBuffer.readChunk(): ByteArray {
        position(bufferInfo.offset)
        limit(bufferInfo.offset + bufferInfo.size)
        return ByteArray(bufferInfo.size).also { get(it) }
    }
}
