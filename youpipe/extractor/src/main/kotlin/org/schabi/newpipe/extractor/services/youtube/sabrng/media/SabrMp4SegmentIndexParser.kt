package org.schabi.newpipe.extractor.services.youtube.sabrng.media

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrFormatInitializationMetadata
import java.nio.charset.StandardCharsets

/** Parses an MP4 (sidx) segment index from a format's initialization data. */
object SabrMp4SegmentIndexParser {
    private const val SIDX_BOX = "sidx"

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun parse(initData: ByteArray, metadata: SabrFormatInitializationMetadata): SabrSegmentIndex =
        parse(
            initData,
            checkedRangeOffset(metadata.getIndexRangeStart(), initData.size),
            checkedRangeOffset(metadata.getIndexRangeEnd(), initData.size)
        )

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun parse(initData: ByteArray, format: YoutubeSabrInfo.Format): SabrSegmentIndex =
        parse(initData, 0, initData.size - 1)

    @Throws(SabrProtocolException::class)
    private fun parse(initData: ByteArray, indexStart: Int, indexEnd: Int): SabrSegmentIndex {
        if (indexEnd < indexStart) {
            throw SabrProtocolException("Invalid MP4 SIDX range")
        }
        val sidxOffset = findSidxBox(initData, indexStart, indexEnd + 1)
        return parseSidx(initData, sidxOffset, indexEnd + 1)
    }

    @Throws(SabrProtocolException::class)
    fun parse(initData: ByteArray): SabrSegmentIndex {
        val sidxOffset = findSidxBox(initData, 0, initData.size)
        return parseSidx(initData, sidxOffset, initData.size)
    }

    @Throws(SabrProtocolException::class)
    private fun parseSidx(initData: ByteArray, sidxOffset: Int, rangeEnd: Int): SabrSegmentIndex {
        val boxSize = readUint32(initData, sidxOffset)
        val boxEnd = checkedBoxEnd(sidxOffset, boxSize, rangeEnd)
        var cursor = sidxOffset + 8
        val version = initData[cursor].toInt() and 0xff
        cursor += 4 // reference_ID

        cursor += 4
        val timescale = readUint32(initData, cursor)
        cursor += 4
        if (timescale <= 0) {
            throw SabrProtocolException("Invalid MP4 SIDX timescale")
        }

        val earliestPresentationTime: Long
        when (version) {
            0 -> {
                earliestPresentationTime = readUint32(initData, cursor)
                cursor += 8 // earliest_presentation_time + first_offset
            }
            1 -> {
                earliestPresentationTime = readUint64(initData, cursor)
                cursor += 16 // earliest_presentation_time + first_offset
            }
            else -> throw SabrProtocolException("Unsupported MP4 SIDX version: $version")
        }

        cursor += 2 // reserved
        val referenceCount = readUint16(initData, cursor)
        cursor += 2
        val entries = ArrayList<SabrSegmentIndex.Entry>(referenceCount)
        var unscaledStart = earliestPresentationTime
        for (i in 0 until referenceCount) {
            if (cursor + 12 > boxEnd) {
                throw SabrProtocolException("Truncated MP4 SIDX references")
            }
            val reference = readUint32(initData, cursor)
            cursor += 4
            val nestedSidx = (reference and 0x80000000L) != 0L
            if (nestedSidx) {
                throw SabrProtocolException("Nested MP4 SIDX references are unsupported")
            }
            val duration = readUint32(initData, cursor)
            cursor += 8 // subsegment_duration + SAP flags
            entries.add(
                SabrSegmentIndex.Entry(
                    i + 1,
                    scaleToMs(unscaledStart, timescale),
                    scaleToMs(duration, timescale)
                )
            )
            unscaledStart += duration
        }
        return SabrSegmentIndex(entries)
    }

    @Throws(SabrProtocolException::class)
    private fun findSidxBox(data: ByteArray, start: Int, end: Int): Int {
        var offset = start
        while (offset + 8 <= end) {
            if (SIDX_BOX == String(data, offset + 4, 4, StandardCharsets.US_ASCII)) {
                return offset
            }
            offset++
        }
        throw SabrProtocolException("MP4 SIDX box not found")
    }

    @Throws(SabrProtocolException::class)
    private fun checkedRangeOffset(offset: Long, length: Int): Int {
        if (offset < 0 || offset >= length) {
            throw SabrProtocolException("MP4 SIDX range outside init segment")
        }
        return offset.toInt()
    }

    @Throws(SabrProtocolException::class)
    private fun checkedBoxEnd(offset: Int, boxSize: Long, rangeEnd: Int): Int {
        if (boxSize < 8 || boxSize > Int.MAX_VALUE || offset + boxSize > rangeEnd) {
            throw SabrProtocolException("Invalid MP4 SIDX box size")
        }
        return offset + boxSize.toInt()
    }

    @Throws(SabrProtocolException::class)
    private fun readUint16(data: ByteArray, offset: Int): Int {
        checkAvailable(data, offset, 2)
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    @Throws(SabrProtocolException::class)
    private fun readUint32(data: ByteArray, offset: Int): Long {
        checkAvailable(data, offset, 4)
        return ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)
    }

    @Throws(SabrProtocolException::class)
    private fun readUint64(data: ByteArray, offset: Int): Long {
        val high = readUint32(data, offset)
        val low = readUint32(data, offset + 4)
        return (high shl 32) or low
    }

    @Throws(SabrProtocolException::class)
    private fun checkAvailable(data: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || offset + length > data.size) {
            throw SabrProtocolException("Unexpected EOF while reading MP4 SIDX")
        }
    }

    private fun scaleToMs(value: Long, timescale: Long): Long =
        (value * 1000L + timescale / 2L) / timescale
}
