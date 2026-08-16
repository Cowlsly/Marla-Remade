package org.schabi.newpipe.extractor.services.youtube.sabrng

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Observability state for a SABR session; it does not participate in requests. */
internal class YoutubeSabrSessionDiagnostics {
    private val diagnosticEvents = ArrayDeque<String>()
    private var diagnosticChars = 0

    @Volatile
    private var totalResponseBytes: Long = 0

    @Volatile
    private var maxResponseBytes: Long = 0

    @Volatile
    private var maxUmpPartBytes: Long = 0

    @Volatile
    private var maxMediaPartPayloadBytes: Long = 0

    @Volatile
    private var maxSegmentBytes: Long = 0

    @Volatile
    private var maxSegmentsPerResponse: Int = 0

    private val maxStreamProtectionStatus = AtomicInteger(-1)

    @Volatile
    private var traceEnabled = false

    private val traceLock = Any()
    private var traceResponseBytes: Long = 0
    private var traceMediaPayloadBytes: Long = 0
    private var traceControlPayloadBytes: Long = 0
    private var traceUmpOverheadBytes: Long = 0
    private val traceResponses = ArrayDeque<String>()

    @Synchronized
    fun addEvent(event: String) {
        val bounded = if (event.length > MAX_DIAGNOSTIC_CHARS) {
            event.substring(0, MAX_DIAGNOSTIC_CHARS)
        } else {
            event
        }
        while (diagnosticEvents.isNotEmpty() &&
            diagnosticChars + bounded.length > MAX_DIAGNOSTIC_CHARS
        ) {
            diagnosticChars -= diagnosticEvents.removeFirst().length
        }
        diagnosticEvents.addLast(bounded)
        diagnosticChars += bounded.length
    }

    @Synchronized
    fun getTrace(): String = diagnosticEvents.joinToString(" | ")

    fun recordResponse(result: YoutubeSabrResponse, requestNumber: Int) {
        totalResponseBytes += result.getResponseBytes()
        maxResponseBytes = Math.max(maxResponseBytes, result.getResponseBytes())
        maxUmpPartBytes = Math.max(maxUmpPartBytes, result.getMaxPartBytes())
        maxMediaPartPayloadBytes =
            Math.max(maxMediaPartPayloadBytes, result.getMaxMediaPartPayloadBytes())
        maxSegmentBytes = Math.max(maxSegmentBytes, result.getMaxSegmentBytes())
        maxSegmentsPerResponse = Math.max(maxSegmentsPerResponse, result.getSegmentCount())
        maxStreamProtectionStatus.accumulateAndGet(result.getStreamProtectionStatus()) { a, b ->
            Math.max(a, b)
        }
        if (!traceEnabled) {
            return
        }
        val umpOverheadBytes = Math.max(0, result.getResponseBytes() - result.getTotalPayloadBytes())
        synchronized(traceLock) {
            traceResponseBytes += result.getResponseBytes()
            traceMediaPayloadBytes += result.getMediaPayloadBytes()
            traceControlPayloadBytes += result.getControlPayloadBytes()
            traceUmpOverheadBytes += umpOverheadBytes
            addBoundedTraceEvent(
                traceResponses,
                "request=" + requestNumber +
                    ",elapsedMs=" + result.getRequestElapsedMs() +
                    ",firstSegmentMs=" + result.getFirstSegmentElapsedMs() +
                    ",bytes=" + result.getResponseBytes() +
                    ",mediaBytes=" + result.getMediaPayloadBytes() +
                    ",segments=" + result.getSegmentCount()
            )
        }
    }

    fun setTraceEnabled(enabled: Boolean) {
        traceEnabled = enabled
    }

    fun getTotalResponseBytes(): Long = totalResponseBytes
    fun getMaxResponseBytes(): Long = maxResponseBytes
    fun getMaxUmpPartBytes(): Long = maxUmpPartBytes
    fun getMaxMediaPartPayloadBytes(): Long = maxMediaPartPayloadBytes
    fun getMaxSegmentBytes(): Long = maxSegmentBytes
    fun getMaxSegmentsPerResponse(): Int = maxSegmentsPerResponse
    fun getMaxStreamProtectionStatus(): Int = maxStreamProtectionStatus.get()

    fun getMemorySummary(requestNumber: Int): String =
        "requestNumber=" + requestNumber +
            ", totalResponseBytes=" + totalResponseBytes +
            ", maxResponseBytes=" + maxResponseBytes +
            ", maxUmpPartBytes=" + maxUmpPartBytes +
            ", maxMediaPartPayloadBytes=" + maxMediaPartPayloadBytes +
            ", maxSegmentBytes=" + maxSegmentBytes +
            ", maxSegmentsPerResponse=" + maxSegmentsPerResponse

    fun snapshot(requestNumber: Int): YoutubeSabrSession.TraceSnapshot {
        synchronized(traceLock) {
            return YoutubeSabrSession.TraceSnapshot(
                traceResponseBytes, traceMediaPayloadBytes, traceControlPayloadBytes,
                traceUmpOverheadBytes, 0, requestNumber, emptyList(), emptyList(),
                ArrayList(traceResponses)
            )
        }
    }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARS = 32 * 1024
        private const val MAX_TRACE_EVENTS = 1024

        private fun addBoundedTraceEvent(events: ArrayDeque<String>, value: String) {
            if (events.size >= MAX_TRACE_EVENTS) {
                events.removeFirst()
            }
            events.addLast(value)
        }
    }
}
