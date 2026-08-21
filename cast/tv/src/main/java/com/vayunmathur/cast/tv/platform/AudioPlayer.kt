package com.vayunmathur.cast.tv.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.vayunmathur.cast.protocol.StreamConstants

private const val TAG = "AudioPlayer"

private const val TIMEOUT_US = 10_000L

/**
 * Opus packets in, sound out.
 *
 * The mirror of `:cast`'s `AudioEncoder`, and it inherits that class's honest limitation: any app on
 * the phone that sets `ALLOW_CAPTURE_BY_NONE` is silently absent from the captured audio, so digital
 * silence here can mean the capture was empty rather than that anything is broken. Nothing on this
 * end can tell the difference.
 *
 * `MODE_STREAM` with `PERFORMANCE_MODE_LOW_LATENCY`: the phone's frames are already 20 ms and
 * arriving in real time, so the only thing extra buffering would buy is lip-sync error.
 *
 * **No `csd-0`.** Android's Opus decoder wants an identification header, and the encoder on the phone
 * does not send its codec-config buffer over the wire. So the header is synthesised here from the
 * parameters both ends already agree on - they are fixed constants in [StreamConstants], not
 * negotiated, which is what makes reconstructing it sound rather than a guess.
 */
class AudioPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    /** False when this TV has no Opus decoder, which leaves the picture but no sound. */
    fun start(): Boolean {
        val name = decoderName()
        if (name == null) {
            Log.w(TAG, "no $AUDIO_MIME decoder on this TV")
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(
                AUDIO_MIME,
                StreamConstants.AUDIO_TIMEBASE,
                StreamConstants.AUDIO_CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusIdentificationHeader()))
            }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, null, null, 0)
            created.start()

            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(
                StreamConstants.AUDIO_TIMEBASE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(MIN_TRACK_BYTES)
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(StreamConstants.AUDIO_TIMEBASE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            output.play()

            codec = created
            track = output
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start audio playback", e)
            release()
            false
        }
    }

    /** Queue one Opus packet and write out whatever the decoder finished. */
    fun play(data: ByteArray, presentationTimeUs: Long) {
        val activeCodec = codec ?: return
        val activeTrack = track ?: return
        try {
            val index = activeCodec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = activeCodec.getInputBuffer(index)
                input?.clear()
                input?.put(data)
                activeCodec.queueInputBuffer(index, 0, data.size, presentationTimeUs, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = activeCodec.dequeueOutputBuffer(info, 0)
                if (out < 0) return
                val buffer = activeCodec.getOutputBuffer(out)
                if (buffer != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(pcm)
                    activeTrack.write(pcm, 0, pcm.size)
                }
                activeCodec.releaseOutputBuffer(out, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio playback failed", e)
        }
    }

    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        track = null
        codec = null
    }

    private companion object {
        const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

        /** 200 ms of stereo 16-bit at 48 kHz, as a floor under whatever the platform asks for. */
        const val MIN_TRACK_BYTES = 48_000 / 5 * 2 * 2

        /**
         * The 19-byte `OpusHead`, per RFC 7845 §5.1.
         *
         * ```
         * "OpusHead" | version=1 | channels | pre-skip(LE16) | sample rate(LE32) | gain(LE16) | map=0
         * ```
         *
         * Pre-skip 0 and output gain 0 because the phone's encoder applies neither, and channel
         * mapping family 0 because two channels are plain stereo.
         */
        fun opusIdentificationHeader(): ByteArray {
            val rate = StreamConstants.AUDIO_TIMEBASE
            return byteArrayOf(
                'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
                'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
                1,
                StreamConstants.AUDIO_CHANNELS.toByte(),
                0, 0,
                (rate and 0xff).toByte(),
                ((rate ushr 8) and 0xff).toByte(),
                ((rate ushr 16) and 0xff).toByte(),
                ((rate ushr 24) and 0xff).toByte(),
                0, 0,
                0,
            )
        }

        fun decoderName(): String? {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecs) {
                if (info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(AUDIO_MIME, ignoreCase = true) }) {
                    return info.name
                }
            }
            return null
        }
    }
}
