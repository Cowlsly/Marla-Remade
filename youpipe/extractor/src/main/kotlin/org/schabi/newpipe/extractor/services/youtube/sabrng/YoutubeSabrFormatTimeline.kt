package org.schabi.newpipe.extractor.services.youtube.sabrng

import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMp4SegmentIndexParser
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrSegmentIndex
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrWebmSegmentIndexParser

/** Immutable segment timeline parsed from one format's initialization data. */
class YoutubeSabrFormatTimeline private constructor(private val index: SabrSegmentIndex) {

    fun getEndSequence(): Int = index.size()

    fun getStartMs(sequenceNumber: Int): Long {
        val entry = index.getEntry(sequenceNumber)
        return entry?.getStartMs() ?: -1
    }

    fun getEndMs(sequenceNumber: Int): Long {
        val entry = index.getEntry(sequenceNumber)
        return entry?.getEndMs() ?: -1
    }

    fun getSequenceAt(timeMs: Long): Int {
        if (timeMs <= 0) return 1
        var sequence = 1
        while (sequence <= index.size()) {
            val entry = index.getEntry(sequence)
            if (entry != null && entry.getEndMs() > timeMs) return sequence
            sequence++
        }
        return if (index.size() == Int.MAX_VALUE) Int.MAX_VALUE else index.size() + 1
    }

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun parse(
            format: YoutubeSabrInfo.Format,
            initializationData: ByteArray
        ): YoutubeSabrFormatTimeline {
            val mimeType = format.getMimeType()
                ?: throw SabrProtocolException(
                    "Missing SABR format MIME type: itag=" + format.getItag()
                )
            val index: SabrSegmentIndex = when {
                mimeType.contains("mp4") ->
                    SabrMp4SegmentIndexParser.parse(initializationData, format)
                mimeType.contains("webm") ->
                    SabrWebmSegmentIndexParser.parse(initializationData, format)
                else -> throw SabrProtocolException("Unsupported SABR initialization format: $mimeType")
            }
            if (index.size() == 0) {
                throw SabrProtocolException("Empty SABR segment index: itag=" + format.getItag())
            }
            return YoutubeSabrFormatTimeline(index)
        }
    }
}
