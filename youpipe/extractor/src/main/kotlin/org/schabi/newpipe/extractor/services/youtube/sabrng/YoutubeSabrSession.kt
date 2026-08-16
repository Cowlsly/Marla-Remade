package org.schabi.newpipe.extractor.services.youtube.sabrng

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrRecoverableException
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Collections

/**
 * Drives the session-based SABR protocol: builds requests, sends them via
 * [YoutubeSabrRequestHelper], ingests control/attestation/protection state, handles redirects,
 * backoff and bounded recovery, and forwards media segments to a consumer.
 */
class YoutubeSabrSession @JvmOverloads constructor(
    private val info: YoutubeSabrInfo,
    private val segmentSpoolDirectory: File? = null
) {
    private var serverAbrStreamingUrl: String
    private var requestNumber = 0
    private var redirectCount = 0
    private var consecutiveIntegrityFailures = 0
    private var consecutiveAttestationPendingResponses = 0
    private var playbackCookie: ByteArray? = null
    private val sabrContexts = LinkedHashMap<Int, ByteArray>()
    private val activeSabrContextTypes = LinkedHashSet<Int>()
    private var live = false
    private var postLiveDvr = false
    private var liveHeadSequenceNumber: Long = -1
    private var liveHeadTimeMs: Long = -1
    private var bandwidthEstimate: Long = -1

    @Volatile
    private var backoffDeadlineNs: Long = 0
    private var poToken: ByteArray? = null

    private val diagnostics = YoutubeSabrSessionDiagnostics()

    init {
        val url = info.getServerAbrStreamingUrl()
        require(!url.isNullOrEmpty()) { "Missing SABR streaming URL" }
        serverAbrStreamingUrl = url
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchNextResponse(
        request: YoutubeSabrRequest,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer?
    ): YoutubeSabrResponse {
        val playbackState = request.getPlaybackState()
        val audioTrack = request.getAudioTrack()
        val videoTrack = request.getVideoTrack()
        addDiagnosticEvent(
            "request n=" + requestNumber +
                " playerMs=" + playbackState.getPlayerTimeMs() +
                " audioThrough=" + (audioTrack?.getBufferedThrough() ?: 0) +
                " videoThrough=" + (videoTrack?.getBufferedThrough() ?: 0) +
                " selectedTracks=" + request.hasSelectedTracks() +
                " poTokenBytes=" + (poToken?.size ?: -1)
        )
        val timedConsumer = segmentConsumer
        val startedConsumer = SabrStreamingResponseReader.SegmentConsumer { }
        val result: YoutubeSabrResponse
        try {
            result = YoutubeSabrRequestHelper.post(
                info, request, this, serverAbrStreamingUrl, requestNumber, timedConsumer,
                startedConsumer, segmentSpoolDirectory
            )
        } catch (e: IOException) {
            addDiagnosticEvent(
                "request_failed n=" + requestNumber + " type=" + e.javaClass.simpleName +
                    " message=" + e.message.toString()
            )
            throw e
        } catch (e: ExtractionException) {
            addDiagnosticEvent(
                "request_failed n=" + requestNumber + " type=" + e.javaClass.simpleName +
                    " message=" + e.message.toString()
            )
            throw e
        }
        addDiagnosticEvent(
            "response n=" + requestNumber +
                " http=" + result.getResponseCode() +
                " contentType=" + result.getContentType() +
                " segments=" + (
                    if (result.getSegments().isEmpty()) {
                        "count=" + result.getSegmentCount()
                    } else {
                        summarizeSegments(result.getSegments())
                    }
                    ) +
                " decoded={" + result.summarizeForDiagnostics() + '}'
        )
        if (result.getBackoffTimeMs() > MAX_BACKOFF_MS) {
            throw SabrProtocolException("SABR backoff exceeds limit: " + result.getBackoffTimeMs() + "ms")
        }
        updateBackoff(result.getBackoffTimeMs())
        diagnostics.recordResponse(result, requestNumber)
        updateBandwidthEstimate(result.getResponseBytes(), result.getRequestElapsedMs())
        requestNumber++
        return result
    }

    @Synchronized
    @Throws(IOException::class, ExtractionException::class)
    fun requestOnce(
        request: YoutubeSabrRequest,
        consumer: SabrStreamingResponseReader.SegmentConsumer
    ): RequestResult {
        val backoffRemainingMs = getBackoffRemainingMs()
        if (backoffRemainingMs > 0) {
            return RequestResult(0, Math.min(Int.MAX_VALUE.toLong(), backoffRemainingMs).toInt(), true)
        }
        val result = fetchAndProcessResponse(request, consumer)
            ?: return RequestResult(
                0, Math.min(Int.MAX_VALUE.toLong(), getBackoffRemainingMs()).toInt(), false
            )
        return RequestResult(result.getSegmentCount(), result.getBackoffTimeMs(), false)
    }

    class RequestResult internal constructor(
        private val segmentCount: Int,
        private val backoffMs: Int,
        private val deferred: Boolean
    ) {
        fun getSegmentCount(): Int = segmentCount
        fun getBackoffMs(): Int = backoffMs

        /** True when no HTTP request was sent because this session is still backing off. */
        fun isDeferred(): Boolean = deferred
    }

    /** Remaining server-requested delay before another transaction may start. */
    fun getBackoffRemainingMs(): Long {
        val remainingNs = backoffDeadlineNs - System.nanoTime()
        return if (remainingNs <= 0) 0 else Math.max(1, remainingNs / 1_000_000L)
    }

    private fun updateBackoff(backoffMs: Int) {
        backoffDeadlineNs = if (backoffMs <= 0) 0 else System.nanoTime() + backoffMs * 1_000_000L
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchAndProcessResponse(
        request: YoutubeSabrRequest,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer
    ): YoutubeSabrResponse? {
        val result: YoutubeSabrResponse
        try {
            result = fetchNextResponse(request, segmentConsumer)
        } catch (e: SabrRecoverableException) {
            if (recoverFromStreamingMediaException(e)) {
                return null
            }
            throw e
        }
        val decoded = result
        val integrityIssues = decoded.getIntegrityIssues()
        if (integrityIssues.isNotEmpty()) {
            if (isRecoverableIncompleteMediaResponse(integrityIssues)) {
                if (recoverFromIncompleteMediaResponse()) {
                    return null
                }
                throw SabrProtocolException("SABR media integrity issue: $integrityIssues")
            }
            throw SabrProtocolException("SABR media integrity issue: $integrityIssues")
        }
        consecutiveIntegrityFailures = 0
        handleControlResponse(result)
        return result
    }

    @Throws(SabrProtocolException::class)
    private fun handleControlResponse(result: YoutubeSabrResponse) {
        val decoded = result
        if (decoded.isAttestationPending() && decoded.isNoMediaResponse()) {
            consecutiveAttestationPendingResponses++
            addDiagnosticEvent(
                "attestation_pending_no_media count=$consecutiveAttestationPendingResponses"
            )
            if (consecutiveAttestationPendingResponses >= MAX_CONSECUTIVE_ATTESTATION_PENDING_RESPONSES) {
                throw SabrAttestationException(
                    "SABR attestation remained pending without media for " +
                        consecutiveAttestationPendingResponses + " consecutive responses: " +
                        decoded.summarizeNoMediaResponse()
                )
            }
        } else {
            consecutiveAttestationPendingResponses = 0
        }
        ingestControl(decoded)

        val redirectUrl = decoded.getRedirectUrl()
        if (!redirectUrl.isNullOrEmpty()) {
            if (++redirectCount > MAX_REDIRECTS_PER_SESSION) {
                throw SabrProtocolException("SABR redirect limit exceeded: redirects=$redirectCount")
            }
            validateRedirectUrl(redirectUrl)
            serverAbrStreamingUrl = redirectUrl
        }

        if (decoded.getSabrError() != null) {
            throw SabrProtocolException("SABR error: " + decoded.getSabrError())
        }
        if (decoded.isAttestationRequired()) {
            throw SabrAttestationException(
                "SABR attestation required: " + decoded.summarizeNoMediaResponse()
            )
        }
        if (decoded.isReloadRequested()) {
            throw SabrProtocolException(
                "SABR requested player reload: " + decoded.summarizeNoMediaResponse()
            )
        }

        if (result.getSegmentCount() > 0) {
            redirectCount = 0
        }
    }

    private fun ingestControl(response: YoutubeSabrResponse) {
        val nextRequestPolicy = response.getNextRequestPolicy()
        if (nextRequestPolicy != null) {
            ingestNextRequestPolicy(nextRequestPolicy)
        }
        for (update in response.getSabrContextUpdates()) {
            ingestContextUpdate(update)
        }
        val sendingPolicy = response.getSabrContextSendingPolicy()
        if (sendingPolicy != null) {
            ingestContextSendingPolicy(sendingPolicy)
        }
        for (metadata in response.getLiveMetadata()) {
            ingestLiveMetadata(metadata)
        }
    }

    private fun ingestNextRequestPolicy(data: ByteArray) {
        try {
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 7) {
                    playbackCookie = field.getBytes()
                }
            }
        } catch (ignored: SabrProtocolException) {
            // The response decoder already records malformed control parts.
        }
    }

    private fun ingestLiveMetadata(data: ByteArray) {
        val live = true
        var postLiveDvr = false
        var liveHeadSequenceNumber: Long = -1
        var liveHeadTimeMs: Long = -1
        try {
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    3 -> liveHeadSequenceNumber = field.getVarint()
                    4 -> liveHeadTimeMs = field.getVarint()
                    8 -> postLiveDvr = field.getVarint() != 0L
                    else -> {}
                }
            }
        } catch (ignored: SabrProtocolException) {
            return
        }
        this.live = live
        this.postLiveDvr = postLiveDvr
        if (liveHeadSequenceNumber >= 0) this.liveHeadSequenceNumber = liveHeadSequenceNumber
        if (liveHeadTimeMs >= 0) this.liveHeadTimeMs = liveHeadTimeMs
    }

    private fun ingestContextUpdate(data: ByteArray) {
        var type = -1
        var value: ByteArray? = null
        var sendByDefault = false
        var writePolicy = -1
        try {
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> type = field.getVarint().toInt()
                    3 -> value = field.getBytes()
                    4 -> sendByDefault = field.getVarint() != 0L
                    5 -> writePolicy = field.getVarint().toInt()
                    else -> {}
                }
            }
        } catch (ignored: SabrProtocolException) {
            return
        }
        if (type < 0 || value == null || value.isEmpty() ||
            writePolicy == 2 && sabrContexts.containsKey(type)
        ) {
            return
        }
        sabrContexts[type] = value
        if (sendByDefault) {
            activeSabrContextTypes.add(type)
        }
    }

    private fun ingestContextSendingPolicy(data: ByteArray) {
        try {
            for (field in SabrProto.readFields(data)) {
                val values: List<Long> = when (field.getWireType()) {
                    SabrProto.WIRE_VARINT -> Collections.singletonList(field.getVarint())
                    SabrProto.WIRE_LENGTH_DELIMITED -> SabrProto.readPackedVarints(field.getBytes())
                    else -> emptyList()
                }
                for (value in values) {
                    val type = value.toInt()
                    when (field.getNumber()) {
                        1 -> activeSabrContextTypes.add(type)
                        2 -> activeSabrContextTypes.remove(type)
                        3 -> {
                            sabrContexts.remove(type)
                            activeSabrContextTypes.remove(type)
                        }
                    }
                }
            }
        } catch (ignored: SabrProtocolException) {
            // The response decoder already records malformed control parts.
        }
    }

    internal fun getRawPlaybackCookie(): ByteArray? = playbackCookie

    @Synchronized
    fun clearPlaybackCookie() {
        playbackCookie = null
    }

    internal fun getActiveSabrContexts(): Map<Int, ByteArray> {
        val active = LinkedHashMap<Int, ByteArray>()
        for (type in activeSabrContextTypes) {
            val value = sabrContexts[type]
            if (value != null) active[type] = value
        }
        return active
    }

    internal fun getUnsentSabrContextTypes(): Collection<Int> {
        val unsent = ArrayList<Int>()
        for (type in sabrContexts.keys) {
            if (!activeSabrContextTypes.contains(type)) unsent.add(type)
        }
        return unsent
    }

    /** True once the server has reported this is a live stream (foundation for live support). */
    fun isLive(): Boolean = live

    /** Latest segment the live edge has reached, or -1 if unknown / not live. */
    fun getLiveHeadSequenceNumber(): Long = liveHeadSequenceNumber

    fun isPostLiveDvr(): Boolean = postLiveDvr

    fun getLiveHeadTimeMs(): Long = liveHeadTimeMs

    fun getRequestNumber(): Int = requestNumber

    @Synchronized
    fun setPoToken(value: ByteArray?) {
        poToken = value?.clone()
    }

    internal fun getRawPoToken(): ByteArray? = poToken

    internal fun getBandwidthEstimate(): Long = bandwidthEstimate

    private fun updateBandwidthEstimate(responseBytes: Long, elapsedMs: Long) {
        if (responseBytes <= 0 || elapsedMs <= 0) return
        val sample = responseBytes * 8_000L / elapsedMs
        bandwidthEstimate = if (bandwidthEstimate <= 0) sample else (bandwidthEstimate * 3 + sample) / 4
    }

    private fun matchesFormat(format: YoutubeSabrInfo.Format, segment: SabrMediaSegment): Boolean {
        if (segment.getHeader().getItag() != format.getItag()) return false
        val headerXtags = segment.getHeader().getXtags()
        if (headerXtags != null) return headerXtags == format.getXtags()
        var sameItagFormats = 0
        for (candidate in info.getFormats()) {
            if (candidate.getItag() == format.getItag()) sameItagFormats++
        }
        return sameItagFormats == 1
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun recoverFromStreamingMediaException(error: SabrRecoverableException): Boolean {
        addDiagnosticEvent(
            "streaming_integrity_recoverable type=" + error.javaClass.simpleName +
                " message=" + error.message
        )
        return recoverFromIncompleteMediaResponse()
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun recoverFromIncompleteMediaResponse(): Boolean {
        consecutiveIntegrityFailures++
        return consecutiveIntegrityFailures < MAX_INCOMPLETE_MEDIA_RESPONSES
    }

    @Synchronized
    fun addDiagnosticEvent(event: String) {
        diagnostics.addEvent(event)
    }

    @Synchronized
    fun getDiagnosticTrace(): String = diagnostics.getTrace()

    /** Raw bytes consumed from all SABR HTTP response bodies in this session. */
    fun getTotalResponseBytes(): Long = diagnostics.getTotalResponseBytes()
    fun getMaxResponseBytes(): Long = diagnostics.getMaxResponseBytes()
    fun getMaxUmpPartBytes(): Long = diagnostics.getMaxUmpPartBytes()
    fun getMaxMediaPartPayloadBytes(): Long = diagnostics.getMaxMediaPartPayloadBytes()
    fun getMaxSegmentBytes(): Long = diagnostics.getMaxSegmentBytes()
    fun getMaxSegmentsPerResponse(): Int = diagnostics.getMaxSegmentsPerResponse()
    fun getMaxStreamProtectionStatus(): Int = diagnostics.getMaxStreamProtectionStatus()

    fun getMemoryDiagnosticSummary(): String = diagnostics.getMemorySummary(requestNumber)

    fun setTraceEnabled(traceEnabled: Boolean) {
        diagnostics.setTraceEnabled(traceEnabled)
    }

    fun getTraceSnapshot(): TraceSnapshot = diagnostics.snapshot(requestNumber)

    class TraceSnapshot internal constructor(
        private val responseBytes: Long,
        private val mediaPayloadBytes: Long,
        private val controlPayloadBytes: Long,
        private val umpOverheadBytes: Long,
        private val discardedBytes: Long,
        private val requestNumber: Int,
        segments: List<String>,
        discards: List<String>,
        responses: List<String>
    ) {
        private val segments: List<String> = Collections.unmodifiableList(segments)
        private val discards: List<String> = Collections.unmodifiableList(discards)
        private val responses: List<String> = Collections.unmodifiableList(responses)

        fun getResponseBytes(): Long = responseBytes
        fun getMediaPayloadBytes(): Long = mediaPayloadBytes
        fun getControlPayloadBytes(): Long = controlPayloadBytes
        fun getUmpOverheadBytes(): Long = umpOverheadBytes
        fun getDiscardedBytes(): Long = discardedBytes
        fun getRequestNumber(): Int = requestNumber
        fun getSegments(): List<String> = segments
        fun getDiscards(): List<String> = discards
        fun getResponses(): List<String> = responses
    }

    companion object {
        private const val MAX_REDIRECTS_PER_SESSION = 3
        private const val MAX_INCOMPLETE_MEDIA_RESPONSES = 3
        private const val MAX_CONSECUTIVE_ATTESTATION_PENDING_RESPONSES = 3
        private const val MAX_BACKOFF_MS = 30_000

        private fun isRecoverableIncompleteMediaResponse(integrityIssues: List<String>): Boolean {
            if (integrityIssues.isEmpty()) {
                return false
            }
            for (issue in integrityIssues) {
                if (!issue.startsWith("length-mismatch:") &&
                    !issue.startsWith("missing-media-end:") &&
                    !issue.startsWith("missing-media:") &&
                    !issue.startsWith("media-without-header:") &&
                    !issue.startsWith("media-end-without-header:")
                ) {
                    return false
                }
            }
            return true
        }

        @Throws(SabrProtocolException::class)
        private fun validateRedirectUrl(redirectUrl: String) {
            try {
                val uri = URI.create(redirectUrl)
                val host = uri.host
                if (!"https".equals(uri.scheme, ignoreCase = true) || host == null ||
                    !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
                ) {
                    throw SabrProtocolException("SABR redirect escaped the GoogleVideo Host")
                }
            } catch (error: IllegalArgumentException) {
                throw SabrProtocolException("Malformed SABR redirect URL", error)
            }
        }

        private fun summarizeSegments(segments: List<SabrMediaSegment>): String {
            if (segments.isEmpty()) {
                return "[]"
            }
            val summary = StringBuilder("[")
            for (i in segments.indices) {
                if (i > 0) {
                    summary.append(',')
                }
                val segment = segments[i]
                summary.append(segment.getHeader().getItag()).append(':')
                summary.append(
                    if (segment.getHeader().isInitSegment()) {
                        "init"
                    } else {
                        segment.getHeader().getSequenceNumber()
                    }
                )
            }
            return summary.append(']').toString()
        }
    }
}
