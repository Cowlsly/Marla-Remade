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
 *
 * **A failure here is terminal, and that is the fix for a measured disaster.** [play] used to catch
 * every exception and retry on the next packet, so a codec that entered its released state a few
 * seconds in logged a stack trace per 20 ms packet from then on: 2,634 traces in 38,233 logcat lines
 * in one session, on the thread that has to be reading datagrams. The storm cost more than the lost
 * audio ever did, and it was voluminous enough to evict its own root cause from the log buffer.
 */
class AudioPlayer {

    // Volatile so the lifecycle is explicit rather than resting on the loop thread happening to be
    // the only reader: [release] can run while a stale reference is still in a local.
    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var track: AudioTrack? = null

    /** Once set, this session has no sound and [play] is a no-op. */
    @Volatile
    var failed: Boolean = false
        private set

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
            // Published before the AudioTrack is built, because the catch below releases through the
            // fields: a throw from AudioTrack.Builder would otherwise leak a started MediaCodec.
            codec = created
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
            track = output
            output.play()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start audio playback", e)
            release()
            false
        }
    }

    /**
     * Queue one Opus packet and write out whatever the decoder finished.
     *
     * Called from the socket loop, so the cost of failing has to be bounded: the first exception
     * gives up on audio for the rest of the session rather than being retried per packet.
     */
    fun play(data: ByteArray, presentationTimeUs: Long) {
        if (failed) return
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
            // One trace, once. Whatever put the codec in this state will not be undone by the next
            // packet, and a realtime path may not log per packet.
            failed = true
            Log.w(TAG, "audio playback failed for good; the rest of this session is silent", e)
            release()
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
