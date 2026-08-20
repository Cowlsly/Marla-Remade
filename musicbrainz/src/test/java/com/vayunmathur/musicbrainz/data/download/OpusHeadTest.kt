package com.vayunmathur.musicbrainz.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Opus identification header and the packet arithmetic around it.
 *
 * The pre-skip and the packet duration are what the Ogg writer turns into granule positions,
 * so a mistake in either shows up as a file whose reported duration is wrong and which seeks
 * to the wrong place - never as a crash. The marker-chunk parsing matters because that is the
 * shape the platform encoder actually produces, and a stray `AOPUSHDR` copied into the file
 * as if it were a header would make the whole stream unreadable.
 */
class OpusHeadTest {

    private fun longLe(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }

    @Test
    fun `builds a header a reader can parse`() {
        val head = OpusHead.build(channels = 2, preSkip = 312, inputSampleRate = 44_100)
        assertEquals(OpusHead.SIZE, head.size)
        assertEquals("OpusHead", String(head, 0, 8, Charsets.ISO_8859_1))
        assertEquals(1, head[8].toInt(), "version")
        assertEquals(2, head[9].toInt(), "channel count")
        assertEquals(312, OpusHead.preSkipOf(head))
        assertEquals(44_100, readIntLe(head, 12), "original sample rate is informational")
        assertEquals(0, head[16].toInt() or head[17].toInt(), "output gain should be 0 dB")
        assertEquals(0, head[18].toInt(), "channel mapping family 0 covers mono and stereo")
    }

    @Test
    fun `round trips a pre-skip that needs both bytes`() {
        val head = OpusHead.build(channels = 1, preSkip = 3_840, inputSampleRate = 48_000)
        assertEquals(3_840, OpusHead.preSkipOf(head))
    }

    @Test
    fun `reads a plain header straight out of a codec config`() {
        val head = OpusHead.build(channels = 2, preSkip = 120, inputSampleRate = 48_000)
        // Extractors hand over exactly 19 bytes; a decoder may append its own padding.
        val parsed = assertNotNull(OpusHead.fromCodecConfig(head + ByteArray(5)))
        assertEquals(120, OpusHead.preSkipOf(parsed))
        assertEquals(OpusHead.SIZE, parsed.size)
    }

    @Test
    fun `unwraps the marker chunks the platform encoder produces`() {
        val head = OpusHead.build(channels = 2, preSkip = 312, inputSampleRate = 48_000)
        val config = "AOPUSHDR".toByteArray(Charsets.ISO_8859_1) +
            longLe(head.size.toLong()) +
            head +
            "AOPUSDLY".toByteArray(Charsets.ISO_8859_1) + longLe(8) + longLe(6_500_000) +
            "AOPUSPRL".toByteArray(Charsets.ISO_8859_1) + longLe(8) + longLe(80_000_000)

        val parsed = assertNotNull(
            OpusHead.fromCodecConfig(config),
            "the marker-chunk form is what the platform encoder actually emits",
        )
        assertEquals("OpusHead", String(parsed, 0, 8, Charsets.ISO_8859_1))
        assertEquals(312, OpusHead.preSkipOf(parsed))
        assertTrue(parsed.size >= OpusHead.SIZE)
    }

    @Test
    fun `rejects a codec config it cannot make sense of`() {
        assertNull(OpusHead.fromCodecConfig(ByteArray(0)))
        assertNull(OpusHead.fromCodecConfig(ByteArray(40)))
        assertNull(OpusHead.fromCodecConfig("OpusHead".toByteArray(Charsets.ISO_8859_1)))
        // A marker chunk claiming more than the buffer holds must not be trusted.
        assertNull(
            OpusHead.fromCodecConfig(
                "AOPUSHDR".toByteArray(Charsets.ISO_8859_1) + longLe(9_000) + ByteArray(19),
            ),
        )
        // A marker chunk whose payload is not a header either.
        assertNull(
            OpusHead.fromCodecConfig(
                "AOPUSHDR".toByteArray(Charsets.ISO_8859_1) + longLe(19) + ByteArray(19),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Packet durations
    // ------------------------------------------------------------------

    /** A packet whose TOC declares [config] and frame-count code [code]. */
    private fun packet(config: Int, code: Int, frameCount: Int = 0): ByteArray =
        byteArrayOf(((config shl 3) or code).toByte(), frameCount.toByte())

    @Test
    fun `reads the frame length out of the table of contents byte`() {
        // SILK narrowband: 10, 20, 40, 60 ms.
        assertEquals(480, OpusHead.packetSamples(packet(0, 0), 2))
        assertEquals(960, OpusHead.packetSamples(packet(1, 0), 2))
        assertEquals(1_920, OpusHead.packetSamples(packet(2, 0), 2))
        assertEquals(2_880, OpusHead.packetSamples(packet(3, 0), 2))
        // Hybrid: 10 or 20 ms only.
        assertEquals(480, OpusHead.packetSamples(packet(12, 0), 2))
        assertEquals(960, OpusHead.packetSamples(packet(13, 0), 2))
        // CELT fullband, which is what a 256 kbps music encode uses: 2.5, 5, 10, 20 ms.
        assertEquals(120, OpusHead.packetSamples(packet(28, 0), 2))
        assertEquals(960, OpusHead.packetSamples(packet(31, 0), 2))
    }

    @Test
    fun `counts the frames a packet carries`() {
        assertEquals(960, OpusHead.packetSamples(packet(31, 0), 2), "one frame")
        assertEquals(1_920, OpusHead.packetSamples(packet(31, 1), 2), "two equal frames")
        assertEquals(1_920, OpusHead.packetSamples(packet(31, 2), 2), "two unequal frames")
        assertEquals(
            2_880,
            OpusHead.packetSamples(packet(31, 3, frameCount = 3), 2),
            "an arbitrary-frame packet counts them in the second byte",
        )
    }

    @Test
    fun `returns nothing for a packet too short to describe itself`() {
        assertEquals(0, OpusHead.packetSamples(ByteArray(0), 0))
        assertEquals(0, OpusHead.packetSamples(packet(31, 3), 1))
    }

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)
}

/**
 * Tests for reading the downloaded bytes as a media source.
 *
 * The contract has two sharp edges - end of stream is -1, not 0, and a read that overruns
 * the end must be truncated rather than refused - and getting either wrong makes the
 * extractor report a perfectly good download as corrupt.
 */
class ByteArrayReadsTest {

    private val source = ByteArray(100) { it.toByte() }

    @Test
    fun `copies from the requested position`() {
        val destination = ByteArray(10)
        assertEquals(4, ByteArrayReads.readAt(source, 20, destination, 2, 4))
        assertEquals(listOf<Byte>(0, 0, 20, 21, 22, 23, 0, 0, 0, 0), destination.toList())
    }

    @Test
    fun `truncates a read that runs past the end`() {
        val destination = ByteArray(20)
        assertEquals(5, ByteArrayReads.readAt(source, 95, destination, 0, 20))
        assertEquals(listOf<Byte>(95, 96, 97, 98, 99), destination.take(5))
    }

    @Test
    fun `reports end of stream as minus one`() {
        assertEquals(-1, ByteArrayReads.readAt(source, 100, ByteArray(4), 0, 4))
        assertEquals(-1, ByteArrayReads.readAt(source, 500, ByteArray(4), 0, 4))
        assertEquals(-1, ByteArrayReads.readAt(source, -1, ByteArray(4), 0, 4))
    }

    @Test
    fun `treats a zero-length read as nothing to do`() {
        assertEquals(0, ByteArrayReads.readAt(source, 10, ByteArray(4), 0, 0))
    }
}
