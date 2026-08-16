package org.schabi.newpipe.extractor.services.youtube.sabrng

import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrFormatInitializationMetadata
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.UmpReader
import java.util.Collections

/** The complete HTTP, UMP control and media result of one SABR request. */
class YoutubeSabrResponse {
    private var info: YoutubeSabrInfo? = null
    private var segments: List<SabrMediaSegment> = emptyList()
    private var segmentCount = 0
    private var responseCode = 0
    private var contentType = ""
    private var responseBytes: Long = 0
    private var mediaPayloadBytes: Long = 0
    private var mediaPartPayloadBytes: Long = 0
    private var controlPayloadBytes: Long = 0
    private var totalPayloadBytes: Long = 0
    private var maxPartBytes: Long = 0
    private var maxMediaPartPayloadBytes: Long = 0
    private var maxSegmentBytes: Long = 0
    private var requestElapsedMs: Long = 0
    private var firstSegmentElapsedMs: Long = -1

    private val parts = ArrayList<UmpReader.UmpPart>()
    private val partSummaries = ArrayList<String>()
    private val wireFieldSummaries = ArrayList<String>()
    private val formatInitializationMetadata = ArrayList<SabrFormatInitializationMetadata>()
    private val mediaHeaders = ArrayList<SabrMediaHeader>()
    private val sabrContextUpdates = ArrayList<ByteArray>()
    private val liveMetadata = ArrayList<ByteArray>()
    private val mediaBytesByHeaderId = LinkedHashMap<Int, Long>()
    private val mediaEndHeaderIds = ArrayList<Int>()
    private val unknownPartTypes = ArrayList<Int>()
    private val malformedParts = ArrayList<String>()
    private val genericPartDescriptions = LinkedHashMap<Int, MutableList<String>>()
    private var redirectUrl: String? = null
    private var sabrError: String? = null
    private var nextRequestPolicy: ByteArray? = null
    private var sabrContextSendingPolicy: ByteArray? = null
    private var streamProtectionStatus = -1
    private var streamProtectionMaxRetries = -1
    private var backoffTimeMs = -1
    private var reloadRequested = false

    internal fun complete(
        requestInfo: YoutubeSabrInfo,
        responseSegments: List<SabrMediaSegment>,
        responseSegmentCount: Int,
        httpResponseCode: Int,
        responseContentType: String,
        rawResponseBytes: Long,
        rawMediaPayloadBytes: Long,
        rawMediaPartPayloadBytes: Long,
        rawControlPayloadBytes: Long,
        rawTotalPayloadBytes: Long,
        largestPartBytes: Long,
        largestMediaPartPayloadBytes: Long,
        largestSegmentBytes: Long,
        elapsedMs: Long,
        firstMediaElapsedMs: Long
    ) {
        info = requestInfo
        segments = responseSegments
        segmentCount = responseSegmentCount
        responseCode = httpResponseCode
        contentType = responseContentType
        responseBytes = rawResponseBytes
        mediaPayloadBytes = rawMediaPayloadBytes
        mediaPartPayloadBytes = rawMediaPartPayloadBytes
        controlPayloadBytes = rawControlPayloadBytes
        totalPayloadBytes = rawTotalPayloadBytes
        maxPartBytes = largestPartBytes
        maxMediaPartPayloadBytes = largestMediaPartPayloadBytes
        maxSegmentBytes = largestSegmentBytes
        requestElapsedMs = elapsedMs
        firstSegmentElapsedMs = firstMediaElapsedMs
    }

    fun addPart(part: UmpReader.UmpPart) {
        parts.add(part)
        addPartSummary(partSummaries, part.getType(), part.getSize())
    }

    fun setPartSummaries(summaries: List<String>) {
        partSummaries.clear()
        partSummaries.addAll(summaries)
    }

    fun addUnknownPartType(type: Int) {
        unknownPartTypes.add(type)
    }

    fun addWireFieldSummary(type: Int, summary: String) {
        wireFieldSummaries.add("$type={$summary}")
    }

    fun addGenericPartDescription(type: Int, description: String) {
        genericPartDescriptions.getOrPut(type) { ArrayList() }.add(description)
    }

    fun addMalformedPart(type: Int, size: Int, error: SabrProtocolException) {
        if (malformedParts.size >= MAX_MALFORMED_PARTS) return
        val message = error.message.toString()
        malformedParts.add(
            "$type:$size:" + if (message.length > MAX_MALFORMED_MESSAGE_CHARS) {
                message.substring(0, MAX_MALFORMED_MESSAGE_CHARS)
            } else {
                message
            }
        )
    }

    fun addFormatInitializationMetadata(value: SabrFormatInitializationMetadata) {
        formatInitializationMetadata.add(value)
    }

    fun addMediaHeader(value: SabrMediaHeader) {
        mediaHeaders.add(value)
    }

    fun addSabrContextUpdate(data: ByteArray) {
        sabrContextUpdates.add(data.clone())
    }

    fun addLiveMetadata(data: ByteArray) {
        liveMetadata.add(data.clone())
    }

    fun addMediaBytes(headerId: Int, bytes: Long) {
        mediaBytesByHeaderId[headerId] = (mediaBytesByHeaderId[headerId] ?: 0L) + bytes
    }

    fun addMediaEndHeaderId(headerId: Int) {
        mediaEndHeaderIds.add(headerId)
    }

    fun setRedirectUrl(value: String?) {
        redirectUrl = value
    }

    fun setSabrError(value: String?) {
        sabrError = value
    }

    fun setNextRequestPolicy(data: ByteArray) {
        nextRequestPolicy = data.clone()
    }

    fun setSabrContextSendingPolicy(data: ByteArray) {
        sabrContextSendingPolicy = data.clone()
    }

    fun setStreamProtectionStatus(value: Int) {
        streamProtectionStatus = value
    }

    fun setStreamProtectionMaxRetries(value: Int) {
        streamProtectionMaxRetries = value
    }

    fun setBackoffTimeMs(value: Int) {
        backoffTimeMs = value
    }

    fun setReloadRequested(value: Boolean) {
        reloadRequested = value
    }

    fun getInfo(): YoutubeSabrInfo = info!!
    fun getSegments(): List<SabrMediaSegment> = segments
    fun getSegmentCount(): Int = segmentCount
    fun getResponseCode(): Int = responseCode
    fun getContentType(): String = contentType
    fun getResponseBytes(): Long = responseBytes
    fun getMediaPayloadBytes(): Long = mediaPayloadBytes
    fun getMediaPartPayloadBytes(): Long = mediaPartPayloadBytes
    fun getControlPayloadBytes(): Long = controlPayloadBytes
    fun getTotalPayloadBytes(): Long = totalPayloadBytes
    fun getMaxPartBytes(): Long = maxPartBytes
    fun getMaxMediaPartPayloadBytes(): Long = maxMediaPartPayloadBytes
    fun getMaxSegmentBytes(): Long = maxSegmentBytes
    fun getRequestElapsedMs(): Long = requestElapsedMs
    fun getFirstSegmentElapsedMs(): Long = firstSegmentElapsedMs
    fun getParts(): List<UmpReader.UmpPart> = Collections.unmodifiableList(parts)
    fun getFormatInitializationMetadata(): List<SabrFormatInitializationMetadata> =
        Collections.unmodifiableList(formatInitializationMetadata)

    fun getMediaHeaders(): List<SabrMediaHeader> = Collections.unmodifiableList(mediaHeaders)
    fun getSabrContextUpdates(): List<ByteArray> = Collections.unmodifiableList(sabrContextUpdates)
    fun getLiveMetadata(): List<ByteArray> = Collections.unmodifiableList(liveMetadata)
    fun getRedirectUrl(): String? = redirectUrl
    fun getSabrError(): String? = sabrError
    fun getNextRequestPolicy(): ByteArray? = nextRequestPolicy?.clone()
    fun getSabrContextSendingPolicy(): ByteArray? = sabrContextSendingPolicy?.clone()
    fun getStreamProtectionStatus(): Int = streamProtectionStatus
    fun getStreamProtectionMaxRetries(): Int = streamProtectionMaxRetries
    fun getBackoffTimeMs(): Int = backoffTimeMs
    fun isReloadRequested(): Boolean = reloadRequested
    fun hasMedia(): Boolean = mediaHeaders.isNotEmpty() || mediaBytesByHeaderId.isNotEmpty()
    fun isNoMediaResponse(): Boolean = !hasMedia()
    fun isPolicyOnlyResponse(): Boolean = isNoMediaResponse() && nextRequestPolicy != null
    fun isAttestationRequired(): Boolean = streamProtectionStatus == ATTESTATION_REQUIRED
    fun isAttestationPending(): Boolean = streamProtectionStatus == ATTESTATION_PENDING

    fun getIntegrityIssues(): List<String> {
        val issues = ArrayList<String>()
        val headerIds = ArrayList<Int>()
        for (header in mediaHeaders) {
            if (headerIds.contains(header.getHeaderId())) {
                issues.add("duplicate-media-header:" + header.getHeaderId())
            }
            headerIds.add(header.getHeaderId())
            val bytes = mediaBytesByHeaderId[header.getHeaderId()]
            if (bytes == null) {
                issues.add("missing-media:" + header.getHeaderId())
            } else if (header.getContentLength() >= 0 && bytes != header.getContentLength()) {
                issues.add(
                    "length-mismatch:" + header.getHeaderId() + ":expected=" +
                        header.getContentLength() + ":actual=" + bytes
                )
            }
            if (!mediaEndHeaderIds.contains(header.getHeaderId())) {
                issues.add("missing-media-end:" + header.getHeaderId())
            }
        }
        for (id in mediaBytesByHeaderId.keys) {
            if (!headerIds.contains(id)) issues.add("media-without-header:$id")
        }
        for (id in mediaEndHeaderIds) {
            if (!headerIds.contains(id)) issues.add("media-end-without-header:$id")
        }
        return issues
    }

    fun summarizeForDiagnostics(): String =
        "parts=" + partSummaries + ", wireFields=" + wireFieldSummaries +
            ", controls=" + genericPartDescriptions + ", mediaHeaders=" + mediaHeaders.size +
            ", mediaBytes=" + mediaBytesByHeaderId + ", mediaEnds=" + mediaEndHeaderIds +
            ", integrity=" + getIntegrityIssues() + ", malformedParts=" + malformedParts +
            ", unknownParts=" + unknownPartTypes + ", protection=" +
            streamProtectionStatus + '/' + streamProtectionMaxRetries +
            ", backoffMs=" + backoffTimeMs + ", reload=" + reloadRequested

    fun summarizeNoMediaResponse(): String =
        "parts=" + parts.size + ", status=" + streamProtectionStatus +
            ", maxRetries=" + streamProtectionMaxRetries + ", backoffMs=" + backoffTimeMs +
            ", policy=" + (nextRequestPolicy != null) + ", reload=" + reloadRequested +
            ", redirect=" + (redirectUrl != null && redirectUrl!!.isNotEmpty()) +
            ", error=" + (sabrError ?: "null")

    companion object {
        const val ATTESTATION_PENDING = 2
        const val ATTESTATION_REQUIRED = 3
        private const val MAX_MALFORMED_PARTS = 16
        private const val MAX_MALFORMED_MESSAGE_CHARS = 256

        @JvmStatic
        fun addPartSummary(summaries: MutableList<String>, type: Int, size: Int) {
            val value = "$type:$size"
            if (summaries.isEmpty()) {
                summaries.add(value)
                return
            }
            val lastIndex = summaries.size - 1
            val last = summaries[lastIndex]
            when {
                last == value -> summaries[lastIndex] = value + "x2"
                last.startsWith(value + 'x') -> summaries[lastIndex] = value + 'x' +
                    (last.substring(value.length + 1).toInt() + 1)
                else -> summaries.add(value)
            }
        }
    }
}
