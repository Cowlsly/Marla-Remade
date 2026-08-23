package com.vayunmathur.cast.tv.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.vayunmathur.cast.protocol.AudioCodec
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
 * **The codec configuration is synthesised.** Android's Opus decoder wants its headers, and the encoder
 * on the phone does not send its codec-config buffer over the wire. So they are rebuilt here from the
 * parameters both ends already agree on - they are fixed constants in [StreamConstants], not
 * negotiated, which is what makes reconstructing them sound rather than a guess.
 *
 * **Three csd buffers, not one.** The decoder expects `csd-0` = `OpusHead`, `csd-1` = the codec delay
 * and `csd-2` = the seek pre-roll, the latter two as little-endian nanoseconds - the same three
 * ExoPlayer builds in `OpusUtil.buildInitializationData`. Only `csd-0` used to be supplied, which
 * `configure()` accepts without complaint and then leaves the component to make the best of.
 *
 * **A failure is survivable, and bounded.** [play] used to catch every exception and retry on the next
 * packet, so a codec that entered its released state a few seconds in logged a stack trace per 20 ms
 * packet from then on: 2,634 traces in 38,233 logcat lines in one session, on the thread that has to be
 * reading datagrams. Giving up for good stopped the storm but overcorrected: one transient error became
 * a silent session with no way back, which is what hardware then measured - audio died 3 seconds in and
 * stayed dead while 487 packets arrived to be decoded into nothing. The codec is rebuilt instead, at
 * most [MAX_RESTARTS] times, and only then given up on. The log stays bounded because the number of
 * attempts is.
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

    /**
     * How many times the codec has been rebuilt after a failure.
     *
     * Counted rather than latched on the first error, so a transient fault costs a gap in the sound
     * instead of the rest of the session - but bounded, because a codec that is broken for a structural
     * reason will fail again immediately and rebuilding it per packet would be the log storm the old
     * latch existed to prevent.
     */
    var restarts: Int = 0
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
                // All three, in the order the decoder reads them. csd-0 alone configures without
                // complaint, which is what made its absence easy to miss.
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusIdentificationHeader()))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(nanosecondsLe(0)))
                setByteBuffer("csd-2", java.nio.ByteBuffer.wrap(nanosecondsLe(0)))
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
     * Set the output gain, 0..1.
     *
     * **A gain on the track rather than a change to the TV's own volume.** The level is the phone's,
     * shared between the two ends so that turning the sound down here leaves the phone quiet when
     * playback comes back to it. Moving the television's device volume instead would put the shared
     * level and the box's own setting in a fight, and the user would have two controls doing the same
     * job badly.
     *
     * Called from the media loop, like everything else that touches [track] - see `pump`'s note on why
     * nothing outside that coroutine may.
     */
    fun setVolume(level: Float) {
        val activeTrack = track ?: return
        runCatching { activeTrack.setVolume(level.coerceIn(0f, 1f)) }
            .onFailure { Log.w(TAG, "could not set the output volume", it) }
    }

    /**
     * Queue one Opus packet and write out whatever the decoder finished.
     *
     * Called from the socket loop, so the cost of failing has to be bounded - see the class comment for
     * why that is a rebuild rather than either a per-packet retry or a permanent surrender.
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
            recover(e)
        }
    }

    /**
     * Rebuild the codec, or give up if it has already been rebuilt too often.
     *
     * One trace per attempt, never per packet. The trace matters as much as the recovery does: the
     * hardware failure this replaces was a codec found in its *released* state on the very first
     * `dequeueInputBuffer`, with nothing in the log to say what had released it, so the attempt count
     * and the codec's own name go in the line to narrow that down next time.
     */
    private fun recover(cause: Exception) {
        release()
        if (restarts >= MAX_RESTARTS) {
            failed = true
            Log.w(
                TAG,
                "audio failed $MAX_RESTARTS times; the rest of this session is silent",
                cause,
            )
            return
        }
        restarts++
        Log.w(TAG, "audio playback failed; rebuilding the decoder (attempt $restarts)", cause)
        if (!start()) {
            failed = true
            Log.w(TAG, "the rebuilt audio decoder would not start; this session is silent")
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

    /**
     * Not private, unlike it used to be.
     *
     * Everything in here is still an implementation detail except the two capability lookups at the
     * bottom, and those had to become reachable: `ReceiverController` cannot advertise an Opus
     * decoder it is not allowed to ask about, and until it could, an audio-only session had no way
     * to be refused.
     */
    companion object {
        /** 200 ms of stereo 16-bit at 48 kHz, as a floor under whatever the platform asks for. */
        private const val MIN_TRACK_BYTES = 48_000 / 5 * 2 * 2

        /**
         * How many times the decoder may be rebuilt before audio is given up on.
         *
         * Three, because the failure this exists for was a single one: a codec found already released
         * on its first use, which one rebuild would have cleared. A codec broken structurally fails
         * again at once, so this is spent in milliseconds and the log stays short either way.
         */
        private const val MAX_RESTARTS = 3

        /**
         * `csd-1` and `csd-2`: the codec delay and the seek pre-roll, in little-endian nanoseconds.
         *
         * Both zero, for the same reason the identification header's pre-skip is: the phone's encoder
         * applies neither, and there is no container here whose timestamps would need adjusting for
         * them. They are supplied rather than omitted because the decoder reads three csd buffers and
         * silently tolerates being given one.
         */
        private fun nanosecondsLe(value: Long): ByteArray = ByteArray(8) { i ->
            ((value ushr (8 * i)) and 0xff).toByte()
        }

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
        private fun opusIdentificationHeader(): ByteArray {
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

        /** From the protocol, so the phone's encoder and this decoder cannot look for different things. */
        val AUDIO_MIME = AudioCodec.Opus.mimeType

        /**
         * What this TV can decode, for `TV_IDENTITY`.
         *
         * Mirrors `VideoDecoder.limits()`, and exists for the same reason: the phone has to be able
         * to refuse a session it cannot serve, by name, before starting one. Until audio-only was
         * possible nobody asked - a TV with no Opus decoder just played a silent picture - so an
         * audio-only session would have sat in silence with no failure to report.
         */
        fun limits(): List<AudioCodec> =
            if (decoderName() != null) listOf(AudioCodec.Opus) else emptyList()

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
