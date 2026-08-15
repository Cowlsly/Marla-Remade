package com.vayunmathur.musicbrainz.data.download

import java.io.ByteArrayOutputStream

/** Everything the tagger will write. Null fields are simply omitted. */
data class Mp4Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val date: String? = null,
    val lyrics: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val coverArt: ByteArray? = null,
    val coverIsPng: Boolean = false,
    /** iTunes-style custom tags, which is where the MusicBrainz identifiers live. */
    val freeform: Map<String, String> = emptyMap(),
)

/**
 * Writes iTunes-style metadata into an MP4/M4A file.
 *
 * The repo deliberately ships no ffmpeg - it was removed because the prebuilt binary
 * blocks F-Droid publication - and Android's own `MediaMuxer` cannot write these atoms,
 * so the container is edited directly.
 *
 * This matters beyond cosmetics: the MusicBrainz IDs written here are exactly what the
 * library scan reads back, so a downloaded track is recognised as owned on the next scan
 * without the app keeping a private ledger.
 */
object Mp4Tagger {

    private const val MEAN_DOMAIN = "com.apple.iTunes"

    /** Text payloads are UTF-8; the well-known type codes for the others. */
    private const val TYPE_UTF8 = 1
    private const val TYPE_BINARY = 0
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    /**
     * Returns the tagged file, or null when the input is not an MP4 this can rewrite.
     *
     * Callers fall back to the untagged bytes: a file with poor metadata is still worth
     * keeping, and every non-MP4 container YouTube can serve lands here.
     */
    fun tag(source: ByteArray, tags: Mp4Tags): ByteArray? {
        val boxes = topLevelBoxes(source) ?: return null
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return null
        val mdat = boxes.firstOrNull { it.type == "mdat" }

        val newMoov = rebuildMoov(source, moov, tags) ?: return null
        val delta = newMoov.size - (moov.end - moov.start)

        // Sample offsets in `stco`/`co64` are absolute file positions. Growing `moov`
        // pushes a following `mdat` further down the file, so every offset has to move
        // with it; when `moov` already sits after the audio, nothing has shifted.
        if (mdat != null && mdat.start > moov.start && delta != 0) {
            shiftChunkOffsets(newMoov, delta.toLong())
        }

        val out = ByteArrayOutputStream(source.size + newMoov.size)
        for (box in boxes) {
            if (box.type == "moov") out.write(newMoov) else out.write(source, box.start, box.end - box.start)
        }
        return out.toByteArray()
    }

    private class Box(val type: String, val start: Int, val contentStart: Int, val end: Int)

    private fun topLevelBoxes(buf: ByteArray): List<Box>? {
        val boxes = ArrayList<Box>()
        var offset = 0
        while (offset + 8 <= buf.size) {
            val declared = readInt(buf, offset).toLong() and 0xffffffffL
            val type = String(buf, offset + 4, 4, Charsets.ISO_8859_1)
            val (contentStart, end) = when {
                declared == 1L -> {
                    if (offset + 16 > buf.size) return null
                    offset + 16 to (offset + readLong(buf, offset + 8)).toInt()
                }
                declared == 0L -> offset + 8 to buf.size
                declared < 8L -> return null
                else -> offset + 8 to (offset + declared).toInt()
            }
            if (end <= offset || end > buf.size) return null
            boxes.add(Box(type, offset, contentStart, end))
            offset = end
        }
        return boxes.takeIf { it.isNotEmpty() }
    }

    /** Copies `moov` through, dropping any existing `udta` and appending a freshly built one. */
    private fun rebuildMoov(source: ByteArray, moov: Box, tags: Mp4Tags): ByteArray? {
        val children = childBoxes(source, moov.contentStart, moov.end) ?: return null
        val body = ByteArrayOutputStream()
        for (child in children) {
            if (child.type == "udta") continue
            body.write(source, child.start, child.end - child.start)
        }
        body.write(buildUdta(tags))
        return box("moov", body.toByteArray())
    }

    private fun childBoxes(buf: ByteArray, start: Int, end: Int): List<Box>? {
        val boxes = ArrayList<Box>()
        var offset = start
        while (offset + 8 <= end) {
            val declared = readInt(buf, offset)
            if (declared < 8 || offset + declared > end) return null
            boxes.add(
                Box(
                    String(buf, offset + 4, 4, Charsets.ISO_8859_1),
                    offset,
                    offset + 8,
                    offset + declared,
                ),
            )
            offset += declared
        }
        return boxes
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    private fun buildUdta(tags: Mp4Tags): ByteArray {
        val meta = ByteArrayOutputStream()
        meta.write(byteArrayOf(0, 0, 0, 0)) // `meta` is a full box: version and flags.
        meta.write(handlerBox())
        meta.write(box("ilst", buildIlst(tags)))
        return box("udta", box("meta", meta.toByteArray()))
    }

    /**
     * The `mdir`/`appl` handler.
     *
     * Players use it to recognise the metadata as iTunes-style; without it several
     * ignore the `ilst` entirely.
     */
    private fun handlerBox(): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(byteArrayOf(0, 0, 0, 0)) // version and flags
        content.write(byteArrayOf(0, 0, 0, 0)) // pre_defined
        content.write("mdir".toByteArray(Charsets.ISO_8859_1))
        content.write("appl".toByteArray(Charsets.ISO_8859_1))
        content.write(ByteArray(8)) // reserved
        content.write(0) // empty handler name
        return box("hdlr", content.toByteArray())
    }

    private fun buildIlst(tags: Mp4Tags): ByteArray {
        val ilst = ByteArrayOutputStream()
        fun text(atom: String, value: String?) {
            if (!value.isNullOrBlank()) {
                ilst.write(box(atom, dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8))))
            }
        }

        text("\u00A9nam", tags.title)
        text("\u00A9ART", tags.artist)
        text("\u00A9alb", tags.album)
        text("aART", tags.albumArtist)
        text("\u00A9day", tags.date)
        text("\u00A9lyr", tags.lyrics)

        tags.trackNumber?.let { number ->
            val payload = ByteArray(8)
            writeShort(payload, 2, number)
            writeShort(payload, 4, tags.trackTotal ?: 0)
            ilst.write(box("trkn", dataBox(TYPE_BINARY, payload)))
        }
        tags.discNumber?.let { number ->
            val payload = ByteArray(6)
            writeShort(payload, 2, number)
            writeShort(payload, 4, tags.discTotal ?: 0)
            ilst.write(box("disk", dataBox(TYPE_BINARY, payload)))
        }
        tags.coverArt?.let { cover ->
            val type = if (tags.coverIsPng) TYPE_PNG else TYPE_JPEG
            ilst.write(box("covr", dataBox(type, cover)))
        }
        for ((name, value) in tags.freeform) {
            if (value.isNotBlank()) ilst.write(freeformBox(name, value))
        }
        return ilst.toByteArray()
    }

    private fun dataBox(type: Int, payload: ByteArray): ByteArray {
        val content = ByteArrayOutputStream(payload.size + 8)
        content.write(intBytes(type)) // version and type indicator
        content.write(ByteArray(4)) // locale
        content.write(payload)
        return box("data", content.toByteArray())
    }

    /** A `----` box: the namespace, the tag name, then the value. */
    private fun freeformBox(name: String, value: String): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(box("mean", ByteArray(4) + MEAN_DOMAIN.toByteArray(Charsets.UTF_8)))
        content.write(box("name", ByteArray(4) + name.toByteArray(Charsets.UTF_8)))
        content.write(dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8)))
        return box("----", content.toByteArray())
    }

    private fun box(type: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 8)
        out.write(intBytes(content.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(content)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Chunk offset fixups
    // ------------------------------------------------------------------

    /** Walks the rebuilt `moov` and adds [delta] to every sample chunk offset it holds. */
    private fun shiftChunkOffsets(moov: ByteArray, delta: Long) {
        fun walk(start: Int, end: Int) {
            var offset = start
            while (offset + 8 <= end) {
                val size = readInt(moov, offset)
                if (size < 8 || offset + size > end) return
                val type = String(moov, offset + 4, 4, Charsets.ISO_8859_1)
                when (type) {
                    "stco" -> {
                        val count = readInt(moov, offset + 12)
                        for (i in 0 until count) {
                            val at = offset + 16 + i * 4
                            if (at + 4 > offset + size) break
                            val shifted = (readInt(moov, at).toLong() and 0xffffffffL) + delta
                            writeInt(moov, at, shifted.toInt())
                        }
                    }
                    "co64" -> {
                        val count = readInt(moov, offset + 12)
                        for (i in 0 until count) {
                            val at = offset + 16 + i * 8
                            if (at + 8 > offset + size) break
                            writeLong(moov, at, readLong(moov, at) + delta)
                        }
                    }
                    // Only the containers on the path to `stbl` are worth descending into.
                    "trak", "mdia", "minf", "stbl" -> walk(offset + 8, offset + size)
                }
                offset += size
            }
        }
        walk(8, moov.size)
    }

    // ------------------------------------------------------------------

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
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

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun readLong(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (buf[offset + i].toLong() and 0xff)
        return result
    }

    private fun writeLong(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buf[offset + i] = (value ushr (56 - i * 8)).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }
}
