package com.vayunmathur.musicbrainz.data.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the hand-rolled tag parsers.
 *
 * These decide whether the app thinks the user already owns a track, and they are parsing
 * three unrelated binary formats without a library, so the byte-level details - synchsafe
 * sizes, UTF-16 text frames, Ogg page segmentation - are covered directly. Getting any of
 * them subtly wrong shows up only as tracks that never stop looking missing.
 */
class TagReaderTest {

    // ------------------------------------------------------------------
    // ID3v2 (MP3)
    // ------------------------------------------------------------------

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7f).toByte(),
        ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )

    private fun plainInt(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun id3(major: Int, frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0)
        out.write(0) // flags
        out.write(synchsafe(frames.size))
        out.write(frames)
        return out.toByteArray()
    }

    private fun frame(id: String, body: ByteArray, major: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major >= 4) synchsafe(body.size) else plainInt(body.size))
        out.write(byteArrayOf(0, 0)) // frame flags
        out.write(body)
        return out.toByteArray()
    }

    /** Encoding byte 3 is UTF-8. */
    private fun textBody(text: String) = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)

    private fun txxxBody(description: String, value: String) =
        byteArrayOf(3) + description.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            value.toByteArray(Charsets.UTF_8)

    @Test
    fun `reads id3v2_4 text frames and musicbrainz identifiers`() {
        val frames = ByteArrayOutputStream()
        frames.write(frame("TIT2", textBody("Weird Fishes"), 4))
        frames.write(frame("TPE1", textBody("Radiohead"), 4))
        frames.write(frame("TALB", textBody("In Rainbows"), 4))
        frames.write(frame("TPE2", textBody("Radiohead"), 4))
        frames.write(
            frame("TXXX", txxxBody("MusicBrainz Album Id", "release-mbid-here"), 4),
        )
        frames.write(
            frame("TXXX", txxxBody("MusicBrainz Release Track Id", "track-mbid-here"), 4),
        )
        frames.write(
            frame(
                "UFID",
                "http://musicbrainz.org".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                    "recording-mbid-here".toByteArray(Charsets.ISO_8859_1),
                4,
            ),
        )

        val tags = TagReader.readId3(ByteArrayInputStream(id3(4, frames.toByteArray())))
        assertEquals("Weird Fishes", tags.title)
        assertEquals("Radiohead", tags.artist)
        assertEquals("In Rainbows", tags.album)
        assertEquals("Radiohead", tags.albumArtist)
        assertEquals("release-mbid-here", tags.releaseId)
        assertEquals("track-mbid-here", tags.releaseTrackId)
        assertEquals("recording-mbid-here", tags.recordingId)
    }

    /** v2.3 sizes are plain big-endian, not synchsafe. Reading them the v2.4 way truncates. */
    @Test
    fun `reads id3v2_3 frames whose sizes are not synchsafe`() {
        val frames = ByteArrayOutputStream()
        frames.write(frame("TIT2", textBody("Nude"), 3))
        frames.write(frame("TPE1", textBody("Radiohead"), 3))

        val tags = TagReader.readId3(ByteArrayInputStream(id3(3, frames.toByteArray())))
        assertEquals("Nude", tags.title)
        assertEquals("Radiohead", tags.artist)
    }

    /** UTF-16 frames are the common case for non-ASCII titles from Windows taggers. */
    @Test
    fun `decodes utf16 text frames`() {
        val body = byteArrayOf(1) + "Björk".toByteArray(Charsets.UTF_16)
        val tags = TagReader.readId3(
            ByteArrayInputStream(id3(4, frame("TPE1", body, 4))),
        )
        assertEquals("Björk", tags.artist)
    }

    @Test
    fun `returns nothing for a file with no id3 header`() {
        val tags = TagReader.readId3(ByteArrayInputStream(ByteArray(64) { 0xff.toByte() }))
        assertTrue(tags.isEmpty)
    }

    // ------------------------------------------------------------------
    // Vorbis comments (FLAC and Ogg)
    // ------------------------------------------------------------------

    private fun intLe(value: Int) = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun vorbisComments(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        out.write(intLe(vendor.size))
        out.write(vendor)
        out.write(intLe(entries.size))
        for ((key, value) in entries) {
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            out.write(intLe(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    @Test
    fun `reads flac vorbis comments after skipping other metadata blocks`() {
        val comments = vorbisComments(
            "TITLE" to "Reckoner",
            "ARTIST" to "Radiohead",
            "ALBUM" to "In Rainbows",
            "MUSICBRAINZ_TRACKID" to "recording-mbid",
            "MUSICBRAINZ_ALBUMID" to "release-mbid",
        )
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        // STREAMINFO (type 0) comes first in every real FLAC file and must be skipped.
        val streamInfo = ByteArray(34)
        out.write(0)
        out.write(byteArrayOf(0, 0, streamInfo.size.toByte()))
        out.write(streamInfo)
        // VORBIS_COMMENT (type 4), flagged last.
        out.write(0x80 or 4)
        out.write(
            byteArrayOf(
                (comments.size shr 16).toByte(),
                (comments.size shr 8).toByte(),
                comments.size.toByte(),
            ),
        )
        out.write(comments)

        val tags = TagReader.readFlac(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Reckoner", tags.title)
        assertEquals("Radiohead", tags.artist)
        assertEquals("In Rainbows", tags.album)
        assertEquals("recording-mbid", tags.recordingId)
        assertEquals("release-mbid", tags.releaseId)
    }

    @Test
    fun `ignores a flac file with no comment block`() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        out.write(0x80) // STREAMINFO, flagged last
        out.write(byteArrayOf(0, 0, 34))
        out.write(ByteArray(34))
        assertTrue(TagReader.readFlac(ByteArrayInputStream(out.toByteArray())).isEmpty)
    }

    /** Builds one Ogg page carrying a single complete packet. */
    private fun oggPage(payload: ByteArray, sequence: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // version
        out.write(if (sequence == 0) 0x02 else 0) // header type
        out.write(ByteArray(8)) // granule position
        out.write(intLe(1)) // serial
        out.write(intLe(sequence))
        out.write(intLe(0)) // checksum, not verified by the parser
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

    @Test
    fun `reads opus tags from the second ogg packet`() {
        val identification = "OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11)
        val comments = "OpusTags".toByteArray(Charsets.ISO_8859_1) + vorbisComments(
            "TITLE" to "Videotape",
            "ARTIST" to "Radiohead",
            "MUSICBRAINZ_RELEASETRACKID" to "release-track-mbid",
        )
        val file = ByteArrayOutputStream()
        file.write(oggPage(identification, 0))
        file.write(oggPage(comments, 1))

        val tags = TagReader.readOgg(ByteArrayInputStream(file.toByteArray()))
        assertEquals("Videotape", tags.title)
        assertEquals("Radiohead", tags.artist)
        assertEquals("release-track-mbid", tags.releaseTrackId)
    }

    /** A packet spanning more than one page still has to be reassembled before parsing. */
    @Test
    fun `reassembles an opus comment packet split across pages`() {
        val identification = "OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11)
        val comments = "OpusTags".toByteArray(Charsets.ISO_8859_1) + vorbisComments(
            "TITLE" to "Bodysnatchers",
            "ARTIST" to "Radiohead",
            // Padded so the packet has to run past a single 255-byte segment boundary.
            "DESCRIPTION" to "x".repeat(600),
        )
        val file = ByteArrayOutputStream()
        file.write(oggPage(identification, 0))
        file.write(oggPage(comments, 1))

        val tags = TagReader.readOgg(ByteArrayInputStream(file.toByteArray()))
        assertEquals("Bodysnatchers", tags.title)
        assertEquals("Radiohead", tags.artist)
    }

    @Test
    fun `ignores bytes that are not an ogg stream`() {
        assertTrue(TagReader.readOgg(ByteArrayInputStream("not ogg".toByteArray())).isEmpty)
    }

    // ------------------------------------------------------------------

    @Test
    fun `recognises audio files by extension`() {
        assertTrue(TagReader.isAudioFile("01 Nude.mp3"))
        assertTrue(TagReader.isAudioFile("track.FLAC"))
        assertTrue(TagReader.isAudioFile("song.opus"))
        assertTrue(!TagReader.isAudioFile("cover.jpg"))
        assertTrue(!TagReader.isAudioFile("no-extension"))
    }

    @Test
    fun `treats a tagless read as empty`() {
        assertNull(AudioTags().title)
        assertTrue(AudioTags().isEmpty)
        assertTrue(!AudioTags(title = "x").isEmpty)
    }
}

