package org.schabi.newpipe.extractor.services.youtube.sabrng.media

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrFormatInitializationMetadata

/** Parses a WebM (cues) segment index from a format's initialization data. */
object SabrWebmSegmentIndexParser {
    private const val SEGMENT_ID = 0x18538067L
    private const val INFO_ID = 0x1549a966L
    private const val TIMECODE_SCALE_ID = 0x2ad7b1L
    private const val CUES_ID = 0x1c53bb6bL
    private const val CUE_POINT_ID = 0xbbL
    private const val CUE_TIME_ID = 0xb3L
    private const val DEFAULT_TIMECODE_SCALE_NANOS = 1000000L

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun parse(initData: ByteArray, metadata: SabrFormatInitializationMetadata): SabrSegmentIndex =
        parse(
            initData, metadata,
            if (metadata.getDurationUnits() > 0 && metadata.getDurationTimescale() > 0) {
                scaleToMs(metadata.getDurationUnits(), metadata.getDurationTimescale())
            } else {
                -1
            }
        )

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun parse(initData: ByteArray, format: YoutubeSabrInfo.Format): SabrSegmentIndex =
        parse(initData, null, format.getApproxDurationMs())

    @Throws(SabrProtocolException::class)
    private fun parse(
        initData: ByteArray,
        metadata: SabrFormatInitializationMetadata?,
        totalDurationMs: Long
    ): SabrSegmentIndex {
        val segment = findElement(initData, 0, initData.size, SEGMENT_ID)
        val timecodeScaleNanos = readTimecodeScale(initData, segment)
        val cues = if (metadata == null) {
            findElement(initData, segment.contentStart, segment.contentEnd, CUES_ID)
        } else {
            findElement(
                initData,
                checkedRangeOffset(metadata.getIndexRangeStart(), initData.size),
                checkedRangeEnd(metadata.getIndexRangeEnd(), initData.size),
                CUES_ID
            )
        }
        val cueTimes = readCueTimes(initData, cues, timecodeScaleNanos)
        if (cueTimes.isEmpty()) {
            throw SabrProtocolException("WebM cues contain no cue times")
        }

        val entries = ArrayList<SabrSegmentIndex.Entry>(cueTimes.size)
        for (i in cueTimes.indices) {
            val startMs = cueTimes[i]
            val endMs: Long = when {
                i + 1 < cueTimes.size -> cueTimes[i + 1]
                totalDurationMs > startMs -> totalDurationMs
                i > 0 -> startMs + maxOf(1, startMs - cueTimes[i - 1])
                else -> startMs + 1
            }
            entries.add(SabrSegmentIndex.Entry(i + 1, startMs, maxOf(1, endMs - startMs)))
        }
        return SabrSegmentIndex(entries)
    }

    @Throws(SabrProtocolException::class)
    private fun readTimecodeScale(data: ByteArray, segment: Element): Long {
        val info = findElement(data, segment.contentStart, segment.contentEnd, INFO_ID)
        var offset = info.contentStart
        while (offset < info.contentEnd) {
            val element = readElement(data, offset, info.contentEnd)
            if (element.id == TIMECODE_SCALE_ID) {
                return readUnsignedInteger(
                    data, element.contentStart, element.contentEnd - element.contentStart
                )
            }
            offset = element.contentEnd
        }
        return DEFAULT_TIMECODE_SCALE_NANOS
    }

    @Throws(SabrProtocolException::class)
    private fun readCueTimes(data: ByteArray, cues: Element, timecodeScaleNanos: Long): List<Long> {
        val cueTimes = ArrayList<Long>()
        var offset = cues.contentStart
        while (offset < cues.contentEnd) {
            val cuePoint = readElement(data, offset, cues.contentEnd)
            if (cuePoint.id == CUE_POINT_ID) {
                val cueTime = readCueTime(data, cuePoint)
                if (cueTime >= 0) {
                    cueTimes.add(scaleWebmTimeToMs(cueTime, timecodeScaleNanos))
                }
            }
            offset = cuePoint.contentEnd
        }
        return cueTimes
    }

    @Throws(SabrProtocolException::class)
    private fun readCueTime(data: ByteArray, cuePoint: Element): Long {
        var offset = cuePoint.contentStart
        while (offset < cuePoint.contentEnd) {
            val element = readElement(data, offset, cuePoint.contentEnd)
            if (element.id == CUE_TIME_ID) {
                return readUnsignedInteger(
                    data, element.contentStart, element.contentEnd - element.contentStart
                )
            }
            offset = element.contentEnd
        }
        return -1
    }

    @Throws(SabrProtocolException::class)
    private fun findElement(data: ByteArray, start: Int, end: Int, id: Long): Element {
        var offset = start
        while (offset < end) {
            val element = readElement(data, offset, end)
            if (element.id == id) {
                return element
            }
            offset = element.contentEnd
        }
        throw SabrProtocolException("WebM element not found: " + java.lang.Long.toHexString(id))
    }

    @Throws(SabrProtocolException::class)
    private fun readElement(data: ByteArray, offset: Int, containerEnd: Int): Element {
        val id = readElementId(data, offset, containerEnd)
        val size = readElementSize(data, offset + id.length, containerEnd)
        val contentStart = offset + id.length + size.length
        val contentEnd: Int = if (size.unknown || size.value > Int.MAX_VALUE ||
            contentStart + size.value > containerEnd
        ) {
            // Segment (master) in a DASH/SABR init declares its full media size, way past the init
            // buffer we actually have. Clamp instead of failing, otherwise webm formats never get a
            // segment index -> uniform-tiling fallback -> drift -> periodic video freeze.
            containerEnd
        } else {
            contentStart + size.value.toInt()
        }
        return Element(id.value, contentStart, contentEnd)
    }

    @Throws(SabrProtocolException::class)
    private fun readElementId(data: ByteArray, offset: Int, end: Int): Varint {
        val length = readVintLength(data, offset, end)
        var value = 0L
        for (i in 0 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xffL)
        }
        return Varint(value, length, false)
    }

    @Throws(SabrProtocolException::class)
    private fun readElementSize(data: ByteArray, offset: Int, end: Int): Varint {
        val length = readVintLength(data, offset, end)
        var value = data[offset].toLong() and (0xffL shr length)
        for (i in 1 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xffL)
        }
        val unknownValue = (1L shl (7 * length)) - 1L
        return Varint(value, length, value == unknownValue)
    }

    @Throws(SabrProtocolException::class)
    private fun readVintLength(data: ByteArray, offset: Int, end: Int): Int {
        if (offset >= end) {
            throw SabrProtocolException("Unexpected EOF while reading WebM vint")
        }
        val first = data[offset].toInt() and 0xff
        for (length in 1..8) {
            if ((first and (0x80 shr (length - 1))) != 0) {
                if (offset + length > end) {
                    throw SabrProtocolException("Truncated WebM vint")
                }
                return length
            }
        }
        throw SabrProtocolException("Invalid WebM vint")
    }

    @Throws(SabrProtocolException::class)
    private fun checkedRangeOffset(offset: Long, length: Int): Int {
        if (offset < 0 || offset >= length) {
            throw SabrProtocolException("WebM index range outside init segment")
        }
        return offset.toInt()
    }

    @Throws(SabrProtocolException::class)
    private fun checkedRangeEnd(inclusiveEnd: Long, length: Int): Int {
        if (inclusiveEnd < 0 || inclusiveEnd >= length) {
            throw SabrProtocolException("WebM index range outside init segment")
        }
        return inclusiveEnd.toInt() + 1
    }

    @Throws(SabrProtocolException::class)
    private fun readUnsignedInteger(data: ByteArray, offset: Int, length: Int): Long {
        if (length <= 0 || length > 8 || offset < 0 || offset + length > data.size) {
            throw SabrProtocolException("Invalid WebM unsigned integer length")
        }
        var value = 0L
        for (i in 0 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xffL)
        }
        return value
    }

    private fun scaleWebmTimeToMs(cueTime: Long, timecodeScaleNanos: Long): Long =
        (cueTime * timecodeScaleNanos + 500000L) / 1000000L

    private fun scaleToMs(value: Long, timescale: Long): Long =
        (value * 1000L + timescale / 2L) / timescale

    private class Element(val id: Long, val contentStart: Int, val contentEnd: Int)

    private class Varint(val value: Long, val length: Int, val unknown: Boolean)
}
