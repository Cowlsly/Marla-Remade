package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.VideoCodec
import java.nio.ByteBuffer

private const val TAG = "VideoEncoder"

private const val TIMEOUT_US = 10_000L

/**
 * The most often a key frame may be asked for.
 *
 * While the receiver has no decoder it PLIs every feedback round - every 50 ms - and each request
 * resets [VideoEncoder.sentParameterSets] and demands an IDR, so the sender emitted back-to-back key
 * frames at native resolution that the receiver then threw away for want of a surface. AV1's extra
 * startup gate widens that window, which is what turns a pre-existing waste into a real one. Long
 * enough to stop the storm, short enough that a genuine loss is still repaired within a few frames.
 */
private const val KEY_FRAME_MIN_INTERVAL_MS = 400L

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
 * An H.265 or AV1 encoder fed by a `Surface`, producing raw access units.
 *
 * The drain-loop shape is borrowed from `camera/.../MotionPhotoEncoder.kt`, with two inversions
 * that matter:
 *
 *  - **It never touches pixels.** [inputSurface] is handed to a `VirtualDisplay`, which writes into
 *    it directly, so there is no input-buffer queueing and no `fillI420` colour conversion.
 *  - **It keeps `BUFFER_FLAG_CODEC_CONFIG`.** `MotionPhotoEncoder` discards it because `MediaMuxer`
 *    writes the parameter sets into the container itself. There is no container here, so they are
 *    cached - a receiver handed a key frame with no parameter sets shows nothing at all, and shows it
 *    silently.
 *
 * **Where those cached bytes go depends on the codec, and that is the one genuinely per-codec branch
 * in this class.** H.265's VPS/SPS/PPS are Annex-B units, so they are prepended to the first key
 * frame exactly as H.264's were. AV1's are a sequence header the decoder wants as `csd-0` before it
 * is configured at all, so they cannot ride in the stream - they go out through [onCodecConfig] on the
 * control channel instead.
 */
class VideoEncoder(
    private val codec: VideoCodec,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    /**
     * The codec configuration, for a codec that cannot carry it in-band.
     *
     * Called from the encoder loop when the bytes first appear, and again from the RTCP loop on every
     * [requestKeyFrame] - so the implementation has to be safe from both, and is expected to be
     * cheap: it is on the path of a repair.
     */
    private val onCodecConfig: (ByteArray) -> Unit = {},
) {

    private var mediaCodec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** The parameter sets, cached from the codec-config buffer. */
    @Volatile
    private var parameterSets: ByteArray? = null

    /**
     * Whether the current key-frame generation has had its parameter sets prepended already.
     *
     * **Meaningful only for a codec that carries them in-band**, i.e. H.265. For AV1 nothing reads it,
     * because the bytes never travel with a frame; it is left alone rather than special-cased so the
     * latch has one meaning. Volatile because [requestKeyFrame] runs on the RTCP loop while [drain]
     * runs on the encoder loop.
     */
    @Volatile
    private var sentParameterSets = false

    @Volatile
    private var lastKeyFrameRequestMs = 0L

    /** True once the codec has produced its configuration, for the caller's own startup watchdog. */
    val hasCodecConfig: Boolean get() = parameterSets != null

    var inputSurface: Surface? = null
        private set

    /** False when the device has no usable surface encoder, which is not recoverable. */
    fun start(): Boolean {
        val name = EncoderSupport.videoEncoderName(codec)
        if (name == null) {
            Log.w(TAG, "no hardware ${codec.mimeType} encoder taking COLOR_FormatSurface")
            return false
        }
        return try {
            val format = MediaFormat.createVideoFormat(codec.mimeType, width, height)
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
                    // **Load-bearing for screen capture.** A VirtualDisplay only hands the encoder a
                    // buffer when the screen content changes, so a phone sitting on a static screen
                    // produces no frames at all - the stream simply stops, and a receiver watching a
                    // stream that has stopped concludes the sender is gone and tears the session
                    // down. This tells the encoder to re-emit the previous frame if nothing new has
                    // arrived, which keeps a steady frame rate for free: an unchanged frame codes to
                    // almost nothing.
                    setLong(
                        MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                        1_000_000L / frameRate,
                    )
                    // **CBR where the encoder offers it, not VBR.** With VBR this encoder answered a
                    // 12 Mbit/s target with a measured 4.18 Mbit/s: it read a mostly-static screen as
                    // cheap and did not spend the allowance, which is what the bleeding around text
                    // was. Screen content is where VBR's judgement is worst - flat regions cost
                    // nothing, and the sharp edges that need the bits get averaged away with them.
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        EncoderSupport.videoBitrateMode(codec, name),
                    )
                }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = created.createInputSurface()
            created.start()
            mediaCodec = created
            Log.i(TAG, "encoding ${codec.label} at ${width}x$height @ ${frameRate}fps on $name")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the ${codec.label} encoder", e)
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
        val active = mediaCodec ?: return emptyList()
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
                    onCodecConfigBuffer(bytes)
                } else {
                    val isKeyFrame =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // Prepended only for a codec whose parameter sets belong in the stream. AV1's are
                    // already on their way over the control channel, and putting them here as well
                    // would hand the decoder bytes it has been configured to expect elsewhere.
                    val payload =
                        if (isKeyFrame && !sentParameterSets && !codec.needsCodecConfig) {
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
     *
     * **Rate-limited**, because a receiver with nowhere to draw asks on every feedback round and each
     * answer is a key frame at native resolution that it then discards. See
     * [KEY_FRAME_MIN_INTERVAL_MS].
     */
    fun requestKeyFrame() {
        val active = mediaCodec ?: return
        // Monotonic, not wall clock: a backwards time adjustment would make the interval negative and
        // suppress every later request - including the AV1 codec-config re-send below, which is the
        // only repair path there is for it.
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyFrameRequestMs < KEY_FRAME_MIN_INTERVAL_MS) return
        lastKeyFrameRequestMs = now
        runCatching {
            active.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }.onFailure { Log.w(TAG, "could not request a key frame", it) }
        // The new key frame needs its parameter sets in front of it too: a receiver asking for one is
        // a receiver that may never have seen the originals.
        sentParameterSets = false
        // And for a codec that cannot carry them in-band, "in front of it" means the control channel.
        // This is the whole repair path for AV1 - `CODEC_CONFIG` is emitted once per encoder, with its
        // first output, so a single-shot delivery that was lost would be a black screen for the rest of
        // the session.
        if (codec.needsCodecConfig) parameterSets?.let(onCodecConfig)
    }

    fun release() {
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        runCatching { inputSurface?.release() }
        mediaCodec = null
        inputSurface = null
    }

    /** Not a frame: the parameter sets, cached and - for AV1 - sent on at once. */
    private fun onCodecConfigBuffer(bytes: ByteArray) {
        parameterSets = bytes
        Log.i(
            TAG,
            "${codec.label} codec config: ${bytes.size} bytes, ${bytes.hexPreview()}" +
                if (codec.needsCodecConfig) " - sending it to the TV" else " - riding in-band",
        )
        if (codec.needsCodecConfig) onCodecConfig(bytes)
    }

    private fun ByteBuffer.readChunk(): ByteArray {
        position(bufferInfo.offset)
        limit(bufferInfo.offset + bufferInfo.size)
        return ByteArray(bufferInfo.size).also { get(it) }
    }
}

/**
 * The first bytes as hex, for a log line.
 *
 * The only way to answer, from a device, whether AV1's codec config arrived as an `av1C`
 * configuration record or as a raw sequence-header OBU - and that question decides whether `csd-0`
 * was the right place to put it. 16 bytes is enough to see the leading marker either way.
 */
internal fun ByteArray.hexPreview(count: Int = 16): String =
    take(count).joinToString(" ") { "%02x".format(it) }
