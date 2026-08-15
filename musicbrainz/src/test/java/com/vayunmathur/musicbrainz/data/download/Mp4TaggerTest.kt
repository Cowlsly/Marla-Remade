package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.musicbrainz.data.library.TagReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for the MP4 tagger and the tag reader.
 *
 * These two have to agree exactly: the identifiers [Mp4Tagger] writes into a download are
 * the same ones [TagReader] reads back during a library scan to decide the track is
 * already owned. A mismatch would silently make every download look missing forever, and
 * that is not something the UI would reveal.
 */
class Mp4TaggerTest {

    private fun box(type: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBytes(content.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(content)
        return out.toByteArray()
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 24) or
            ((buf[offset + 1].toInt() and 0xff) shl 16) or
            ((buf[offset + 2].toInt() and 0xff) shl 8) or
            (buf[offset + 3].toInt() and 0xff)

    /**
     * A minimal faststart MP4: `ftyp`, then `moov` holding one chunk offset table, then
     * `mdat`. This is the layout that makes tagging risky, because growing `moov` moves
     * the audio and invalidates the offsets.
     */
    private fun syntheticMp4(chunkOffsets: List<Int>): Pair<ByteArray, Int> {
        val stcoContent = ByteArrayOutputStream()
        stcoContent.write(intBytes(0)) // version and flags
        stcoContent.write(intBytes(chunkOffsets.size))
        chunkOffsets.forEach { stcoContent.write(intBytes(it)) }
        val stbl = box("stbl", box("stco", stcoContent.toByteArray()))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
        val mdat = box("mdat", ByteArray(512) { (it % 251).toByte() })

        val file = ByteArrayOutputStream()
        file.write(ftyp)
        file.write(moov)
        file.write(mdat)
        return file.toByteArray() to moov.size
    }

    private fun readBack(bytes: ByteArray): com.vayunmathur.musicbrainz.data.library.AudioTags {
        val temp = File.createTempFile("tagger", ".m4a")
        try {
            temp.writeBytes(bytes)
            RandomAccessFile(temp, "r").use { return TagReader.readMp4(it.channel) }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `writes and reads back every tag it supports`() {
        val (source, _) = syntheticMp4(listOf(1000))
        val tagged = Mp4Tagger.tag(
            source,
            Mp4Tags(
                title = "Midnight Drive",
                artist = "The Neon Owls",
                album = "After Hours",
                albumArtist = "The Neon Owls",
                date = "2024-03-01",
                trackNumber = 3,
                trackTotal = 8,
                freeform = mapOf(
                    "MusicBrainz Track Id" to "11111111-2222-3333-4444-555555555555",
                    "MusicBrainz Album Id" to "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "MusicBrainz Release Track Id" to "99999999-8888-7777-6666-555555555555",
                ),
            ),
        )
        assertNotNull(tagged)

        val tags = readBack(tagged)
        assertEquals("Midnight Drive", tags.title)
        assertEquals("The Neon Owls", tags.artist)
        assertEquals("After Hours", tags.album)
        assertEquals("The Neon Owls", tags.albumArtist)
        assertEquals("11111111-2222-3333-4444-555555555555", tags.recordingId)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", tags.releaseId)
        assertEquals("99999999-8888-7777-6666-555555555555", tags.releaseTrackId)
    }

    /**
     * The whole point of the `stco` fixup: the audio has moved, so the offsets pointing at
     * it must move by exactly the same amount or the file plays silence.
     */
    @Test
    fun `shifts chunk offsets by the amount moov grew`() {
        val originalOffsets = listOf(1000, 2000, 3000)
        val (source, originalMoovSize) = syntheticMp4(originalOffsets)
        val tagged = assertNotNull(Mp4Tagger.tag(source, Mp4Tags(title = "Test")))

        val moovStart = readInt(source, 0) // ftyp size; moov begins right after
        val newMoovSize = readInt(tagged, moovStart)
        val delta = newMoovSize - originalMoovSize
        assertTrue(delta > 0, "tagging should have grown moov")

        val shifted = findChunkOffsets(tagged)
        assertContentEquals(originalOffsets.map { it + delta }, shifted)
    }

    /** Offsets must be left alone when `moov` already sits after the audio. */
    @Test
    fun `leaves chunk offsets alone when moov follows mdat`() {
        val originalOffsets = listOf(64, 128)
        val stcoContent = ByteArrayOutputStream()
        stcoContent.write(intBytes(0))
        stcoContent.write(intBytes(originalOffsets.size))
        originalOffsets.forEach { stcoContent.write(intBytes(it)) }
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stco", stcoContent.toByteArray()))))))
        val file = ByteArrayOutputStream()
        file.write(box("ftyp", "isom".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)))
        file.write(box("mdat", ByteArray(256)))
        file.write(moov)

        val tagged = assertNotNull(Mp4Tagger.tag(file.toByteArray(), Mp4Tags(title = "Test")))
        assertContentEquals(originalOffsets, findChunkOffsets(tagged))
    }

    @Test
    fun `returns null for input that is not an mp4`() {
        assertNull(Mp4Tagger.tag("not an mp4 at all, just bytes".toByteArray(), Mp4Tags(title = "x")))
    }

    @Test
    fun `omits tags that were not supplied`() {
        val (source, _) = syntheticMp4(listOf(1000))
        val tagged = assertNotNull(Mp4Tagger.tag(source, Mp4Tags(title = "Only A Title")))
        val tags = readBack(tagged)
        assertEquals("Only A Title", tags.title)
        assertNull(tags.artist)
        assertNull(tags.recordingId)
    }

    /** Re-tagging must replace the previous `udta`, not stack a second one alongside it. */
    @Test
    fun `retagging replaces the previous metadata`() {
        val (source, _) = syntheticMp4(listOf(1000))
        val once = assertNotNull(Mp4Tagger.tag(source, Mp4Tags(title = "First", artist = "A")))
        val twice = assertNotNull(Mp4Tagger.tag(once, Mp4Tags(title = "Second", artist = "B")))

        val tags = readBack(twice)
        assertEquals("Second", tags.title)
        assertEquals("B", tags.artist)
        assertEquals(1, countBoxes(twice, "udta"))
    }

    private fun findChunkOffsets(buf: ByteArray): List<Int> {
        var index = 0
        while (index + 8 <= buf.size) {
            if (String(buf, index + 4, 4, Charsets.ISO_8859_1) == "stco") {
                val count = readInt(buf, index + 12)
                return (0 until count).map { readInt(buf, index + 16 + it * 4) }
            }
            index++
        }
        return emptyList()
    }

    private fun countBoxes(buf: ByteArray, type: String): Int {
        var count = 0
        for (index in 0..buf.size - 8) {
            if (String(buf, index + 4, 4, Charsets.ISO_8859_1) == type &&
                readInt(buf, index) in 8..buf.size
            ) {
                count++
            }
        }
        return count
    }
}

