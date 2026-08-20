package com.vayunmathur.musicbrainz.data.download

import java.io.ByteArrayInputStream
import com.vayunmathur.musicbrainz.data.library.TagReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Ogg container the transcoder writes from scratch.
 *
 * Nothing here can be checked on a device from a unit test, and a malformed page is the kind
 * of mistake a player rejects silently, so the framing is pinned down byte by byte: the
 * magic, the flags, contiguous page sequence numbers, the granule positions that decide the
 * reported duration, and a CRC that a reader will actually verify. The strongest check is the
 * last one - a stream built here, retagged by [OggOpusTagger] and read back by [TagReader] -
 * because that is exactly the path a download takes.
 */
class OggStreamWriterTest {

    private val serial = 0x1A2B3C4D

    private class ParsedPage(
        val headerType: Int,
        val granulePosition: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: List<Int>,
        val payload: ByteArray,
        val checksumValid: Boolean,
    ) {
        /** Splits the payload back into packets using the lacing table. */
        val packets: List<ByteArray>
            get() {
                val out = ArrayList<ByteArray>()
                var at = 0
                var start = 0
                for (value in lacing) {
                    at += value
                    if (value < 255) {
                        out.add(payload.copyOfRange(start, at))
                        start = at
                    }
                }
                return out
            }
    }

    private fun parse(stream: ByteArray): List<ParsedPage> {
        val pages = ArrayList<ParsedPage>()
        var offset = 0
        while (offset + 27 <= stream.size) {
            assertEquals("OggS", String(stream, offset, 4, Charsets.ISO_8859_1), "page at $offset")
            val segmentCount = stream[offset + 26].toInt() and 0xff
            val lacing = (0 until segmentCount).map { stream[offset + 27 + it].toInt() and 0xff }
            val payloadStart = offset + 27 + segmentCount
            val payloadSize = lacing.sum()
            val end = payloadStart + payloadSize

            val whole = stream.copyOfRange(offset, end)
            val stated = readIntLe(whole, 22)
            for (i in 22..25) whole[i] = 0

            pages.add(
                ParsedPage(
                    headerType = stream[offset + 5].toInt() and 0xff,
                    granulePosition = readLongLe(stream, offset + 6),
                    serial = readIntLe(stream, offset + 14),
                    sequence = readIntLe(stream, offset + 18),
                    lacing = lacing,
                    payload = stream.copyOfRange(payloadStart, end),
                    checksumValid = OggPages.crc(whole) == stated,
                ),
            )
            offset = end
        }
        return pages
    }

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)

    private fun readLongLe(buf: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((buf[offset + i].toLong() and 0xff) shl (8 * i))
        return value
    }

    /** A 20 ms stereo CELT packet of [size] bytes, which is the shape the encoder emits. */
    private fun opusPacket(size: Int, fill: Int = 0x5A): ByteArray =
        ByteArray(size) { if (it == 0) 0xFC.toByte() else (fill + it).toByte() }

    /**
     * Builds a stream of [packets] 20 ms packets, as the transcoder does.
     *
     * A granule position counts every sample a decoder produces, pre-skip included, because
     * the pre-skip samples are the first ones it decodes and then discards.
     */
    private fun stream(packets: Int, packetSize: Int = 640, channels: Int = 2): ByteArray {
        val writer = OggStreamWriter(serial)
        writer.writeHeaderPacket(OpusHead.build(channels, OpusHead.DEFAULT_PRE_SKIP, 48_000))
        writer.writeHeaderPacket(OggOpusTagger.buildOpusTagsPacket(VorbisTags()))
        var samples = 0L
        repeat(packets) {
            samples += 960
            writer.writeAudioPacket(opusPacket(packetSize), samples)
        }
        return writer.finish(samples)
    }

    // ------------------------------------------------------------------

    @Test
    fun `opens with the two header packets on pages of their own`() {
        val pages = parse(stream(packets = 3))
        assertTrue(pages.size >= 3, "head, tags and audio need at least three pages")

        assertEquals(OggPages.FLAG_BEGIN_OF_STREAM, pages[0].headerType)
        assertEquals(1, pages[0].packets.size, "the head packet must not share its page")
        assertEquals("OpusHead", String(pages[0].payload, 0, 8, Charsets.ISO_8859_1))
        assertEquals(0L, pages[0].granulePosition, "a header page has no granule position")

        assertEquals(0, pages[1].headerType)
        assertEquals("OpusTags", String(pages[1].payload, 0, 8, Charsets.ISO_8859_1))
        assertEquals(0L, pages[1].granulePosition)
    }

    @Test
    fun `numbers pages contiguously and stamps them all with one serial`() {
        val pages = parse(stream(packets = 300))
        pages.forEachIndexed { index, page ->
            assertEquals(index, page.sequence, "page $index is out of sequence")
            assertEquals(serial, page.serial, "page $index belongs to another stream")
        }
    }

    @Test
    fun `checksums every page it writes`() {
        parse(stream(packets = 120)).forEachIndexed { index, page ->
            assertTrue(page.checksumValid, "page $index has a bad CRC and would be dropped")
        }
    }

    @Test
    fun `flags only the last page end of stream`() {
        val pages = parse(stream(packets = 200))
        pages.dropLast(1).forEachIndexed { index, page ->
            assertEquals(0, page.headerType and OggPages.FLAG_END_OF_STREAM, "page $index")
        }
        assertEquals(
            OggPages.FLAG_END_OF_STREAM,
            pages.last().headerType and OggPages.FLAG_END_OF_STREAM,
            "without an end-of-stream flag a reader treats the file as truncated",
        )
    }

    @Test
    fun `records the granule position of the last packet to finish on each page`() {
        val packets = 200
        val pages = parse(stream(packets = packets))
        val audio = pages.drop(2)

        var seen = 0
        var previous = 0L
        for (page in audio) {
            seen += page.packets.size
            assertEquals(
                seen * 960L,
                page.granulePosition,
                "granule position decides the duration a player reports",
            )
            assertTrue(
                page.granulePosition > previous,
                "a granule position that goes backwards reads as a corrupt stream",
            )
            previous = page.granulePosition
        }
        assertEquals(packets, seen, "every packet must reach a page")
    }

    @Test
    fun `groups audio packets rather than paging each one`() {
        val audio = parse(stream(packets = 200)).drop(2)
        assertTrue(
            audio.all { it.packets.size > 1 },
            "one packet per page would treble the container overhead",
        )
        assertTrue(
            audio.all { it.lacing.size <= OggPages.MAX_SEGMENTS },
            "a page cannot carry more than 255 segments",
        )
    }

    @Test
    fun `keeps the packets byte for byte`() {
        val expected = opusPacket(640)
        val recovered = parse(stream(packets = 40)).drop(2).flatMap { it.packets }
        assertEquals(40, recovered.size)
        assertTrue(recovered.all { it.contentEquals(expected) }, "a packet was altered in paging")
    }

    @Test
    fun `pages a packet that will not fit in one page`() {
        // 255 segments hold 65025 bytes at most, so this has to be continued onto a second
        // page - the case embedded cover art hits in the comment header.
        val writer = OggStreamWriter(serial)
        val big = ByteArray(70_000) { (it % 251).toByte() }
        writer.writeHeaderPacket(big)
        val pages = parse(writer.finish(0L))

        assertTrue(pages.size > 1, "an oversized packet must span pages")
        assertEquals(OggPages.FLAG_BEGIN_OF_STREAM, pages[0].headerType)
        assertEquals(
            OggPages.FLAG_CONTINUED,
            pages[1].headerType and OggPages.FLAG_CONTINUED,
            "a page that starts mid-packet must say so",
        )
        val rejoined = pages.dropLast(1).fold(ByteArray(0)) { acc, page -> acc + page.payload }
        assertTrue(rejoined.contentEquals(big), "the packet did not survive being split")
    }

    @Test
    fun `lets the final position trim the encoder's padding`() {
        // The encoder pads its last frame out to a whole 20 ms, so the last page reports the
        // real length instead of the padded one and a decoder drops the difference. Reporting
        // the padded length is how a track ends with a fraction of a second of silence.
        val writer = OggStreamWriter(serial)
        writer.writeHeaderPacket(OpusHead.build(2, OpusHead.DEFAULT_PRE_SKIP, 48_000))
        writer.writeHeaderPacket(OggOpusTagger.buildOpusTagsPacket(VorbisTags()))
        repeat(3) { writer.writeAudioPacket(opusPacket(640), (it + 1) * 960L) }
        val trimmed = 2_400L
        val pages = parse(writer.finish(trimmed))

        assertEquals(trimmed, pages.last().granulePosition)
        assertTrue(
            pages.last().granulePosition < 3 * 960L,
            "the reported length should be under what the packets decode to",
        )
    }

    @Test
    fun `writes a stream the tagger can retag and the library can read`() {
        val tagged = assertNotNull(
            OggOpusTagger.tag(
                stream(packets = 50),
                VorbisTags(
                    title = "Weightless",
                    artist = "Marconi Union",
                    album = "Ambient Transmissions",
                    recordingId = "12345678-1234-1234-1234-123456789abc",
                ),
            ),
            "the tagger should recognise a stream this writer produced",
        )

        val tags = TagReader.readOgg(ByteArrayInputStream(tagged))
        assertEquals("Weightless", tags.title)
        assertEquals("Marconi Union", tags.artist)
        assertEquals("Ambient Transmissions", tags.album)
        assertEquals("12345678-1234-1234-1234-123456789abc", tags.recordingId)

        // Retagging must leave the audio pages valid, or the file plays the tags and nothing
        // else. Page numbering and CRCs are recomputed, so both are checked again.
        val pages = parse(tagged)
        pages.forEachIndexed { index, page ->
            assertEquals(index, page.sequence, "page $index is out of sequence after retagging")
            assertTrue(page.checksumValid, "page $index has a bad CRC after retagging")
        }
        assertEquals(
            50 * 960L,
            pages.last().granulePosition,
            "retagging must not change the stream's length",
        )
    }

    @Test
    fun `builds the lacing table so a reader can find the packet boundary`() {
        assertEquals(listOf(0), OggPages.lacingFor(0))
        assertEquals(listOf(1), OggPages.lacingFor(1))
        assertEquals(listOf(254), OggPages.lacingFor(254))
        // A length that is an exact multiple of 255 needs a trailing zero-length segment,
        // otherwise the packet reads as continuing onto the next page.
        assertEquals(listOf(255, 0), OggPages.lacingFor(255))
        assertEquals(listOf(255, 255, 0), OggPages.lacingFor(510))
        assertEquals(listOf(255, 130), OggPages.lacingFor(385))
    }

    @Test
    fun `refuses to retag something that is not an ogg stream`() {
        assertNull(OggOpusTagger.tag(ByteArray(100), VorbisTags(title = "x")))
    }
}
