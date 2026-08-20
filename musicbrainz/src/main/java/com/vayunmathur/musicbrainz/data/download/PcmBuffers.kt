package com.vayunmathur.musicbrainz.data.download

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts between the PCM a MediaCodec decoder hands out and the float frames the
 * resampler works in, and holds the bytes waiting for the encoder.
 *
 * Kept apart from [PolyphaseResampler] so the resampler stays codec-agnostic: it only ever
 * sees interleaved floats at a known rate, and everything to do with sample formats,
 * channel counts and buffer boundaries lives here.
 */
internal object PcmBuffers {

    /** Full-scale for signed 16-bit, used both ways so a round trip is symmetric. */
    private const val S16_SCALE = 32768f
    private const val S16_MAX = 32767
    private const val S16_MIN = -32768

    /**
     * Reads [buffer] as signed 16-bit little-endian PCM into [out] as floats in -1..1,
     * returning how many samples were written.
     */
    fun readS16(buffer: ByteBuffer, out: FloatArray): Int {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shorts.remaining()
        for (i in 0 until count) out[i] = shorts.get() / S16_SCALE
        return count
    }

    /** Reads [buffer] as 32-bit float PCM into [out], returning how many samples were written. */
    fun readFloat(buffer: ByteBuffer, out: FloatArray): Int {
        val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val count = floats.remaining()
        for (i in 0 until count) out[i] = floats.get()
        return count
    }

    /**
     * Writes the first [samples] of [input] as signed 16-bit little-endian PCM.
     *
     * Clamped rather than wrapped: a resampler's ringing can overshoot a full-scale source
     * by a fraction of a decibel, and letting that wrap would turn a loud passage into a
     * burst of noise instead of a hint of clipping.
     */
    fun writeS16(input: FloatArray, samples: Int, out: ByteArray): Int {
        var index = 0
        for (i in 0 until samples) {
            val value = Math.round(input[i] * S16_SCALE).coerceIn(S16_MIN, S16_MAX)
            out[index++] = value.toByte()
            out[index++] = (value shr 8).toByte()
        }
        return index
    }

    /**
     * Reduces [channels]-channel interleaved audio in place to the front pair.
     *
     * The Opus encoder takes at most two channels. Multichannel music is not something
     * either source serves - Tidal's Dolby streams are rejected earlier as protected - so
     * this keeps the front left/right pair rather than guessing at a downmix matrix for a
     * channel order it has no way to confirm.
     */
    fun foldToStereo(interleaved: FloatArray, frames: Int, channels: Int): Int {
        require(channels > 2) { "only wider than stereo needs folding" }
        for (frame in 0 until frames) {
            interleaved[frame * 2] = interleaved[frame * channels]
            interleaved[frame * 2 + 1] = interleaved[frame * channels + 1]
        }
        return frames * 2
    }
}

/**
 * A byte FIFO for the PCM waiting to be encoded.
 *
 * The resampler emits whatever the ratio produces, while the encoder wants whole frames in
 * whatever size its input buffer happens to be, so the two do not line up. This holds the
 * remainder between them and hands out only whole frames, which matters because half a
 * frame queued would swap the channels of everything after it.
 */
internal class PcmQueue(initialCapacity: Int = 64 * 1024) {

    private var buffer = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    fun write(source: ByteArray, length: Int) {
        if (length <= 0) return
        if (tail + length > buffer.size) compact(length)
        System.arraycopy(source, 0, buffer, tail, length)
        tail += length
    }

    /**
     * Moves up to [limit] bytes into [destination], truncated to a whole number of
     * [frameBytes]-sized frames. Returns how many bytes were moved.
     */
    fun drainTo(destination: ByteBuffer, limit: Int, frameBytes: Int): Int {
        val take = minOf(size, limit, destination.remaining()) / frameBytes * frameBytes
        if (take <= 0) return 0
        destination.put(buffer, head, take)
        head += take
        if (head == tail) {
            head = 0
            tail = 0
        }
        return take
    }

    private fun compact(incoming: Int) {
        val remaining = size
        if (head > 0) {
            System.arraycopy(buffer, head, buffer, 0, remaining)
            head = 0
            tail = remaining
        }
        if (remaining + incoming > buffer.size) {
            buffer = buffer.copyOf(maxOf(remaining + incoming, buffer.size * 2))
        }
    }
}
