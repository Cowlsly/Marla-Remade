package com.vayunmathur.musicbrainz.data.download

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Re-encodes any audio the app can decode into an untagged Ogg/Opus stream at 48 kHz.
 *
 * Every download ends up as one format, so the formats the sources actually serve - FLAC, AAC
 * in MP4, and hi-res FLAC in MP4 - all funnel through here. The one exception is a stream
 * that is already 48 kHz Opus, which [OpusRemuxer] stream-copies instead; re-encoding that
 * would be a second lossy generation for no gain. The rest give up their lossless-ness in
 * exchange for a small, uniform library, which is the trade that was asked for.
 *
 * Resampling is not optional. `c2.android.opus.encoder` accepts 8, 12, 16, 24 and 48 kHz and
 * nothing else, so a 44.1 kHz CD-rate source - the common case - cannot be encoded at its own
 * rate at all. Everything is resampled to 48 kHz by [PolyphaseResampler].
 *
 * The whole thing is one streaming pass. Four minutes of 24/192 stereo is about 184 MB of
 * decoded PCM, a bigger allocation than the download itself, so each decoder buffer is
 * resampled and handed to the encoder as it arrives; only the resampler's tap history and a
 * partial frame are retained.
 *
 * The result carries a placeholder comment header, so tagging stays in [OggOpusTagger] on the
 * same path the stream-copy branch already uses.
 */
object OpusTranscoder {

    internal const val TAG = "MBDownload"

    /**
     * Well past Opus transparency, and still about a third the size of the FLAC it replaces.
     * Not a parameter: the source ladder already takes the best stream available, so there is
     * nothing left for a quality setting to choose between. The codec's declared ceiling is
     * 512 kbps if this is ever raised.
     */
    private const val BIT_RATE = 256_000

    /**
     * Returns the Ogg/Opus bytes, or null when [source] has no decodable audio track or the
     * platform codecs refuse it. A null fails the download: writing one of the formats the
     * user asked to be rid of would be worse than reporting the failure.
     */
    fun transcode(source: ByteArray, isStopped: () -> Boolean = { false }): ByteArray? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var pump: OpusPump? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(ByteArrayMediaDataSource(source)) }
            val track = audioTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            decoder = MediaCodec.createDecoderByType(mime)
            // Configured with the extractor's format untouched. Asking for a PCM encoding
            // here is how a decoder ends up quietly ignoring the request and delivering
            // something else anyway; what it produces is read back from its output format.
            decoder.configure(format, null, null, 0)
            decoder.start()

            pump = OpusPump(extractor, decoder, isStopped, ::createEncoder)
            val output = pump.run()
            Log.i(TAG, "transcode $mime: in=${source.size} out=${output?.size ?: 0}")
            return output?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "transcode threw: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        } finally {
            runCatching { extractor?.release() }
            for (codec in listOfNotNull(decoder, pump?.encoder)) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            Log.i(TAG, "transcode track $i mime=$mime")
            if (mime.startsWith("audio/")) return i
        }
        Log.w(TAG, "transcode: no audio track in ${extractor.trackCount} tracks")
        return null
    }

    private fun createEncoder(channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            OpusHead.SAMPLE_RATE,
            channels,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // The C2 encoder hands its input straight to `opus_encode`, which is int16.
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }
}

/**
 * Drives the extractor, the decoder, the resampler and the encoder from one thread.
 *
 * Both codecs are software and share this thread, so every pass of the loop touches every
 * stage. Servicing one stage until it blocks would leave the other holding buffers nothing
 * is coming to collect, and the whole transcode would stall.
 */
private class OpusPump(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val isStopped: () -> Boolean,
    private val createEncoder: (Int) -> MediaCodec,
) {
    /** Exposed only so the caller can release it; created once the decoder's format is known. */
    var encoder: MediaCodec? = null
        private set

    private val info = MediaCodec.BufferInfo()
    private val queue = PcmQueue()
    private val writer = OggStreamWriter(Random.nextInt())

    private var resampler: PolyphaseResampler? = null

    /** Channels the decoder produces, and the at most two the encoder is given. */
    private var sourceChannels = 0
    private var channels = 0
    private var frameBytes = 0
    private var floatPcm = false

    private var floats = FloatArray(0)
    private var pcm = ByteArray(0)

    /** 48 kHz frames handed to the encoder, which is what fixes the stream's true length. */
    private var frames = 0L

    /** 48 kHz samples the encoder has emitted, including the padding on its last packet. */
    private var encoded = 0L

    private var preSkip = OpusHead.DEFAULT_PRE_SKIP
    private var headersWritten = false

    private var extractorDone = false
    private var decoderDone = false
    private var encoderClosed = false

    fun run(): ByteArray? {
        while (true) {
            // A hi-res transcode runs for many seconds, so a cancelled download has to be
            // able to stop part-way rather than only between tracks.
            if (isStopped()) return null

            if (!extractorDone) extractorDone = feedDecoder()
            if (!decoderDone && queue.size < MAX_QUEUED_PCM) decoderDone = drainDecoder()

            val active = encoder
            if (active == null) {
                // The decoder finished without ever producing PCM, so there is nothing to
                // encode and no format to configure an encoder from.
                if (decoderDone) return null
                continue
            }
            if (!encoderClosed && decoderDone && queue.size < frameBytes) {
                encoderClosed = signalEndOfStream(active)
            } else {
                feedEncoder(active)
            }
            if (drainEncoder(active)) break
        }
        // An encoder that reported end of stream without ever emitting a packet leaves a
        // container with no audio in it, which is a failure however valid the framing is.
        if (encoded == 0L) return null
        return writer.finish(preSkip + frames)
    }

    // ------------------------------------------------------------------
    // Decode and resample
    // ------------------------------------------------------------------

    /** Returns true once the last compressed sample has been queued. */
    private fun feedDecoder(): Boolean {
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = decoder.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /** Drains one decoded buffer into the encoder's queue. Returns true at end of stream. */
    private fun drainDecoder(): Boolean {
        val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            configure(decoder.outputFormat)
            return false
        }
        if (index < 0) return false

        val buffer = decoder.getOutputBuffer(index)
        if (buffer != null && info.size > 0) {
            // Some codecs deliver their first buffer before the format-changed event, so the
            // resampler is built on first use as well as on that event.
            if (resampler == null) configure(decoder.outputFormat)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            resample(buffer)
        }
        decoder.releaseOutputBuffer(index, false)

        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            resampler?.flush()?.let { queuePcm(it, it.size) }
            return true
        }
        return false
    }

    /**
     * Reads the real output format and builds everything that depends on it.
     *
     * The rate, channel count and PCM encoding all come from the *decoder's* output format,
     * never from the container's: a FLAC track can report 24-bit while the decoder hands back
     * 16-bit or float, and building a resampler for the wrong rate is an instant pitch shift.
     */
    private fun configure(format: MediaFormat) {
        if (resampler != null) return
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        channels = sourceChannels.coerceAtMost(MAX_ENCODER_CHANNELS)
        frameBytes = channels * 2
        floatPcm = format.pcmEncoding() == AudioFormat.ENCODING_PCM_FLOAT
        resampler = PolyphaseResampler(rate, OpusHead.SAMPLE_RATE, channels)
        Log.i(
            OpusTranscoder.TAG,
            "transcode source: ${rate}Hz ${sourceChannels}ch float=$floatPcm -> " +
                "48000Hz ${channels}ch taps=${resampler?.tapsPerPhase}",
        )
        encoder = createEncoder(channels)
    }

    /**
     * An unset `KEY_PCM_ENCODING` means 16-bit; anything the app cannot read is treated the
     * same way, since guessing wrong produces noise rather than a failure.
     */
    private fun MediaFormat.pcmEncoding(): Int =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    private fun resample(buffer: ByteBuffer) {
        val bytesPerSample = if (floatPcm) 4 else 2
        val capacity = buffer.remaining() / bytesPerSample
        if (floats.size < capacity) floats = FloatArray(capacity)

        var samples = if (floatPcm) {
            PcmBuffers.readFloat(buffer, floats)
        } else {
            PcmBuffers.readS16(buffer, floats)
        }
        val sourceFrames = samples / sourceChannels
        if (sourceChannels > channels) {
            samples = PcmBuffers.foldToStereo(floats, sourceFrames, sourceChannels)
        }
        val resampled = resampler?.process(floats, samples / channels) ?: return
        queuePcm(resampled, resampled.size)
    }

    private fun queuePcm(samples: FloatArray, count: Int) {
        if (count == 0) return
        if (pcm.size < count * 2) pcm = ByteArray(count * 2)
        val bytes = PcmBuffers.writeS16(samples, count, pcm)
        queue.write(pcm, bytes)
    }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    private fun feedEncoder(encoder: MediaCodec) {
        if (queue.size < frameBytes) return
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = encoder.getInputBuffer(index) ?: return
        buffer.clear()
        // Only whole frames: half a frame queued would swap the channels of everything after
        // it, and the remainder is carried forward instead.
        val moved = queue.drainTo(buffer, buffer.capacity(), frameBytes)
        if (moved == 0) {
            encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), 0)
            return
        }
        encoder.queueInputBuffer(index, 0, moved, presentationTimeUs(), 0)
        frames += moved / frameBytes
    }

    private fun signalEndOfStream(encoder: MediaCodec): Boolean {
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        encoder.queueInputBuffer(
            index,
            0,
            0,
            presentationTimeUs(),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        return true
    }

    /**
     * Timestamps from the post-resample frame counter, never from the extractor: this is a
     * 48 kHz stream now, and a container timestamp would describe the source's rate.
     */
    private fun presentationTimeUs(): Long = frames * 1_000_000L / OpusHead.SAMPLE_RATE

    /** Returns true once the encoder has reported end of stream. */
    private fun drainEncoder(encoder: MediaCodec): Boolean {
        val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // Only if the format actually carries the header. Writing the fallback here
            // would lock it in before the codec-config buffer arrives with the real one.
            encoder.outputFormat.opusHead()?.let { writeHeaders(it) }
            return false
        }
        if (index < 0) return false

        val buffer = encoder.getOutputBuffer(index)
        if (buffer != null && info.size > 0) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                OpusHead.fromCodecConfig(bytes)?.let { writeHeaders(it) }
            } else {
                writeHeaders(null)
                encoded += OpusHead.packetSamples(bytes, bytes.size)
                writer.writeAudioPacket(bytes, preSkip + encoded)
            }
        }
        encoder.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    /**
     * Opens the stream with the identification header and a placeholder comment header.
     *
     * [head] is the encoder's own header when it supplied one, because only the encoder knows
     * how much lookahead it introduced; a pre-skip guessed a few hundred samples out shows up
     * as a duration every player reports slightly wrong.
     */
    private fun writeHeaders(head: ByteArray?) {
        if (headersWritten) return
        val identification = head
            ?: OpusHead.build(channels, OpusHead.DEFAULT_PRE_SKIP, OpusHead.SAMPLE_RATE)
        preSkip = OpusHead.preSkipOf(identification)
        writer.writeHeaderPacket(identification)
        writer.writeHeaderPacket(OggOpusTagger.buildOpusTagsPacket(VorbisTags()))
        headersWritten = true
    }

    private fun MediaFormat.opusHead(): ByteArray? {
        val csd = runCatching { getByteBuffer("csd-0") }.getOrNull() ?: return null
        val bytes = ByteArray(csd.remaining())
        csd.duplicate().get(bytes)
        return OpusHead.fromCodecConfig(bytes)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** The Opus encoder takes at most stereo. */
        const val MAX_ENCODER_CHANNELS = 2

        /** Enough that the encoder is never starved, far short of buffering a whole track. */
        const val MAX_QUEUED_PCM = 512 * 1024
    }
}
