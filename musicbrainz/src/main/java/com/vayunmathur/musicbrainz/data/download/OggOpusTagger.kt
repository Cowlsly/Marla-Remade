package com.vayunmathur.musicbrainz.data.download

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Everything the Ogg/Opus tagger will write. Null/blank fields are simply omitted. */
data class VorbisTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val date: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val lyrics: String? = null,
    val recordingId: String? = null,
    val releaseId: String? = null,
    val releaseTrackId: String? = null,
    val coverArt: ByteArray? = null,
    val coverIsPng: Boolean = false,
)

/**
 * Rewrites the comment header of an Ogg/Opus file with Vorbis comments.
 *
 * A `.opus` file is a chain of Ogg pages: the first carries the `OpusHead` identification
 * packet, the second carries the `OpusTags` comment packet, and the rest carry audio. This
 * replaces that comment packet with one holding the track's metadata - the same keys
 * [com.vayunmathur.musicbrainz.data.library.TagReader] reads back, so a download is recognised
 * as owned on the next library scan - plus the cover art and synced lyrics.
 *
 * Embedded art can push the comment packet past a single Ogg page, so the packet is
 * re-paged from scratch: segment tables are rebuilt, the per-page CRC is recomputed, and
 * the audio pages that follow are renumbered to keep the page sequence contiguous. Without
 * exact re-paging a player would reject the file, so this is covered by a round-trip test.
 */
object OggOpusTagger {

    private const val VENDOR = "ModernApps musicbrainz"
    private const val MAX_SEGMENTS_PER_PAGE = 255

    /**
     * Returns the retagged bytes, or null when the input is not an Ogg/Opus file this can
     * rewrite. Callers fall back to the original bytes: an untagged file is still worth
     * keeping.
     */
    fun tag(source: ByteArray, tags: VorbisTags): ByteArray? {
        val pages = parsePages(source) ?: return null
        if (pages.size < 2) return null

        // Page 0 is the OpusHead identification packet (BOS); leave it untouched.
        val head = pages[0]

        // The comment packet finishes the page it ends on, so its last page is the first
        // one after the head whose final lacing value is under 255.
        var commentEnd = -1
        for (i in 1 until pages.size) {
            val page = pages[i]
            if (page.segmentCount == 0) return null
            val lastLacing = source[page.start + 27 + page.segmentCount - 1].toInt() and 0xff
            if (lastLacing < 255) {
                commentEnd = i
                break
            }
        }
        if (commentEnd == -1) return null

        val serial = readIntLe(source, head.start + 14)
        val packet = buildOpusTagsPacket(tags)
        val commentPages = buildPages(packet, serial, startSequence = 1)

        val out = ByteArrayOutputStream(source.size + packet.size)
        out.write(source, head.start, head.end - head.start)
        commentPages.forEach { out.write(it) }

        // Renumber the audio pages so the sequence stays contiguous after a comment packet
        // that may now span a different number of pages, and recompute their CRCs.
        var sequence = 1 + commentPages.size
        for (i in commentEnd + 1 until pages.size) {
            val page = source.copyOfRange(pages[i].start, pages[i].end)
            writeIntLe(page, 18, sequence)
            setChecksum(page)
            out.write(page)
            sequence++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Comment packet
    // ------------------------------------------------------------------

    private fun buildOpusTagsPacket(tags: VorbisTags): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        out.write(intLe(vendor.size))
        out.write(vendor)

        val comments = ArrayList<ByteArray>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) {
                comments.add("$key=$value".toByteArray(Charsets.UTF_8))
            }
        }
        // Text tags first, so a reader with a bounded scan window sees the identifying
        // fields before the much larger cover-art comment.
        add("TITLE", tags.title)
        add("ARTIST", tags.artist)
        add("ALBUM", tags.album)
        add("ALBUMARTIST", tags.albumArtist)
        add("DATE", tags.date)
        tags.trackNumber?.let { add("TRACKNUMBER", it.toString()) }
        tags.trackTotal?.let { add("TRACKTOTAL", it.toString()) }
        tags.discNumber?.let { add("DISCNUMBER", it.toString()) }
        add("MUSICBRAINZ_TRACKID", tags.recordingId)
        add("MUSICBRAINZ_ALBUMID", tags.releaseId)
        add("MUSICBRAINZ_RELEASETRACKID", tags.releaseTrackId)
        add("LYRICS", tags.lyrics)
        tags.coverArt?.takeIf { it.isNotEmpty() }?.let {
            add("METADATA_BLOCK_PICTURE", encodePicture(it, tags.coverIsPng))
        }

        out.write(intLe(comments.size))
        for (comment in comments) {
            out.write(intLe(comment.size))
            out.write(comment)
        }
        return out.toByteArray()
    }

    /**
     * Wraps the image in a FLAC picture block and base64-encodes it, which is the form the
     * `METADATA_BLOCK_PICTURE` comment expects. Every field in the block is big-endian; the
     * dimensions are left at zero, which players read from the image itself.
     */
    private fun encodePicture(image: ByteArray, isPng: Boolean): String {
        val mime = (if (isPng) "image/png" else "image/jpeg").toByteArray(Charsets.ISO_8859_1)
        val block = ByteArrayOutputStream()
        block.write(intBe(3)) // picture type: front cover
        block.write(intBe(mime.size))
        block.write(mime)
        block.write(intBe(0)) // description length
        block.write(intBe(0)) // width
        block.write(intBe(0)) // height
        block.write(intBe(0)) // colour depth
        block.write(intBe(0)) // indexed colours
        block.write(intBe(image.size))
        block.write(image)
        return Base64.getEncoder().encodeToString(block.toByteArray())
    }

    // ------------------------------------------------------------------
    // Ogg pages
    // ------------------------------------------------------------------

    private class Page(val start: Int, val end: Int, val segmentCount: Int)

    private fun parsePages(buf: ByteArray): List<Page>? {
        val pages = ArrayList<Page>()
        var offset = 0
        while (offset + 27 <= buf.size) {
            if (String(buf, offset, 4, Charsets.ISO_8859_1) != "OggS") break
            val segmentCount = buf[offset + 26].toInt() and 0xff
            val tableStart = offset + 27
            if (tableStart + segmentCount > buf.size) return null
            var payload = 0
            for (i in 0 until segmentCount) payload += buf[tableStart + i].toInt() and 0xff
            val end = tableStart + segmentCount + payload
            if (end > buf.size) return null
            pages.add(Page(offset, end, segmentCount))
            offset = end
        }
        return pages.takeIf { it.isNotEmpty() }
    }

    /** Splits a single packet into as many pages as its length needs, up to 255 segments each. */
    private fun buildPages(packet: ByteArray, serial: Int, startSequence: Int): List<ByteArray> {
        val lacing = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) {
            lacing.add(255)
            remaining -= 255
        }
        lacing.add(remaining) // final segment is always < 255, marking the packet's end.

        val pages = ArrayList<ByteArray>()
        var lacingIndex = 0
        var payloadOffset = 0
        var sequence = startSequence
        var first = true
        while (lacingIndex < lacing.size) {
            val segmentCount = minOf(MAX_SEGMENTS_PER_PAGE, lacing.size - lacingIndex)
            val segments = lacing.subList(lacingIndex, lacingIndex + segmentCount)
            val payloadSize = segments.sum()
            pages.add(
                buildPage(
                    headerType = if (first) 0x00 else 0x01, // 0x01 marks a continued packet
                    serial = serial,
                    sequence = sequence,
                    segments = segments,
                    payload = packet,
                    payloadOffset = payloadOffset,
                    payloadSize = payloadSize,
                ),
            )
            lacingIndex += segmentCount
            payloadOffset += payloadSize
            sequence++
            first = false
        }
        return pages
    }

    private fun buildPage(
        headerType: Int,
        serial: Int,
        sequence: Int,
        segments: List<Int>,
        payload: ByteArray,
        payloadOffset: Int,
        payloadSize: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(27 + segments.size + payloadSize)
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // stream structure version
        out.write(headerType)
        out.write(ByteArray(8)) // granule position: 0 for a header page
        out.write(intLe(serial))
        out.write(intLe(sequence))
        out.write(ByteArray(4)) // CRC placeholder, filled in below
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload, payloadOffset, payloadSize)
        val bytes = out.toByteArray()
        setChecksum(bytes)
        return bytes
    }

    /** Zeroes the checksum field, computes the Ogg CRC over the whole page, and writes it back. */
    private fun setChecksum(page: ByteArray) {
        page[22] = 0
        page[23] = 0
        page[24] = 0
        page[25] = 0
        writeIntLe(page, 22, oggCrc(page))
    }

    // ------------------------------------------------------------------
    // Ogg CRC-32 (poly 0x04C11DB7, no reflection, no final xor)
    // ------------------------------------------------------------------

    private val crcTable = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var crc = i shl 24
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04c11db7
                } else {
                    crc shl 1
                }
            }
            table[i] = crc
        }
    }

    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val index = ((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff
            crc = (crc shl 8) xor crcTable[index]
        }
        return crc
    }

    // ------------------------------------------------------------------

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun intBe(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun writeIntLe(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
        buf[offset + 2] = (value ushr 16).toByte()
        buf[offset + 3] = (value ushr 24).toByte()
    }
}

