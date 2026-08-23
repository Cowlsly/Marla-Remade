package com.vayunmathur.library.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Everything the Ogg/Opus tagger will write. Null/blank fields are simply omitted.
 *
 * No release-track id: the catalogue carries no per-track MBID, so there was never a value
 * to write. Readers still parse that tag, because files downloaded before the switch carry
 * it and have to keep matching.
 */
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
    val coverArt: ByteArray? = null,
    val coverIsPng: Boolean = false,
)

/**
 * Rewrites the comment header of an Ogg/Opus file with Vorbis comments.
 *
 * A `.opus` file is a chain of Ogg pages: the first carries the `OpusHead` identification
 * packet, the second carries the `OpusTags` comment packet, and the rest carry audio. This
 * replaces that comment packet with one holding the track's metadata - the standard keys a
 * tag reader looks for, so a download is recognised as owned on the next library scan - plus
 * the cover art and synced lyrics.
 *
 * Embedded art can push the comment packet past a single Ogg page, so the packet is
 * re-paged from scratch: segment tables are rebuilt, the per-page CRC is recomputed, and
 * the audio pages that follow are renumbered to keep the page sequence contiguous. Without
 * exact re-paging a player would reject the file, so this is covered by a round-trip test.
 */
object OggOpusTagger {

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
        val commentPages = OggPages.forPacket(
            packet = packet,
            serialNumber = serial,
            startSequence = 1,
            granulePosition = 0L,
            firstHeaderType = 0,
        )

        val out = ByteArrayOutputStream(source.size + packet.size)
        out.write(source, head.start, head.end - head.start)
        commentPages.forEach { out.write(it) }

        // Renumber the audio pages so the sequence stays contiguous after a comment packet
        // that may now span a different number of pages, and recompute their CRCs.
        var sequence = 1 + commentPages.size
        for (i in commentEnd + 1 until pages.size) {
            val page = source.copyOfRange(pages[i].start, pages[i].end)
            OggPages.writeIntLe(page, 18, sequence)
            OggPages.setChecksum(page)
            out.write(page)
            sequence++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Comment packet
    // ------------------------------------------------------------------

    /** An Opus comment packet: the `OpusTags` magic followed by the shared comment list. */
    fun buildOpusTagsPacket(tags: VorbisTags): ByteArray =
        "OpusTags".toByteArray(Charsets.ISO_8859_1) + VorbisComments.buildCommentList(tags)

    // ------------------------------------------------------------------
    // Ogg pages
    // ------------------------------------------------------------------

    private class Page(val start: Int, val end: Int, val segmentCount: Int)

    private fun parsePages(buf: ByteArray): List<Page>? {
        val pages = ArrayList<Page>()
        var offset = 0
        while (offset + OggPages.HEADER_SIZE <= buf.size) {
            if (String(buf, offset, 4, Charsets.ISO_8859_1) != "OggS") break
            val segmentCount = buf[offset + 26].toInt() and 0xff
            val tableStart = offset + OggPages.HEADER_SIZE
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

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

