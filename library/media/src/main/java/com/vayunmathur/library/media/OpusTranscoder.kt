package com.vayunmathur.library.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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

    internal const val TAG = "OpusTranscoder"

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
     *
     * Accumulates the whole stream in memory, which is what a caller wanting a `ByteArray`
     * asked for. [transcodeTo] is the same work without that: prefer it for anything with a
     * file or a socket on the other end.
     */
    fun transcode(
        source: ByteArray,
        isStopped: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): ByteArray? {
        val buffer = ByteArrayOutputStream(INITIAL_CAPACITY)
        transcodeTo(source, buffer, isStopped, onProgress) ?: return null
        return buffer.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Encodes [source] into [sink] as it goes, returning the bytes written or null on failure.
     *
     * **The stream is servable before it is finished**, which is the point: pages reach [sink]
     * about a second of audio at a time, so a reader following behind can start playing while
     * the rest is still being encoded. Encoding runs several times faster than real time, so a
     * player that starts at the first page stays behind the encoder rather than catching it.
     *
     * A null means the bytes already in [sink] are a partial stream with no end-of-stream page,
     * and the caller has to say so to whatever is reading: unlike [transcode], a failure here
     * cannot be undone by discarding a buffer, because the bytes have already left.
     *
     * [onProgress] is called with the fraction of the track encoded so far. Re-encoding a
     * track takes seconds rather than milliseconds, so a caller with a progress indicator
     * has to be able to move it; without that a slow transcode is indistinguishable from a
     * hang, which is what the user sees as a spinner that never finishes.
     *
     * [sink] is not closed - it belongs to the caller, who may well want its length afterwards.
     */
    fun transcodeTo(
        source: ByteArray,
        sink: OutputStream,
        isStopped: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Long? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var pump: OpusPump? = null
        val counted = CountingSink(sink)
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

            pump = OpusPump(
                extractor,
                decoder,
                isStopped,
                ::createEncoder,
                durationUs(format),
                onProgress,
                counted,
            )
            val completed = pump.run()
            // A cancel and a codec failure both used to log the same `out=0`, which is
            // indistinguishable in a bug report and reads as a broken encoder either way.
            val outcome = when {
                completed -> "out=${counted.count}"
                isStopped() -> "cancelled"
                else -> "failed"
            }
            Log.i(TAG, "transcode $mime: in=${source.size} $outcome")
            return if (completed) counted.count else null
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

    /** The track's length, or zero when the container does not say, which disables progress. */
    private fun durationUs(format: MediaFormat): Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

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

    /** A megabyte, which covers a few minutes of 256 kbps Opus without regrowing. */
    private const val INITIAL_CAPACITY = 1 * 1024 * 1024
}

/**
 * Counts what passes through, so a transcode can report its own size.
 *
 * The streaming path has no buffer to measure at the end, and the size is what a caller has to
 * hand on: a reader waiting on a growing resource is told the final length, and a log line
 * saying only "finished" is what makes a truncated encode look like a successful one.
 */
private class CountingSink(private val sink: OutputStream) : OutputStream() {

    var count = 0L
        private set

    override fun write(b: Int) {
        sink.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        count += len
    }

    override fun flush() = sink.flush()

    /** Deliberately not closing [sink]: it is the caller's, and they may still want its length. */
    override fun close() = Unit
}

/**
 * Drives the extractor, the decoder, the resampler and the encoder from one thread.
 *
 * Both codecs are software and share this thread, so every pass of the loop touches every
 * stage. Servicing one stage until it blocks would leave the other holding buffers nothing
 * is coming to collect, and the whole transcode would stall.
 *
 * Each stage is nevertheless serviced until it stops making progress rather than exactly
 * once per pass. Moving one buffer per stage per pass caps the whole transcode at whatever
 * a single encoder input buffer holds - 20 ms of audio on the platform Opus encoder - and
 * makes every pass wait out a dequeue timeout for whichever codec is momentarily busy,
 * which is a ceiling of a small multiple of real time however fast the codecs themselves
 * are. Polling with no timeout and blocking only once a whole pass has moved nothing puts
 * the same wait behind seconds of buffered audio instead of one packet of it.
 */
private class OpusPump(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val isStopped: () -> Boolean,
    private val createEncoder: (Int) -> MediaCodec,
    private val durationUs: Long,
    private val onProgress: (Float) -> Unit,
    sink: OutputStream,
) {
    /** Exposed only so the caller can release it; created once the decoder's format is known. */
    var encoder: MediaCodec? = null
        private set

    // One per codec: sharing a BufferInfo would work only because the loop happens to read
    // each one before the other overwrites it, which is not a property worth relying on.
    private val decoderInfo = MediaCodec.BufferInfo()
    private val encoderInfo = MediaCodec.BufferInfo()
    private val queue = PcmQueue()
    private val writer = OggStreamWriter(Random.nextInt(), sink)

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
    private var encoderDone = false

    private var lastProgressAt = 0L

    /** Whether a complete stream, end-of-stream page and all, reached the sink. */
    fun run(): Boolean {
        while (!encoderDone) {
            // A hi-res transcode runs for many seconds, so a cancelled download has to be
            // able to stop part-way rather than only between tracks.
            if (isStopped()) return false

            var moved = false
            while (!extractorDone && feedDecoder(POLL_US)) moved = true
            while (!decoderDone && queue.size < MAX_QUEUED_PCM && drainDecoder(POLL_US)) moved = true

            val active = encoder
            if (active == null) {
                // The decoder finished without ever producing PCM, so there is nothing to
                // encode and no format to configure an encoder from.
                if (decoderDone) return false
                if (!moved) drainDecoder(TIMEOUT_US)
                continue
            }

            while (!encoderClosed && queue.size >= frameBytes && feedEncoder(active, POLL_US)) {
                moved = true
            }
            if (!encoderClosed && decoderDone && queue.size < frameBytes) {
                encoderClosed = signalEndOfStream(active, POLL_US)
                if (encoderClosed) moved = true
            }
            while (drainEncoder(active, POLL_US)) moved = true

            // Nothing could be moved anywhere, so wait for a codec rather than spinning on
            // it. The encoder is the stage everything else queues up behind, so a packet
            // coming back from it is what frees the chain.
            if (!moved && !encoderDone) drainEncoder(active, TIMEOUT_US)
            reportProgress()
        }
        // An encoder that reported end of stream without ever emitting a packet leaves a
        // container with no audio in it, which is a failure however valid the framing is.
        if (encoded == 0L) return false
        writer.finish(preSkip + frames)
        return true
    }

    /**
     * Reports how much of the track has been encoded, at the same cadence the download
     * itself uses. Measured against the container's duration, since [frames] counts 48 kHz
     * frames and the source rate is already resampled away by this point.
     *
     * Throttled on the monotonic clock: a wall clock that steps backwards mid-track would
     * leave the last report in the future and silence progress for the rest of the
     * transcode, which is the very thing this exists to prevent.
     */
    private fun reportProgress() {
        if (durationUs <= 0L) return
        val now = System.nanoTime()
        if (lastProgressAt != 0L && now - lastProgressAt < PROGRESS_INTERVAL_NS) return
        lastProgressAt = now
        val total = durationUs * OpusHead.SAMPLE_RATE / 1_000_000L
        if (total > 0L) onProgress((frames.toFloat() / total).coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------
    // Decode and resample
    // ------------------------------------------------------------------

    /** Returns true while this stage is still worth servicing in the same pass. */
    private fun feedDecoder(timeoutUs: Long): Boolean {
        val index = decoder.dequeueInputBuffer(timeoutUs)
        if (index < 0) return false
        val buffer = decoder.getInputBuffer(index)
        if (buffer == null) {
            // Hand the index straight back rather than dropping it: a buffer taken from the
            // codec and never returned is gone for the rest of the run, and enough of them
            // would leave the pump with nothing to queue into and no way to reach the end.
            decoder.queueInputBuffer(index, 0, 0, 0, 0)
            return false
        }
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            extractorDone = true
            return false
        }
        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return true
    }

    /**
     * Drains one decoded buffer into the encoder's queue. Returns true while this stage is
     * still worth servicing in the same pass, which is not the same as "a buffer was
     * drained": a format change drains nothing, and the end-of-stream buffer is drained but
     * is the last one there will ever be.
     */
    private fun drainDecoder(timeoutUs: Long): Boolean {
        val index = decoder.dequeueOutputBuffer(decoderInfo, timeoutUs)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            configure(decoder.outputFormat)
            return true
        }
        if (index < 0) return false

        val buffer = decoder.getOutputBuffer(index)
        if (buffer != null && decoderInfo.size > 0) {
            // Some codecs deliver their first buffer before the format-changed event, so the
            // resampler is built on first use as well as on that event.
            if (resampler == null) configure(decoder.outputFormat)
            buffer.position(decoderInfo.offset)
            buffer.limit(decoderInfo.offset + decoderInfo.size)
            resample(buffer)
        }
        decoder.releaseOutputBuffer(index, false)

        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            resampler?.flush()?.let { queuePcm(it, it.size) }
            decoderDone = true
            return false
        }
        return true
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

    /** Hands one buffer of PCM to the encoder. Returns true when a buffer was queued. */
    private fun feedEncoder(encoder: MediaCodec, timeoutUs: Long): Boolean {
        val index = encoder.dequeueInputBuffer(timeoutUs)
        if (index < 0) return false
        val buffer = encoder.getInputBuffer(index)
        if (buffer == null) {
            encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), 0)
            return false
        }
        buffer.clear()
        // Only whole frames: half a frame queued would swap the channels of everything after
        // it, and the remainder is carried forward instead.
        val moved = queue.drainTo(buffer, buffer.capacity(), frameBytes)
        if (moved == 0) {
            encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), 0)
            return false
        }
        encoder.queueInputBuffer(index, 0, moved, presentationTimeUs(), 0)
        frames += moved / frameBytes
        return true
    }

    private fun signalEndOfStream(encoder: MediaCodec, timeoutUs: Long): Boolean {
        val index = encoder.dequeueInputBuffer(timeoutUs)
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

    /** Collects one encoded packet. Returns true while this stage is still worth servicing. */
    private fun drainEncoder(encoder: MediaCodec, timeoutUs: Long): Boolean {
        val index = encoder.dequeueOutputBuffer(encoderInfo, timeoutUs)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // Only if the format actually carries the header. Writing the fallback here
            // would lock it in before the codec-config buffer arrives with the real one.
            encoder.outputFormat.opusHead()?.let { writeHeaders(it) }
            return true
        }
        if (index < 0) return false

        val buffer = encoder.getOutputBuffer(index)
        if (buffer != null && encoderInfo.size > 0) {
            buffer.position(encoderInfo.offset)
            buffer.limit(encoderInfo.offset + encoderInfo.size)
            val bytes = ByteArray(encoderInfo.size)
            buffer.get(bytes)
            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                OpusHead.fromCodecConfig(bytes)?.let { writeHeaders(it) }
            } else {
                writeHeaders(null)
                // The granule position is the total samples a decoder gets out of the stream
                // so far, which already counts the pre-skip: those are the first samples it
                // decodes and then throws away, not extra ones on top.
                encoded += OpusHead.packetSamples(bytes, bytes.size)
                writer.writeAudioPacket(bytes, encoded)
            }
        }
        encoder.releaseOutputBuffer(index, false)
        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            encoderDone = true
            return false
        }
        return true
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
        /**
         * How long to wait for a codec once a whole pass has moved nothing. Only reached
         * when both codecs are genuinely busy, so it is a wait for work to finish rather
         * than a cost paid per buffer.
         */
        const val TIMEOUT_US = 10_000L

        /** Servicing a stage that has nothing ready must not cost anything. */
        const val POLL_US = 0L

        /** Matches the download's own reporting cadence, which is four times a second. */
        const val PROGRESS_INTERVAL_NS = 250_000_000L

        /** The Opus encoder takes at most stereo. */
        const val MAX_ENCODER_CHANNELS = 2

        /** Enough that the encoder is never starved, far short of buffering a whole track. */
        const val MAX_QUEUED_PCM = 512 * 1024
    }
}
