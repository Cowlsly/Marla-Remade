package com.vayunmathur.music.platform

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads embedded lyrics out of the currently playing file.
 *
 * The musicbrainz downloader writes synced LRC text into a `LYRICS` tag - a Vorbis comment
 * in `.opus`/`.flac`, or an iTunes `©lyr` atom in the `.m4a` fallback - and this pulls it
 * back out so the now-playing screen can scroll along. Only the metadata region at the head
 * of the file is scanned; the audio body is never read, because this runs every time the
 * track changes.
 *
 * This mirrors the parsers in the musicbrainz app's `TagReader`, duplicated rather than
 * shared because the two apps are separate modules with no common dependency.
 */
object EmbeddedLyrics {

    // Large enough to clear an Opus comment header that also embeds cover art, since the
    // lyrics tag sits in that same packet behind the picture.
    private const val OGG_SCAN_BYTES = 1024 * 1024
    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024

    /** Returns the raw LRC/lyrics text, or null when the file carries none this can read. */
    fun read(context: Context, uri: Uri): String? = try {
        when (containerOf(context, uri)) {
            Container.OGG -> context.contentResolver.openInputStream(uri)?.use { readOgg(it) }
            Container.FLAC -> context.contentResolver.openInputStream(uri)?.use { readFlac(it) }
            Container.MP4 -> readMp4(context, uri)
            null -> null
        }
    } catch (_: Exception) {
        null
    }

    private enum class Container { OGG, FLAC, MP4 }

    /** Sniffs the container from the first few bytes rather than trusting a URI's extension. */
    private fun containerOf(context: Context, uri: Uri): Container? {
        val magic = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(12) }
            ?: return null
        if (magic.size < 12) return null
        return when {
            String(magic, 0, 4, Charsets.ISO_8859_1) == "OggS" -> Container.OGG
            String(magic, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> Container.FLAC
            String(magic, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Container.MP4
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Ogg (Vorbis comments in the second packet)
    // ------------------------------------------------------------------

    private fun readOgg(input: InputStream): String? {
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
                if (packetIndex == 1) return lyricsFromCommentPacket(bytes)
                packetIndex++
            }
            offset = payloadStart + payloadSize
        }
        return null
    }

    private fun lyricsFromCommentPacket(packet: ByteArray): String? = when {
        packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" ->
            lyricsFromVorbisComments(packet, 8)
        packet.size > 7 && String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" ->
            lyricsFromVorbisComments(packet, 7)
        else -> null
    }

    // ------------------------------------------------------------------
    // FLAC (Vorbis comment metadata block)
    // ------------------------------------------------------------------

    private fun readFlac(input: InputStream): String? {
        val magic = input.readNBytesCompat(4)
        if (magic.size < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val header = input.readNBytesCompat(4)
            if (header.size < 4) return null
            val flags = header[0].toInt() and 0xff
            val isLast = flags and 0x80 != 0
            val blockType = flags and 0x7f
            val length = ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            if (length < 0 || length > OGG_SCAN_BYTES) return null
            val block = input.readNBytesCompat(length)
            if (block.size < length) return null
            if (blockType == 4) return lyricsFromVorbisComments(block, 0)
            if (isLast) return null
        }
    }

    private fun lyricsFromVorbisComments(buf: ByteArray, start: Int): String? {
        var offset = start
        if (offset + 4 > buf.size) return null
        val vendorLength = readIntLe(buf, offset)
        offset += 4 + vendorLength
        if (offset + 4 > buf.size || vendorLength < 0) return null
        val count = readIntLe(buf, offset)
        offset += 4
        if (count < 0 || count > 10_000) return null
        repeat(count) {
            if (offset + 4 > buf.size) return null
            val length = readIntLe(buf, offset)
            offset += 4
            if (length < 0 || offset + length > buf.size) return null
            val entry = String(buf, offset, length, Charsets.UTF_8)
            offset += length
            val separator = entry.indexOf('=')
            if (separator > 0 && entry.substring(0, separator).equals("LYRICS", ignoreCase = true)) {
                return entry.substring(separator + 1).ifBlank { null }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // MP4 / M4A (iTunes ©lyr atom)
    // ------------------------------------------------------------------

    private fun readMp4(context: Context, uri: Uri): String? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input -> readMp4(input.channel) }
        }

    private fun readMp4(channel: FileChannel): String? {
        val size = channel.size()
        var position = 0L
        while (position < size) {
            if (position + 8 > size) break
            val head = ByteBuffer.allocate(8)
            channel.position(position)
            while (head.hasRemaining()) {
                if (channel.read(head) < 0) return null
            }
            head.flip()
            val declared = head.int.toLong() and 0xffffffffL
            val type = String(ByteArray(4) { head.get() }, Charsets.ISO_8859_1)
            val contentStart = position + 8
            val end = when {
                declared == 1L -> {
                    val ext = ByteBuffer.allocate(8)
                    channel.position(position + 8)
                    while (ext.hasRemaining()) {
                        if (channel.read(ext) < 0) return null
                    }
                    ext.flip()
                    position + ext.long
                }
                declared == 0L -> size
                declared < 8L -> return null
                else -> position + declared
            }
            if (end <= position || end > size) return null
            if (type == "moov") {
                val payloadSize = end - contentStart
                if (payloadSize <= 0 || payloadSize > MAX_MOOV_BYTES) return null
                val buffer = ByteBuffer.allocate(payloadSize.toInt())
                channel.position(contentStart)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return null
                }
                val ilst = findIlst(buffer.array()) ?: return null
                return lyricsFromIlst(ilst)
            }
            position = end
        }
        return null
    }

    private fun findIlst(moov: ByteArray): ByteArray? {
        fun descend(start: Int, end: Int, path: List<String>): ByteArray? {
            var offset = start
            while (offset + 8 <= end) {
                val size = readIntBe(moov, offset)
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

    private fun lyricsFromIlst(ilst: ByteArray): String? {
        var offset = 0
        while (offset + 8 <= ilst.size) {
            val size = readIntBe(ilst, offset)
            if (size < 8 || offset + size > ilst.size) break
            val type = String(ilst, offset + 4, 4, Charsets.ISO_8859_1)
            if (type == "\u00A9lyr") {
                var inner = offset + 8
                while (inner + 8 <= offset + size) {
                    val dataSize = readIntBe(ilst, inner)
                    if (dataSize < 8 || inner + dataSize > offset + size) break
                    if (String(ilst, inner + 4, 4, Charsets.ISO_8859_1) == "data") {
                        val payloadStart = inner + 16
                        val payloadEnd = inner + dataSize
                        if (payloadStart < payloadEnd) {
                            return String(ilst, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
                                .ifBlank { null }
                        }
                    }
                    inner += dataSize
                }
            }
            offset += size
        }
        return null
    }

    // ------------------------------------------------------------------

    private fun readIntBe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 24) or
            ((buf[offset + 1].toInt() and 0xff) shl 16) or
            ((buf[offset + 2].toInt() and 0xff) shl 8) or
            (buf[offset + 3].toInt() and 0xff)

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

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
