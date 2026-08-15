package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.musicbrainz.data.library.TagReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for the Ogg/Opus tagger and the tag reader.
 *
 * As with the MP4 pair, these two have to agree exactly: the identifiers [OggOpusTagger]
 * writes into a `.opus` download are the same ones [TagReader] reads back during a library
 * scan to decide the track is already owned. The re-paging (segment tables, CRC, page
 * renumbering) is exercised here because a mistake there only shows up as a file a player
 * silently rejects, or as downloads that never stop looking missing.
 */
class OggOpusTaggerTest {

    private val serial = 0x0A0B0C0D
    private val audioPayload = ByteArray(500) { (it % 253).toByte() }

    private fun intLe(value: Int) = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)

    /** Builds one Ogg page carrying a single complete packet. */
    private fun oggPage(payload: ByteArray, sequence: Int, headerType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // version
        out.write(headerType)
        out.write(ByteArray(8)) // granule position
        out.write(intLe(serial))
        out.write(intLe(sequence))
        out.write(intLe(0)) // checksum; recomputed by the tagger on output
        val segments = ArrayList<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining)
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload)
        return out.toByteArray()
    }

    private fun opusTags(vararg comments: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        val vendor = "original-vendor".toByteArray(Charsets.UTF_8)
        out.write(intLe(vendor.size))
        out.write(vendor)
        out.write(intLe(comments.size))
        for ((key, value) in comments) {
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            out.write(intLe(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** A minimal but valid Opus stream: OpusHead page, OpusTags page, one audio page. */
    private fun syntheticOpus(): ByteArray {
        val head = "OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11)
        val file = ByteArrayOutputStream()
        file.write(oggPage(head, sequence = 0, headerType = 0x02)) // BOS
        file.write(oggPage(opusTags("TITLE" to "placeholder"), sequence = 1, headerType = 0x00))
        file.write(oggPage(audioPayload, sequence = 2, headerType = 0x00))
        return file.toByteArray()
    }

    private class ParsedPage(
        val headerType: Int,
        val sequence: Int,
        val payload: ByteArray,
    )

    private fun parsePages(buf: ByteArray): List<ParsedPage> {
        val pages = ArrayList<ParsedPage>()
        var offset = 0
        while (offset + 27 <= buf.size && String(buf, offset, 4, Charsets.ISO_8859_1) == "OggS") {
            val headerType = buf[offset + 5].toInt() and 0xff
            val sequence = readIntLe(buf, offset + 18)
            val segmentCount = buf[offset + 26].toInt() and 0xff
            val tableStart = offset + 27
            var payloadSize = 0
            for (i in 0 until segmentCount) payloadSize += buf[tableStart + i].toInt() and 0xff
            val payloadStart = tableStart + segmentCount
            pages.add(
                ParsedPage(
                    headerType,
                    sequence,
                    buf.copyOfRange(payloadStart, payloadStart + payloadSize),
                ),
            )
            offset = payloadStart + payloadSize
        }
        return pages
    }

    @Test
    fun `writes and reads back every tag it supports`() {
        val tagged = assertNotNull(
            OggOpusTagger.tag(
                syntheticOpus(),
                VorbisTags(
                    title = "Midnight Drive",
                    artist = "The Neon Owls",
                    album = "After Hours",
                    albumArtist = "The Neon Owls",
                    date = "2024-03-01",
                    trackNumber = 3,
                    trackTotal = 8,
                    recordingId = "11111111-2222-3333-4444-555555555555",
                    releaseId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    releaseTrackId = "99999999-8888-7777-6666-555555555555",
                ),
            ),
        )

        val tags = TagReader.readOgg(ByteArrayInputStream(tagged))
        assertEquals("Midnight Drive", tags.title)
        assertEquals("The Neon Owls", tags.artist)
        assertEquals("After Hours", tags.album)
        assertEquals("The Neon Owls", tags.albumArtist)
        assertEquals("11111111-2222-3333-4444-555555555555", tags.recordingId)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", tags.releaseId)
        assertEquals("99999999-8888-7777-6666-555555555555", tags.releaseTrackId)
    }

    /**
     * Embedded cover art pushes the comment packet past one page, which is the case the
     * re-paging exists for: the tags still have to read back, and the audio must survive.
     */
    @Test
    fun `spans multiple pages for embedded cover art and preserves the audio`() {
        // Not a real JPEG, but large enough to force the OpusTags packet across pages.
        val cover = ByteArray(200_000) { (it % 256).toByte() }
        val tagged = assertNotNull(
            OggOpusTagger.tag(
                syntheticOpus(),
                VorbisTags(
                    title = "Bodysnatchers",
                    artist = "Radiohead",
                    coverArt = cover,
                ),
            ),
        )

        val tags = TagReader.readOgg(ByteArrayInputStream(tagged))
        assertEquals("Bodysnatchers", tags.title)
        assertEquals("Radiohead", tags.artist)

        val pages = parsePages(tagged)
        // Head + several comment pages + one audio page.
        assertTrue(pages.size > 3, "cover art should spread the comment header over pages")

        // Page sequence numbers stay contiguous from 0.
        pages.forEachIndexed { index, page -> assertEquals(index, page.sequence) }

        // The OpusHead page is left untouched, still flagged beginning-of-stream.
        assertEquals(0x02, pages.first().headerType)

        // The audio packet is copied through byte-for-byte.
        assertContentEqualsPayload(audioPayload, pages.last().payload)
    }

    private fun assertContentEqualsPayload(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "audio payload size changed")
        assertTrue(expected.contentEquals(actual), "audio payload bytes changed")
    }

    @Test
    fun `returns null for input that is not an ogg stream`() {
        assertNull(OggOpusTagger.tag("not an ogg file at all".toByteArray(), VorbisTags(title = "x")))
    }

    @Test
    fun `omits tags that were not supplied`() {
        val tagged = assertNotNull(OggOpusTagger.tag(syntheticOpus(), VorbisTags(title = "Only A Title")))
        val tags = TagReader.readOgg(ByteArrayInputStream(tagged))
        assertEquals("Only A Title", tags.title)
        assertNull(tags.artist)
        assertNull(tags.recordingId)
    }
}

