package com.vayunmathur.musicbrainz.data.library

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** The tags the library index cares about. Every field is absent on files that lack it. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val recordingId: String? = null,
    val releaseId: String? = null,
    val releaseTrackId: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null &&
            recordingId == null && releaseId == null
}

/**
 * Reads identifying tags out of audio files, so the app can tell what the user already
 * owns without keeping its own download ledger.
 *
 * There is no tag library in the repo and adding one would pull in a large third-party
 * dependency for what amounts to four container formats, so the parsers are here. Each
 * one reads only the metadata region and stops - a library scan touches every file, and
 * reading whole albums of audio to find a title would make it unusable.
 *
 * MusicBrainz IDs are the reliable signal but only Picard-tagged files carry them, so
 * plain title/artist/album are read too and the matching falls back to those.
 */
object TagReader {

    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024
    private const val MAX_ID3_BYTES = 4 * 1024 * 1024

    // Large enough to hold an Opus comment header that embeds cover art: the app writes the
    // front cover into the `OpusTags` packet, and the identifying tags parsed here sit in
    // that same packet, so the whole of it has to be scanned to read them back.
    private const val OGG_SCAN_BYTES = 1024 * 1024

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
    )

    fun isAudioFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** Returns whatever could be read; a malformed or unsupported file yields empty tags. */
    fun read(context: Context, uri: Uri, fileName: String): AudioTags = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                when (fileName.substringAfterLast('.', "").lowercase()) {
                    "mp3", "aac" -> readId3(input)
                    "m4a", "m4b", "mp4" -> readMp4(input.channel)
                    "flac" -> readFlac(input)
                    "ogg", "oga", "opus" -> readOgg(input)
                    else -> AudioTags()
                }
            }
        } ?: AudioTags()
    } catch (_: Exception) {
        AudioTags()
    }

    // ------------------------------------------------------------------
    // MP4 / M4A
    // ------------------------------------------------------------------

    /**
     * Pulls `moov` out of the file and parses its `udta.meta.ilst` in memory.
     *
     * `moov` is walked box by box from the channel rather than read wholesale because it
     * sits after the audio data in files that were never prepared for streaming, and in
     * an album-length track that means skipping tens of megabytes to reach it.
     */
    internal fun readMp4(channel: FileChannel): AudioTags {
        val size = channel.size()
        var position = 0L
        while (position < size) {
            val header = readBoxHeader(channel, position, size) ?: break
            if (header.type == "moov") {
                val payloadSize = header.end - header.contentStart
                if (payloadSize <= 0 || payloadSize > MAX_MOOV_BYTES) return AudioTags()
                val buffer = ByteBuffer.allocate(payloadSize.toInt())
                channel.position(header.contentStart)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return AudioTags()
                }
                return parseIlst(findIlst(buffer.array()) ?: return AudioTags())
            }
            position = header.end
        }
        return AudioTags()
    }

    private class BoxHeader(val type: String, val contentStart: Long, val end: Long)

    private fun readBoxHeader(channel: FileChannel, position: Long, fileSize: Long): BoxHeader? {
        if (position + 8 > fileSize) return null
        val head = ByteBuffer.allocate(8)
        channel.position(position)
        while (head.hasRemaining()) {
            if (channel.read(head) < 0) return null
        }
        head.flip()
        val declared = head.int.toLong() and 0xffffffffL
        val type = String(ByteArray(4) { head.get() }, Charsets.ISO_8859_1)
        return when {
            declared == 1L -> {
                val ext = ByteBuffer.allocate(8)
                channel.position(position + 8)
                while (ext.hasRemaining()) {
                    if (channel.read(ext) < 0) return null
                }
                ext.flip()
                val large = ext.long
                if (large < 16) null
                else BoxHeader(type, position + 16, (position + large).coerceAtMost(fileSize))
            }
            declared == 0L -> BoxHeader(type, position + 8, fileSize)
            declared < 8L -> null
            else -> BoxHeader(type, position + 8, (position + declared).coerceAtMost(fileSize))
        }
    }

    /**
     * Locates the `ilst` payload inside a `moov` blob.
     *
     * `meta` is a full box, so its four version/flags bytes have to be stepped over before
     * its children start - treating it like a plain container is the usual reason a tag
     * parser sees nothing here.
     */
    private fun findIlst(moov: ByteArray): ByteArray? {
        fun descend(start: Int, end: Int, path: List<String>): ByteArray? {
            var offset = start
            while (offset + 8 <= end) {
                val size = readInt(moov, offset)
                val type = String(moov, offset + 4, 4, Charsets.ISO_8859_1)
                val boxEnd = if (size == 0) end else offset + size
                if (size < 8 || boxEnd > end) return null
                if (type == path.first()) {
                    val contentStart = if (type == "meta") offset + 12 else offset + 8
                    if (path.size == 1) {
                        return moov.copyOfRange(contentStart.coerceAtMost(boxEnd), boxEnd)
                    }
                    descend(contentStart, boxEnd, path.drop(1))?.let { return it }
                }
                offset = boxEnd
            }
            return null
        }
        return descend(0, moov.size, listOf("udta", "meta", "ilst"))
    }

    private fun parseIlst(ilst: ByteArray): AudioTags {
        val values = HashMap<String, String>()
        var offset = 0
        while (offset + 8 <= ilst.size) {
            val size = readInt(ilst, offset)
            if (size < 8 || offset + size > ilst.size) break
            val type = String(ilst, offset + 4, 4, Charsets.ISO_8859_1)
            val entryEnd = offset + size
            if (type == "----") {
                parseFreeform(ilst, offset + 8, entryEnd)?.let { (key, value) -> values[key] = value }
            } else {
                dataPayload(ilst, offset + 8, entryEnd)?.let { values[type] = it }
            }
            offset = entryEnd
        }
        return AudioTags(
            title = values["\u00A9nam"],
            artist = values["\u00A9ART"],
            album = values["\u00A9alb"],
            albumArtist = values["aART"],
            recordingId = values["MusicBrainz Track Id"],
            releaseId = values["MusicBrainz Album Id"],
            releaseTrackId = values["MusicBrainz Release Track Id"],
        )
    }

    /** Reads the `mean`/`name`/`data` triplet an iTunes-style custom tag is made of. */
    private fun parseFreeform(buf: ByteArray, start: Int, end: Int): Pair<String, String>? {
        var offset = start
        var name: String? = null
        var value: String? = null
        while (offset + 8 <= end) {
            val size = readInt(buf, offset)
            if (size < 8 || offset + size > end) break
            when (String(buf, offset + 4, 4, Charsets.ISO_8859_1)) {
                "name" -> if (offset + 12 <= offset + size) {
                    name = String(buf, offset + 12, size - 12, Charsets.UTF_8)
                }
                "data" -> if (offset + 16 <= offset + size) {
                    value = String(buf, offset + 16, size - 16, Charsets.UTF_8)
                }
            }
            offset += size
        }
        val key = name ?: return null
        val v = value ?: return null
        return key to v
    }

    private fun dataPayload(buf: ByteArray, start: Int, end: Int): String? {
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt(buf, offset)
            if (size < 8 || offset + size > end) return null
            if (String(buf, offset + 4, 4, Charsets.ISO_8859_1) == "data") {
                val payloadStart = offset + 16
                val payloadEnd = offset + size
                if (payloadStart >= payloadEnd) return null
                return String(buf, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
                    .trim()
                    .ifEmpty { null }
            }
            offset += size
        }
        return null
    }

    // ------------------------------------------------------------------
    // MP3 / ID3v2
    // ------------------------------------------------------------------

    internal fun readId3(input: InputStream): AudioTags {
        val header = input.readNBytesCompat(10)
        if (header.size < 10) return AudioTags()
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return AudioTags()
        val major = header[3].toInt() and 0xff
        if (major < 3) return AudioTags()
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_ID3_BYTES) return AudioTags()
        val body = input.readNBytesCompat(tagSize)

        val values = HashMap<String, String>()
        var recordingId: String? = null
        var offset = 0
        while (offset + 10 <= body.size) {
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            // v2.4 made frame sizes synchsafe; v2.3 left them as plain big-endian ints.
            val frameSize = if (major >= 4) synchsafe(body, offset + 4) else readInt(body, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > body.size) break
            val frameStart = offset + 10
            when (id) {
                "TIT2", "TPE1", "TALB", "TPE2" ->
                    decodeTextFrame(body, frameStart, frameSize)?.let { values[id] = it }
                "TXXX" -> decodeUserTextFrame(body, frameStart, frameSize)
                    ?.let { (key, value) -> values[key] = value }
                "UFID" -> {
                    val ownerEnd = body.indexOfZero(frameStart, frameStart + frameSize)
                    if (ownerEnd > 0) {
                        val owner = String(body, frameStart, ownerEnd - frameStart, Charsets.ISO_8859_1)
                        if (owner.contains("musicbrainz.org")) {
                            recordingId = String(
                                body,
                                ownerEnd + 1,
                                frameStart + frameSize - ownerEnd - 1,
                                Charsets.ISO_8859_1,
                            ).trim().ifEmpty { null }
                        }
                    }
                }
            }
            offset = frameStart + frameSize
        }
        return AudioTags(
            title = values["TIT2"],
            artist = values["TPE1"],
            album = values["TALB"],
            albumArtist = values["TPE2"],
            recordingId = recordingId ?: values["MusicBrainz Track Id"],
            releaseId = values["MusicBrainz Album Id"],
            releaseTrackId = values["MusicBrainz Release Track Id"],
        )
    }

    private fun decodeTextFrame(buf: ByteArray, start: Int, size: Int): String? {
        if (size < 2) return null
        return decodeString(buf, start + 1, start + size, buf[start].toInt() and 0xff)
            .trimEnd('\u0000')
            .trim()
            .ifEmpty { null }
    }

    /** `TXXX` is a description and a value, both in the frame's declared encoding. */
    private fun decodeUserTextFrame(buf: ByteArray, start: Int, size: Int): Pair<String, String>? {
        if (size < 2) return null
        val encoding = buf[start].toInt() and 0xff
        val end = start + size
        val wide = encoding == 1 || encoding == 2
        var separator = -1
        var scan = start + 1
        while (scan < end) {
            if (wide) {
                if (scan + 1 < end && buf[scan].toInt() == 0 && buf[scan + 1].toInt() == 0) {
                    separator = scan
                    break
                }
                scan += 2
            } else {
                if (buf[scan].toInt() == 0) {
                    separator = scan
                    break
                }
                scan++
            }
        }
        if (separator < 0) return null
        val description = decodeString(buf, start + 1, separator, encoding).trim()
        val valueStart = separator + if (wide) 2 else 1
        val value = decodeString(buf, valueStart, end, encoding).trimEnd('\u0000').trim()
        if (description.isEmpty() || value.isEmpty()) return null
        return description to value
    }

    private fun decodeString(buf: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(buf, start, end - start, charset)
    }

    // ------------------------------------------------------------------
    // FLAC / Ogg (Vorbis comments)
    // ------------------------------------------------------------------

    internal fun readFlac(input: InputStream): AudioTags {
        val magic = input.readNBytesCompat(4)
        if (magic.size < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return AudioTags()
        while (true) {
            val header = input.readNBytesCompat(4)
            if (header.size < 4) return AudioTags()
            val flags = header[0].toInt() and 0xff
            val isLast = flags and 0x80 != 0
            val blockType = flags and 0x7f
            val length = ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            if (length < 0 || length > MAX_ID3_BYTES) return AudioTags()
            val block = input.readNBytesCompat(length)
            if (block.size < length) return AudioTags()
            if (blockType == 4) return parseVorbisComments(block, 0)
            if (isLast) return AudioTags()
        }
    }

    /**
     * Ogg keeps its comment header in the second packet, so the pages are reassembled
     * until that packet is complete. Only the head of the file is scanned - if the
     * comments are not there the file is not one we can read anyway.
     */
    internal fun readOgg(input: InputStream): AudioTags {
        val data = input.readNBytesCompat(OGG_SCAN_BYTES)
        var offset = 0
        var packetIndex = 0
        val packet = java.io.ByteArrayOutputStream()
        while (offset + 27 <= data.size) {
            if (String(data, offset, 4, Charsets.ISO_8859_1) != "OggS") break
            val segmentCount = data[offset + 26].toInt() and 0xff
            val tableStart = offset + 27
            if (tableStart + segmentCount > data.size) break
            var payloadSize = 0
            var packetEnds = false
            for (i in 0 until segmentCount) {
                val segment = data[tableStart + i].toInt() and 0xff
                payloadSize += segment
                if (segment < 255) packetEnds = true
            }
            val payloadStart = tableStart + segmentCount
            if (payloadStart + payloadSize > data.size) break
            packet.write(data, payloadStart, payloadSize)
            if (packetEnds) {
                val bytes = packet.toByteArray()
                packet.reset()
                if (packetIndex == 1) return parseOggCommentPacket(bytes)
                packetIndex++
            }
            offset = payloadStart + payloadSize
        }
        return AudioTags()
    }

    private fun parseOggCommentPacket(packet: ByteArray): AudioTags = when {
        packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" ->
            parseVorbisComments(packet, 8)
        packet.size > 7 && String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" ->
            parseVorbisComments(packet, 7)
        else -> AudioTags()
    }

    private fun parseVorbisComments(buf: ByteArray, start: Int): AudioTags {
        var offset = start
        if (offset + 4 > buf.size) return AudioTags()
        val vendorLength = readIntLe(buf, offset)
        offset += 4 + vendorLength
        if (offset + 4 > buf.size || vendorLength < 0) return AudioTags()
        val count = readIntLe(buf, offset)
        offset += 4
        if (count < 0 || count > 10_000) return AudioTags()
        val values = HashMap<String, String>()
        repeat(count) {
            if (offset + 4 > buf.size) return@repeat
            val length = readIntLe(buf, offset)
            offset += 4
            if (length < 0 || offset + length > buf.size) return@repeat
            val entry = String(buf, offset, length, Charsets.UTF_8)
            offset += length
            val separator = entry.indexOf('=')
            if (separator > 0) {
                values[entry.substring(0, separator).uppercase()] = entry.substring(separator + 1)
            }
        }
        return AudioTags(
            title = values["TITLE"],
            artist = values["ARTIST"],
            album = values["ALBUM"],
            albumArtist = values["ALBUMARTIST"],
            recordingId = values["MUSICBRAINZ_TRACKID"],
            releaseId = values["MUSICBRAINZ_ALBUMID"],
            releaseTrackId = values["MUSICBRAINZ_RELEASETRACKID"],
        )
    }

    // ------------------------------------------------------------------

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 24) or
            ((buf[offset + 1].toInt() and 0xff) shl 16) or
            ((buf[offset + 2].toInt() and 0xff) shl 8) or
            (buf[offset + 3].toInt() and 0xff)

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** ID3 sizes drop the high bit of each byte so they can never look like a frame sync. */
    private fun synchsafe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0x7f) shl 21) or
            ((buf[offset + 1].toInt() and 0x7f) shl 14) or
            ((buf[offset + 2].toInt() and 0x7f) shl 7) or
            (buf[offset + 3].toInt() and 0x7f)

    private fun ByteArray.indexOfZero(from: Int, to: Int): Int {
        for (i in from until minOf(to, size)) if (this[i].toInt() == 0) return i
        return -1
    }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            if (n < 0) break
            read += n
        }
        return if (read == count) out else out.copyOf(read)
    }
}
