package com.vayunmathur.cast.platform.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log

private const val TAG = "AudioEncoder"

private const val TIMEOUT_US = 10_000L

/**
 * Captures playing audio and encodes it as Opus.
 *
 * **Two honest limitations, neither of which this class can fix:**
 *
 *  1. `AudioPlaybackCapture` silently excludes any app that sets `ALLOW_CAPTURE_BY_NONE` - which is
 *     most DRM and music apps. Those are simply not in the stream, so the captured audio can be
 *     digital silence through no fault of ours and with no error anywhere.
 *  2. `audio/opus` encoders are not universally present.
 *
 * [EncoderSupport] answers the second before anything starts, and the UI says which one degraded.
 * Nothing here can detect the first, so the copy has to warn about it in general terms.
 *
 * `RECORD_AUDIO` is required even though no microphone is involved - the permission gates
 * `AudioRecord` itself, not the source.
 */
class AudioEncoder(private val projection: MediaProjection) {

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val name = EncoderSupport.audioEncoderName()
        if (name == null) {
            Log.w(TAG, "no ${EncoderSupport.AUDIO_MIME} encoder on this device")
            return false
        }
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(FRAME_BYTES * 4)
            val created = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord did not initialise")
                created.release()
                return false
            }
            val mediaFormat = MediaFormat.createAudioFormat(
                EncoderSupport.AUDIO_MIME,
                SAMPLE_RATE,
                CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            val encoder = MediaCodec.createByCodecName(name)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            created.startRecording()
            record = created
            codec = encoder
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start audio capture", e)
            release()
            false
        }
    }

    /**
     * Read one buffer of PCM, feed it in, and return whatever came out.
     *
     * Opus wants 20 ms frames; [FRAME_BYTES] is exactly that at [SAMPLE_RATE] in stereo 16-bit.
     */
    fun pump(): List<EncodedChunk> {
        val activeRecord = record ?: return emptyList()
        val activeCodec = codec ?: return emptyList()
        val pcm = ByteArray(FRAME_BYTES)
        val read = try {
            activeRecord.read(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(TAG, "audio read failed", e)
            return emptyList()
        }
        if (read > 0) {
            val index = activeCodec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = activeCodec.getInputBuffer(index)
                input?.clear()
                input?.put(pcm, 0, read)
                activeCodec.queueInputBuffer(index, 0, read, presentationTimeUs, 0)
                // Derived from the sample count rather than the clock: a timestamp that drifts
                // against the sample rate is what makes audio slowly desynchronise from video.
                samplesWritten += read / (2 * CHANNELS)
            }
        }
        val out = mutableListOf<EncodedChunk>()
        while (true) {
            val index = try {
                activeCodec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio encoder went away mid-drain", e)
                return out
            }
            if (index < 0) return out
            val buffer = activeCodec.getOutputBuffer(index)
            if (buffer != null &&
                bufferInfo.size > 0 &&
                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val bytes = ByteArray(bufferInfo.size).also { buffer.get(it) }
                // Every audio frame is independently decodable, so all of them are "key frames".
                out += EncodedChunk(bytes, bufferInfo.presentationTimeUs, isKeyFrame = true)
            }
            activeCodec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        record = null
        codec = null
    }

    private var samplesWritten = 0L

    private val presentationTimeUs: Long
        get() = samplesWritten * 1_000_000L / SAMPLE_RATE

    companion object {
        /** Matches the OFFER's `1/48000` timebase. */
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BIT_RATE = 128_000

        /** 20 ms of stereo 16-bit PCM. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 * CHANNELS
    }
}
