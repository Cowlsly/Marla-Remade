package com.vayunmathur.musicbrainz.data.download

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the PCM plumbing between the decoder, the resampler and the encoder.
 *
 * The failures these catch are the silent kind: a clip that wraps turns a loud passage into
 * a burst of noise instead of a hint of distortion, and half a frame left in the queue swaps
 * the channels of everything after it.
 */
class PcmBuffersTest {

    private fun s16(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    @Test
    fun `reads signed 16-bit pcm as normalised floats`() {
        val out = FloatArray(4)
        assertEquals(4, PcmBuffers.readS16(s16(0, 16384, -16384, -32768), out))
        assertEquals(0f, out[0])
        assertEquals(0.5f, out[1])
        assertEquals(-0.5f, out[2])
        assertEquals(-1f, out[3])
    }

    @Test
    fun `reads 32-bit float pcm unchanged`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(0.25f)
        buffer.putFloat(-0.75f)
        buffer.flip()
        val out = FloatArray(2)
        assertEquals(2, PcmBuffers.readFloat(buffer, out))
        assertEquals(0.25f, out[0])
        assertEquals(-0.75f, out[1])
    }

    @Test
    fun `round trips a level through float and back`() {
        val out = ByteArray(4)
        PcmBuffers.writeS16(floatArrayOf(0.5f, -0.5f), 2, out)
        val back = FloatArray(2)
        PcmBuffers.readS16(ByteBuffer.wrap(out), back)
        assertEquals(0.5f, back[0])
        assertEquals(-0.5f, back[1])
    }

    @Test
    fun `clamps an overshoot instead of wrapping it`() {
        val out = ByteArray(8)
        PcmBuffers.writeS16(floatArrayOf(1.2f, -1.2f, 1f, -1f), 4, out)
        val shorts = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(32767, shorts.get().toInt(), "a positive overshoot must not wrap negative")
        assertEquals(-32768, shorts.get().toInt())
        assertEquals(32767, shorts.get().toInt())
        assertEquals(-32768, shorts.get().toInt())
    }

    @Test
    fun `folds a wider layout onto its front pair`() {
        // Two frames of 5.1, channels numbered so a mix-up is obvious.
        val input = floatArrayOf(
            0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f,
            0.7f, 0.8f, 0.9f, 1.0f, 0.0f, 0.0f,
        )
        assertEquals(4, PcmBuffers.foldToStereo(input, frames = 2, channels = 6))
        assertEquals(listOf(0.1f, 0.2f, 0.7f, 0.8f), input.take(4))
    }

    // ------------------------------------------------------------------
    // PcmQueue
    // ------------------------------------------------------------------

    @Test
    fun `hands out only whole frames and keeps the remainder`() {
        val queue = PcmQueue(initialCapacity = 16)
        // Nine bytes of stereo 16-bit audio: two whole frames and one stray byte.
        queue.write(ByteArray(9) { it.toByte() }, 9)

        val first = ByteBuffer.allocate(16)
        assertEquals(8, queue.drainTo(first, limit = 16, frameBytes = 4))
        assertEquals(1, queue.size, "the odd byte must stay queued, not be handed over")

        // Once the rest of that frame arrives it comes out with the right alignment.
        queue.write(ByteArray(3) { (9 + it).toByte() }, 3)
        val second = ByteBuffer.allocate(16)
        assertEquals(4, queue.drainTo(second, limit = 16, frameBytes = 4))
        assertEquals(0, queue.size)
        assertEquals(listOf<Byte>(8, 9, 10, 11), second.array().take(4))
    }

    @Test
    fun `respects the destination and the limit`() {
        val queue = PcmQueue(initialCapacity = 8)
        queue.write(ByteArray(64), 64)
        assertEquals(4, queue.drainTo(ByteBuffer.allocate(5), limit = 64, frameBytes = 4))
        assertEquals(8, queue.drainTo(ByteBuffer.allocate(64), limit = 10, frameBytes = 4))
        assertEquals(52, queue.size)
    }

    @Test
    fun `grows and compacts across many partial drains`() {
        val queue = PcmQueue(initialCapacity = 8)
        var written = 0
        var drained = 0
        repeat(200) {
            queue.write(ByteArray(37) { 1 }, 37)
            written += 37
            val destination = ByteBuffer.allocate(20)
            drained += queue.drainTo(destination, limit = 20, frameBytes = 4)
        }
        assertEquals(written - drained, queue.size)
        assertTrue(drained > 0, "a queue that never drains would grow without bound")
    }
}
