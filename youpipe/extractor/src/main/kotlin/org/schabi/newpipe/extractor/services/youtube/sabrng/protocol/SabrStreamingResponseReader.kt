package org.schabi.newpipe.extractor.services.youtube.sabrng.protocol

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrResponse
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrRecoverableException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegmentCollector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Streaming counterpart of [SabrResponseDecoder.decode]: parse the UMP envelope from a stream one
 * part at a time, assembling MEDIA segments on the fly (via [SabrMediaSegmentCollector.Incremental])
 * so the big MEDIA payloads are never all held at once. Only the small control parts are kept and
 * decoded into a [YoutubeSabrResponse].
 */
object SabrStreamingResponseReader {
    private const val MAX_CONTROL_PARTS = 512
    private const val MAX_CONTROL_PAYLOAD_BYTES = 512 * 1024L

    /** Receives one completed media segment as it becomes available. */
    fun interface SegmentConsumer {
        @Throws(SabrProtocolException::class)
        fun accept(segment: SabrMediaSegment)
    }

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun read(input: InputStream): Result = read(input, null)

    /**
     * Streams completed segments directly to [segmentConsumer]. When a consumer is supplied,
     * completed segments are not retained by the result, bounding the response reader to one open
     * segment instead of the sum of every segment in the response.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun read(input: InputStream, segmentConsumer: SegmentConsumer?): Result =
        read(input, segmentConsumer, null, null)

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun read(
        input: InputStream,
        segmentConsumer: SegmentConsumer?,
        segmentStartConsumer: SegmentConsumer?,
        spoolDirectory: File?
    ): Result {
        val controlParts = ArrayList<UmpReader.UmpPart>()
        val partSummaries = ArrayList<String>()
        val segments = ArrayList<SabrMediaSegment>()
        var segmentCount = 0
        var mediaPayloadBytes = 0L
        var mediaPartPayloadBytes = 0L
        var controlPayloadBytes = 0L
        var totalPayloadBytes = 0L
        var maxPartBytes = 0L
        var maxMediaPartPayloadBytes = 0L
        var maxSegmentBytes = 0L
        // headerId -> total media bytes seen, so the decoded response passes the same integrity
        // check (getIntegrityIssues -> "missing-media") as the buffered path WITHOUT the bytes.
        val mediaBytesByHeaderId = HashMap<Int, Long>()
        val collector = SabrMediaSegmentCollector.Incremental(spoolDirectory)
        try {
            UmpReader.readPayloadsUntil(input) { type, size, payloadStream ->
                YoutubeSabrResponse.addPartSummary(partSummaries, type, size)
                maxPartBytes = Math.max(maxPartBytes, size.toLong())
                totalPayloadBytes += size
                if (type != SabrResponseDecoder.MEDIA &&
                    (controlParts.size >= MAX_CONTROL_PARTS ||
                        controlPayloadBytes + size > MAX_CONTROL_PAYLOAD_BYTES)
                ) {
                    throw SabrProtocolException("SABR control response exceeded Host limit")
                }
                when (type) {
                    SabrResponseDecoder.MEDIA_HEADER -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes += payload.size
                        // small (just the header) -> keep so the decoder records it (observeHeader).
                        controlParts.add(UmpReader.UmpPart(type, payload.size, payload))
                        try {
                            val started = collector.onMediaHeader(payload)
                            if (started != null && segmentStartConsumer != null) {
                                segmentStartConsumer.accept(started)
                            }
                        } catch (ignored: SabrProtocolException) {
                            if (!isMalformedMediaHeader(payload)) {
                                throw ignored
                            }
                            // decodeParts records the malformed header. Following MEDIA is left
                            // without an open header so integrity recovery requests a clean batch.
                        }
                    }
                    SabrResponseDecoder.MEDIA -> {
                        mediaPartPayloadBytes += size
                        if (size > 0) {
                            val headerId = payloadStream.read()
                            if (headerId < 0) {
                                throw SabrRecoverableException(
                                    "Unexpected EOF while reading SABR media header id"
                                )
                            }
                            val mediaBytes = size - 1
                            // big payload -> assemble into the open segment, do NOT retain.
                            collector.onMedia(headerId, payloadStream, mediaBytes)
                            maxMediaPartPayloadBytes =
                                Math.max(maxMediaPartPayloadBytes, mediaBytes.toLong())
                            mediaPayloadBytes += mediaBytes
                            mediaBytesByHeaderId[headerId] =
                                (mediaBytesByHeaderId[headerId] ?: 0L) + mediaBytes
                        }
                    }
                    SabrResponseDecoder.MEDIA_END -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes += payload.size
                        val segment = collector.onMediaEnd(payload)
                        controlParts.add(UmpReader.UmpPart(type, payload.size, payload))
                        if (segment != null) {
                            segmentCount++
                            maxSegmentBytes = Math.max(maxSegmentBytes, segment.getLength().toLong())
                            if (segmentConsumer == null) {
                                segments.add(segment)
                            } else {
                                segmentConsumer.accept(segment)
                            }
                        }
                    }
                    else -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes += payload.size
                        controlParts.add(UmpReader.UmpPart(type, payload.size, payload))
                    }
                }
                true
            }
        } finally {
            // Any header left open after EOF, cancellation or failure must wake a growing-file
            // reader. Completed segments have already been removed from the collector.
            collector.abort()
        }
        val decoded = SabrResponseDecoder.decodeParts(controlParts)
        decoded.setPartSummaries(partSummaries)
        for ((key, value) in mediaBytesByHeaderId) {
            decoded.addMediaBytes(key, value)
        }
        return Result(
            decoded, segments, segmentCount, mediaPayloadBytes, mediaPartPayloadBytes,
            controlPayloadBytes, totalPayloadBytes, maxPartBytes, maxMediaPartPayloadBytes,
            maxSegmentBytes
        )
    }

    @Throws(IOException::class)
    private fun readPayloadBytes(input: InputStream, size: Int): ByteArray {
        val output = ByteArrayOutputStream(size)
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, Math.min(buffer.size, remaining))
            if (read < 0) {
                throw IOException("Unexpected EOF while reading UMP part data")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun isMalformedMediaHeader(payload: ByteArray): Boolean {
        return try {
            SabrMediaHeader.decode(payload)
            false
        } catch (e: SabrProtocolException) {
            true
        }
    }

    /** The decoded control response plus the segments assembled while streaming. */
    class Result internal constructor(
        private val probeResult: YoutubeSabrResponse,
        private val segments: List<SabrMediaSegment>,
        private val segmentCount: Int,
        private val mediaPayloadBytes: Long,
        private val mediaPartPayloadBytes: Long,
        private val controlPayloadBytes: Long,
        private val totalPayloadBytes: Long,
        private val maxPartBytes: Long,
        private val maxMediaPartPayloadBytes: Long,
        private val maxSegmentBytes: Long
    ) {
        fun getProbeResult(): YoutubeSabrResponse = probeResult
        fun getSegments(): List<SabrMediaSegment> = segments
        fun getSegmentCount(): Int = segmentCount
        fun getMediaPayloadBytes(): Long = mediaPayloadBytes
        fun getMediaPartPayloadBytes(): Long = mediaPartPayloadBytes
        fun getControlPayloadBytes(): Long = controlPayloadBytes
        fun getTotalPayloadBytes(): Long = totalPayloadBytes
        fun getMaxPartBytes(): Long = maxPartBytes
        fun getMaxMediaPartPayloadBytes(): Long = maxMediaPartPayloadBytes
        fun getMaxSegmentBytes(): Long = maxSegmentBytes
    }
}
