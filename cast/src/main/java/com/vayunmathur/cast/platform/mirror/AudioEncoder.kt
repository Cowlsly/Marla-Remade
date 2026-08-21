package com.vayunmathur.cast.platform.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

private const val TAG = "AudioEncoder"

/**
 * Captures what the phone is playing and encodes it as Opus.
 *
 * The Opus half lives in [OpusEncoder], shared with [PcmAudioEncoder]; what is left here is the
 * `AudioPlaybackCapture` half, which is the part that only screen mirroring can use.
 *
 * **Two honest limitations, neither of which this class can fix:**
 *
 *  1. `AudioPlaybackCapture` silently excludes any app that sets `ALLOW_CAPTURE_BY_NONE` - which is
 *     most DRM and music apps. Those are simply not in the stream, so the captured audio can be
 *     digital silence through no fault of ours and with no error anywhere.
 *  2. `audio/opus` encoders are not universally present.
 *
 * [EncoderSupport] answers the second before anything starts, and the UI says which one degraded.
 * Nothing here can detect the first, so the copy has to warn about it in general terms. An SDK
 * session avoids the first problem entirely, because the app hands over its own PCM.
 *
 * `RECORD_AUDIO` is required even though no microphone is involved - the permission gates
 * `AudioRecord` itself, not the source.
 */
class AudioEncoder(private val projection: MediaProjection) : AudioStream {

    private var record: AudioRecord? = null
    private val opus = OpusEncoder()

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (!opus.start()) return false
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OpusEncoder.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                OpusEncoder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(OpusEncoder.FRAME_BYTES * 4)
            val created = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord did not initialise")
                created.release()
                release()
                return false
            }
            created.startRecording()
            record = created
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start audio capture", e)
            release()
            false
        }
    }

    /** Read one 20 ms buffer of PCM, feed it in, and return whatever came out. */
    override fun pump(): List<EncodedChunk> {
        val active = record ?: return emptyList()
        val pcm = ByteArray(OpusEncoder.FRAME_BYTES)
        val read = try {
            active.read(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(TAG, "audio read failed", e)
            return emptyList()
        }
        return opus.encode(pcm, read.coerceAtLeast(0))
    }

    override fun release() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        opus.release()
    }
}
