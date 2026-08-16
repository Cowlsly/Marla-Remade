package org.schabi.newpipe.extractor.services.youtube.sabrng.generated

import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto

/** Decoded SABR media header describing one media (or initialization) segment. */
class SabrMediaHeader private constructor(
    private val headerId: Int,
    private val videoId: String?,
    private val itag: Int,
    private val lastModified: Long,
    private val xtags: String?,
    private val startRange: Long,
    private val compressionAlgorithm: Int,
    private val initSegment: Boolean,
    private val sequenceNumber: Int,
    private val bitrateBps: Long,
    private val startMs: Long,
    private val durationMs: Long,
    private val contentLength: Long,
    private val timeRangeStartTicks: Long,
    private val timeRangeDurationTicks: Long,
    private val timeRangeTimescale: Int,
    private val sequenceLastModified: Long
) {
    fun getHeaderId(): Int = headerId
    fun getVideoId(): String? = videoId
    fun getItag(): Int = itag
    fun getLastModified(): Long = lastModified
    fun getXtags(): String? = xtags
    fun getStartRange(): Long = startRange
    fun getCompressionAlgorithm(): Int = compressionAlgorithm
    fun isInitSegment(): Boolean = initSegment
    fun getSequenceNumber(): Int = sequenceNumber
    fun getBitrateBps(): Long = bitrateBps
    fun getStartMs(): Long = startMs
    fun getDurationMs(): Long = durationMs
    fun getContentLength(): Long = contentLength
    fun getTimeRangeStartTicks(): Long = timeRangeStartTicks
    fun getTimeRangeDurationTicks(): Long = timeRangeDurationTicks
    fun getTimeRangeTimescale(): Int = timeRangeTimescale
    fun getSequenceLastModified(): Long = sequenceLastModified

    fun summarize(): String =
        "id=" + headerId +
            ", itag=" + itag +
            ", init=" + initSegment +
            ", seq=" + sequenceNumber +
            ", startRange=" + startRange +
            ", startMs=" + startMs +
            ", durationMs=" + durationMs +
            ", contentLength=" + contentLength +
            ", compression=" + compressionAlgorithm +
            ", bitrateBps=" + bitrateBps +
            ", timeRange=" + timeRangeStartTicks + '+' + timeRangeDurationTicks +
            '/' + timeRangeTimescale +
            ", sequenceLmt=" + sequenceLastModified

    override fun toString(): String = summarize()

    private class FormatId(val itag: Int, val lastModified: Long, val xtags: String?)

    private class TimeRange(val startTicks: Long, val durationTicks: Long, val timescale: Int)

    companion object {
        @JvmStatic
        fun normalized(
            headerId: Int, videoId: String?, itag: Int, lastModified: Long, xtags: String?,
            startRange: Long, compressionAlgorithm: Int, initSegment: Boolean, sequenceNumber: Int,
            bitrateBps: Long, startMs: Long, durationMs: Long, contentLength: Long,
            timeRangeStartTicks: Long, timeRangeDurationTicks: Long, timeRangeTimescale: Int,
            sequenceLastModified: Long
        ): SabrMediaHeader = SabrMediaHeader(
            headerId, videoId, itag, lastModified, xtags, startRange, compressionAlgorithm,
            initSegment, sequenceNumber, bitrateBps, startMs, durationMs, contentLength,
            timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale, sequenceLastModified
        )

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrMediaHeader {
            var headerId = -1
            var videoId: String? = null
            var itag = -1
            var lastModified = -1L
            var xtags: String? = null
            var startRange = -1L
            var compressionAlgorithm = -1
            var initSegment = false
            var sequenceNumber = -1
            var bitrateBps = -1L
            var startMs = -1L
            var durationMs = -1L
            var contentLength = -1L
            var timeRangeStartTicks = -1L
            var timeRangeDurationTicks = -1L
            var timeRangeTimescale = -1
            var sequenceLastModified = -1L

            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> headerId = field.getVarint().toInt()
                    2 -> videoId = field.getString()
                    3 -> itag = field.getVarint().toInt()
                    4 -> lastModified = field.getVarint()
                    5 -> xtags = field.getString()
                    6 -> startRange = field.getVarint()
                    7 -> compressionAlgorithm = field.getVarint().toInt()
                    8 -> initSegment = field.getVarint() != 0L
                    9 -> sequenceNumber = field.getVarint().toInt()
                    10 -> bitrateBps = field.getVarint()
                    11 -> startMs = field.getVarint()
                    12 -> durationMs = field.getVarint()
                    13 -> {
                        val formatId = decodeFormatId(field.getBytes())
                        if (itag < 0) itag = formatId.itag
                        if (lastModified < 0) lastModified = formatId.lastModified
                        if (xtags == null) xtags = formatId.xtags
                    }
                    14 -> contentLength = field.getVarint()
                    15 -> {
                        val timeRange = decodeTimeRange(field.getBytes())
                        timeRangeStartTicks = timeRange.startTicks
                        timeRangeDurationTicks = timeRange.durationTicks
                        timeRangeTimescale = timeRange.timescale
                    }
                    16 -> sequenceLastModified = field.getVarint()
                    else -> {}
                }
            }

            if (timeRangeTimescale > 0) {
                if (startMs < 0 && timeRangeStartTicks >= 0) {
                    startMs = timeRangeStartTicks * 1000L / timeRangeTimescale
                }
                if (durationMs < 0 && timeRangeDurationTicks >= 0) {
                    durationMs = timeRangeDurationTicks * 1000L / timeRangeTimescale
                }
            }

            return SabrMediaHeader(
                headerId, videoId, itag, lastModified, xtags, startRange, compressionAlgorithm,
                initSegment, sequenceNumber, bitrateBps, startMs, durationMs, contentLength,
                timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale, sequenceLastModified
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeFormatId(data: ByteArray): FormatId {
            var itag = -1
            var lastModified = -1L
            var xtags: String? = null
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    itag = field.getVarint().toInt()
                } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    lastModified = field.getVarint()
                } else if (field.getNumber() == 3 &&
                    field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED
                ) {
                    xtags = field.getString()
                }
            }
            return FormatId(itag, lastModified, xtags)
        }

        @Throws(SabrProtocolException::class)
        private fun decodeTimeRange(data: ByteArray): TimeRange {
            var startTicks = -1L
            var durationTicks = -1L
            var timescale = -1
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    startTicks = field.getVarint()
                } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    durationTicks = field.getVarint()
                } else if (field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    timescale = field.getVarint().toInt()
                }
            }
            return TimeRange(startTicks, durationTicks, timescale)
        }
    }
}
