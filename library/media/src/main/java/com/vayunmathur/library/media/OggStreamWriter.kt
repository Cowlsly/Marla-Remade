package com.vayunmathur.library.media

import java.io.ByteArrayOutputStream

/**
 * Ogg page construction: lacing tables, the Ogg CRC-32, and splitting a packet across pages.
 *
 * Shared by [OggOpusTagger], which rewrites the pages of a file the platform muxer produced,
 * and by [OggStreamWriter], which builds a file from encoder packets with no muxer involved.
 */
internal object OggPages {

    const val HEADER_SIZE = 27
    const val MAX_SEGMENTS = 255

    const val FLAG_CONTINUED = 0x01
    const val FLAG_BEGIN_OF_STREAM = 0x02
    const val FLAG_END_OF_STREAM = 0x04

    /**
     * The lacing values for one packet: 255 for every full segment, then a final value under
     * 255 that marks the packet's end. A packet whose length is a multiple of 255 therefore
     * ends with a zero-length segment, which is what tells a reader the packet is complete.
     */
    fun lacingFor(packetSize: Int): List<Int> {
        val lacing = ArrayList<Int>(packetSize / MAX_SEGMENTS + 1)
        var remaining = packetSize
        while (remaining >= MAX_SEGMENTS) {
            lacing.add(MAX_SEGMENTS)
            remaining -= MAX_SEGMENTS
        }
        lacing.add(remaining)
        return lacing
    }

    fun build(
        headerType: Int,
        granulePosition: Long,
        serialNumber: Int,
        sequence: Int,
        segments: List<Int>,
        payload: ByteArray,
        payloadOffset: Int,
        payloadSize: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(HEADER_SIZE + segments.size + payloadSize)
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // stream structure version
        out.write(headerType)
        out.write(longLe(granulePosition))
        out.write(VorbisComments.intLe(serialNumber))
        out.write(VorbisComments.intLe(sequence))
        out.write(ByteArray(4)) // CRC placeholder, filled in below
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload, payloadOffset, payloadSize)
        val bytes = out.toByteArray()
        setChecksum(bytes)
        return bytes
    }

    /**
     * Splits a single packet into as many pages as its length needs.
     *
     * Only a header packet with embedded cover art gets anywhere near this; audio packets
     * always fit on one page.
     */
    fun forPacket(
        packet: ByteArray,
        serialNumber: Int,
        startSequence: Int,
        granulePosition: Long,
        firstHeaderType: Int,
    ): List<ByteArray> {
        val lacing = lacingFor(packet.size)
        val pages = ArrayList<ByteArray>()
        var lacingIndex = 0
        var payloadOffset = 0
        var sequence = startSequence
        while (lacingIndex < lacing.size) {
            val segmentCount = minOf(MAX_SEGMENTS, lacing.size - lacingIndex)
            val segments = lacing.subList(lacingIndex, lacingIndex + segmentCount)
            val payloadSize = segments.sum()
            pages.add(
                build(
                    headerType = if (lacingIndex == 0) firstHeaderType else FLAG_CONTINUED,
                    granulePosition = granulePosition,
                    serialNumber = serialNumber,
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
        }
        return pages
    }

    /** Zeroes the checksum field, computes the Ogg CRC over the whole page, and writes it back. */
    fun setChecksum(page: ByteArray) {
        for (i in 22..25) page[i] = 0
        writeIntLe(page, 22, crc(page))
    }

    fun writeIntLe(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
        buffer[offset + 3] = (value ushr 24).toByte()
    }

    fun writeLongLe(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buffer[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun longLe(value: Long): ByteArray = ByteArray(8).also { writeLongLe(it, 0, value) }

    // ------------------------------------------------------------------
    // Ogg CRC-32 (poly 0x04C11DB7, no reflection, no initial or final xor)
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

    fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val index = ((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff
            crc = (crc shl 8) xor crcTable[index]
        }
        return crc
    }
}

/**
 * Writes an Ogg stream from packets, in one pass, with no platform muxer.
 *
 * [MediaMuxer][android.media.MediaMuxer]'s `MUXER_OUTPUT_OGG` can only be handed a track
 * format, and the platform Opus encoder describes itself through Android's own
 * `AOPUSHDR` marker-chunk codec config rather than the split `csd-0`/`csd-1`/`csd-2` an
 * extractor produces - so whether the muxer would accept it is not something the app can
 * rely on, and it would also need a temp file, since the muxer cannot write to memory. All
 * of the Ogg framing the app needs already existed in [OggOpusTagger] for retagging, so
 * this writes the container directly instead: no temp file, no framework dependency, and
 * the byte-level behaviour is unit-testable.
 *
 * Header packets each get their own page, as the Opus mapping requires. Audio packets are
 * grouped so a page holds up to about a second of audio, which is the usual trade between
 * page overhead and how much a reader loses to a corrupt page.
 */
internal class OggStreamWriter(private val serialNumber: Int) {

    private val out = ByteArrayOutputStream(INITIAL_CAPACITY)
    private var sequence = 0
    private var headerWritten = false

    private val pendingPackets = ArrayList<ByteArray>()
    private var pendingSegments = 0
    private var pendingGranule = 0L

    /** Writes a header packet on a page of its own, with a granule position of zero. */
    fun writeHeaderPacket(packet: ByteArray) {
        val pages = OggPages.forPacket(
            packet = packet,
            serialNumber = serialNumber,
            startSequence = sequence,
            granulePosition = 0L,
            firstHeaderType = if (headerWritten) 0 else OggPages.FLAG_BEGIN_OF_STREAM,
        )
        pages.forEach { out.write(it) }
        sequence += pages.size
        headerWritten = true
    }

    /**
     * Queues an audio packet. [granulePosition] is the total number of samples a decoder gets
     * out of the stream through the end of this packet, which is what Ogg records for the last
     * packet finishing on a page.
     */
    fun writeAudioPacket(packet: ByteArray, granulePosition: Long) {
        val segments = OggPages.lacingFor(packet.size).size
        if (pendingPackets.isNotEmpty() &&
            (pendingSegments + segments > OggPages.MAX_SEGMENTS ||
                pendingPackets.size >= MAX_PACKETS_PER_PAGE)
        ) {
            flushPage(endOfStream = false, granulePosition = pendingGranule)
        }
        pendingPackets.add(packet)
        pendingSegments += segments
        pendingGranule = granulePosition
    }

    /**
     * Closes the stream and returns the bytes.
     *
     * [finalGranulePosition] overrides the last page's granule position so it reports the
     * stream's true length: the encoder pads its final packet out to a whole frame, and a
     * decoder trims that padding by comparing what it decoded against this number.
     */
    fun finish(finalGranulePosition: Long): ByteArray {
        flushPage(endOfStream = true, granulePosition = finalGranulePosition)
        return out.toByteArray()
    }

    private fun flushPage(endOfStream: Boolean, granulePosition: Long) {
        if (pendingPackets.isEmpty() && !endOfStream) return
        val segments = ArrayList<Int>(pendingSegments)
        var payloadSize = 0
        for (packet in pendingPackets) {
            segments.addAll(OggPages.lacingFor(packet.size))
            payloadSize += packet.size
        }
        val payload = ByteArray(payloadSize)
        var at = 0
        for (packet in pendingPackets) {
            packet.copyInto(payload, at)
            at += packet.size
        }
        out.write(
            OggPages.build(
                headerType = if (endOfStream) OggPages.FLAG_END_OF_STREAM else 0,
                granulePosition = granulePosition,
                serialNumber = serialNumber,
                sequence = sequence,
                segments = segments,
                payload = payload,
                payloadOffset = 0,
                payloadSize = payloadSize,
            ),
        )
        sequence++
        pendingPackets.clear()
        pendingSegments = 0
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 * 1024 * 1024

        /** Fifty 20 ms Opus packets, so a page carries about one second. */
        const val MAX_PACKETS_PER_PAGE = 50
    }
}
