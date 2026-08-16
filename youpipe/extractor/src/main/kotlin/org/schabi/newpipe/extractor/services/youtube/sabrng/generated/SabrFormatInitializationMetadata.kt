package org.schabi.newpipe.extractor.services.youtube.sabrng.generated

import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto

/** Decoded SABR format initialization metadata describing a format's ranges and duration. */
class SabrFormatInitializationMetadata private constructor(
    private val videoId: String?,
    private val rawSummaryBytes: ByteArray,
    private val itag: Int,
    private val lastModified: Long,
    private val xtags: String?,
    private val endTimeMs: Long,
    private val endSegmentNumber: Long,
    private val mimeType: String?,
    private val initRangeStart: Long,
    private val initRangeEnd: Long,
    private val indexRangeStart: Long,
    private val indexRangeEnd: Long,
    private val field8: Long,
    private val durationUnits: Long,
    private val durationTimescale: Long
) {
    fun getVideoId(): String? = videoId
    fun getItag(): Int = itag
    fun getLastModified(): Long = lastModified
    fun getXtags(): String? = xtags
    fun getEndSegmentNumber(): Long = endSegmentNumber
    fun getMimeType(): String? = mimeType
    fun getDurationUnits(): Long = durationUnits
    fun getDurationTimescale(): Long = durationTimescale
    fun getInitRangeStart(): Long = initRangeStart
    fun getInitRangeEnd(): Long = initRangeEnd
    fun getIndexRangeStart(): Long = indexRangeStart
    fun getIndexRangeEnd(): Long = indexRangeEnd
    fun getField8(): Long = field8

    fun summarize(): String {
        var unknown = "unknown-error"
        try {
            unknown = SabrProto.summarizeUnknownFields(
                rawSummaryBytes, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
            )
        } catch (ignored: Exception) {
        }
        return "itag=" + itag +
            ", endSegment=" + endSegmentNumber +
            ", endTimeMs=" + endTimeMs +
            ", mime=" + (mimeType ?: "null") +
            ", init=" + initRangeStart + '-' + initRangeEnd +
            ", index=" + indexRangeStart + '-' + indexRangeEnd +
            ", field8=" + field8 +
            ", duration=" + durationUnits + '/' + durationTimescale +
            ", unknown=" + unknown
    }

    private class Range(val start: Long, val end: Long)

    companion object {
        @JvmStatic
        fun normalized(
            videoId: String?, itag: Int, lastModified: Long, xtags: String?, endTimeMs: Long,
            endSegmentNumber: Long, mimeType: String?, initRangeStart: Long, initRangeEnd: Long,
            indexRangeStart: Long, indexRangeEnd: Long, field8: Long, durationUnits: Long,
            durationTimescale: Long
        ): SabrFormatInitializationMetadata {
            require(!(itag <= 0 || endSegmentNumber < -1 || durationUnits < -1 || durationTimescale < -1)) {
                "Invalid normalized SABR format metadata"
            }
            return SabrFormatInitializationMetadata(
                videoId, ByteArray(0), itag, lastModified, xtags, endTimeMs, endSegmentNumber,
                mimeType, initRangeStart, initRangeEnd, indexRangeStart, indexRangeEnd, field8,
                durationUnits, durationTimescale
            )
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrFormatInitializationMetadata {
            var videoId: String? = null
            var itag = -1
            var lastModified = -1L
            var xtags: String? = null
            var endTimeMs = -1L
            var endSegmentNumber = -1L
            var mimeType: String? = null
            var initRangeStart = -1L
            var initRangeEnd = -1L
            var indexRangeStart = -1L
            var indexRangeEnd = -1L
            var field8 = -1L
            var durationUnits = -1L
            var durationTimescale = -1L

            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> videoId = field.getString()
                    2 -> for (formatField in SabrProto.readFields(field.getBytes())) {
                        when (formatField.getNumber()) {
                            1 -> itag = formatField.getVarint().toInt()
                            2 -> lastModified = formatField.getVarint()
                            3 -> xtags = formatField.getString()
                        }
                    }
                    3 -> endTimeMs = field.getVarint()
                    4 -> endSegmentNumber = field.getVarint()
                    5 -> mimeType = field.getString()
                    6 -> {
                        val initRange = decodeRange(field.getBytes())
                        initRangeStart = initRange.start
                        initRangeEnd = initRange.end
                    }
                    7 -> {
                        val indexRange = decodeRange(field.getBytes())
                        indexRangeStart = indexRange.start
                        indexRangeEnd = indexRange.end
                    }
                    8 -> field8 = field.getVarint()
                    9 -> durationUnits = field.getVarint()
                    10 -> durationTimescale = field.getVarint()
                    else -> {}
                }
            }

            return SabrFormatInitializationMetadata(
                videoId, data.clone(), itag, lastModified, xtags, endTimeMs, endSegmentNumber,
                mimeType, initRangeStart, initRangeEnd, indexRangeStart, indexRangeEnd, field8,
                durationUnits, durationTimescale
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeRange(data: ByteArray): Range {
            var start = -1L
            var end = -1L
            for (field in SabrProto.readFields(data)) {
                if ((field.getNumber() == 1 || field.getNumber() == 3) &&
                    field.getWireType() == SabrProto.WIRE_VARINT
                ) {
                    start = field.getVarint()
                } else if ((field.getNumber() == 2 || field.getNumber() == 4) &&
                    field.getWireType() == SabrProto.WIRE_VARINT
                ) {
                    end = field.getVarint()
                }
            }
            return Range(start, end)
        }
    }
}
