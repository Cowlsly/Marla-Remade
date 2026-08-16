package com.vayunmathur.youpipe.util.sabr

import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives a session-based ([YoutubeSabrSession]) SABR stream for media3 playback: a background pump
 * repeatedly issues `requestOnce` around the current playhead, caches emitted [SabrMediaSegment]s by
 * (itag, sequence), honors server backoff, and rotates identity on attestation failure via the
 * supplied [onAttestationFailure] hook. Segments are served to the media3 data source through the
 * blocking [getMediaSegment].
 */
class SabrNgSession(
    private val spec: SabrNgSourceSpec,
    spoolDirectory: File?,
    private val onAttestationFailure: (() -> YoutubeSabrInfo?)? = null
) {
    @Volatile
    private var session: YoutubeSabrSession = newSession(spec.info, spoolDirectory, spec.poToken)
    private val spoolDirectory: File? = spoolDirectory

    private val mediaCache = ConcurrentHashMap<Long, SabrMediaSegment>()
    private val initCache = ConcurrentHashMap<Int, SabrMediaSegment>()
    private val lock = Object()

    @Volatile
    private var audioBufferedThrough = 0

    @Volatile
    private var videoBufferedThrough = 0

    @Volatile
    private var playerTimeMs = 0L

    @Volatile
    private var playbackRate = 1.0f

    @Volatile
    private var running = false

    @Volatile
    private var terminal: IOException? = null

    private var pumpThread: Thread? = null

    private fun newSession(
        info: YoutubeSabrInfo,
        spoolDirectory: File?,
        poToken: ByteArray?
    ): YoutubeSabrSession {
        val created = YoutubeSabrSession(info, spoolDirectory)
        if (poToken != null && poToken.isNotEmpty()) {
            created.setPoToken(poToken)
        }
        return created
    }

    @Synchronized
    fun start() {
        if (running) {
            return
        }
        running = true
        val thread = Thread({ pumpLoop() }, "SabrNgSession-${spec.videoId}")
        thread.isDaemon = true
        pumpThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        running = false
        pumpThread?.interrupt()
        pumpThread = null
        synchronized(lock) { lock.notifyAll() }
        for (segment in mediaCache.values) {
            segment.delete()
        }
        mediaCache.clear()
        initCache.clear()
    }

    fun setPlayerTimeMs(positionMs: Long) {
        playerTimeMs = maxOf(0, positionMs)
    }

    fun setPlaybackRate(rate: Float) {
        if (rate > 0) {
            playbackRate = rate
        }
    }

    /** Repositions both tracks so the pump re-requests media from [positionMs]. */
    fun requestSeek(positionMs: Long) {
        val clamped = maxOf(0, positionMs)
        playerTimeMs = clamped
        val audioSeq = spec.audioTimeline.getSequenceAt(clamped)
        val videoSeq = spec.videoTimeline.getSequenceAt(clamped)
        audioBufferedThrough = maxOf(0, audioSeq - 1)
        videoBufferedThrough = maxOf(0, videoSeq - 1)
        synchronized(lock) { lock.notifyAll() }
    }

    fun getInitialization(itag: Int): ByteArray? {
        initCache[itag]?.let { return it.getData() }
        return spec.getInitializationData(itag)
    }

    /** Blocks until the requested media segment is available, the session fails, or timeout. */
    @Throws(IOException::class)
    fun getMediaSegment(itag: Int, sequenceNumber: Int, timeoutMs: Long): SabrMediaSegment? {
        val key = key(itag, sequenceNumber)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                mediaCache[key]?.let { return it }
                terminal?.let { throw it }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    return null
                }
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted awaiting SABR segment", e)
                }
            }
        }
    }

    private fun pumpLoop() {
        val consumer = SabrStreamingResponseReader.SegmentConsumer { segment -> onSegment(segment) }
        while (running) {
            val backoffMs = session.getBackoffRemainingMs()
            if (backoffMs > 0) {
                sleepQuietly(minOf(backoffMs, 500L))
                continue
            }
            if (isFullyBuffered()) {
                sleepQuietly(200L)
                continue
            }
            val request = YoutubeSabrRequest.playback(
                playerTimeMs,
                playbackRate,
                listOf(
                    YoutubeSabrRequest.Track.of(
                        spec.audioFormat, spec.audioTimeline, audioBufferedThrough
                    ),
                    YoutubeSabrRequest.Track.of(
                        spec.videoFormat, spec.videoTimeline, videoBufferedThrough
                    )
                )
            )
            try {
                session.requestOnce(request, consumer)
            } catch (e: SabrAttestationException) {
                if (!rotateIdentity(e)) {
                    fail(IOException("SABR attestation failed", e))
                    return
                }
            } catch (e: SabrProtocolException) {
                fail(IOException("SABR protocol error", e))
                return
            } catch (e: IOException) {
                fail(e)
                return
            } catch (e: Exception) {
                fail(IOException("SABR request failed", e))
                return
            }
        }
    }

    private fun rotateIdentity(cause: SabrAttestationException): Boolean {
        val refetch = onAttestationFailure ?: return false
        return try {
            val refreshed = refetch() ?: return false
            val replacement = newSession(refreshed, spoolDirectory, refreshed.getPoToken())
            session = replacement
            Log.d(TAG, "rotated SABR identity for ${spec.videoId} after attestation failure")
            true
        } catch (e: Exception) {
            Log.d(TAG, "SABR identity rotation failed for ${spec.videoId}: $e", cause)
            false
        }
    }

    private fun onSegment(segment: SabrMediaSegment) {
        val header = segment.getHeader()
        val itag = header.getItag()
        if (header.isInitSegment()) {
            initCache[itag] = segment
        } else {
            mediaCache[key(itag, header.getSequenceNumber())] = segment
            advanceBufferedThrough(itag)
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun advanceBufferedThrough(itag: Int) {
        when (itag) {
            spec.audioFormat.getItag() -> audioBufferedThrough = contiguousEnd(itag, audioBufferedThrough)
            spec.videoFormat.getItag() -> videoBufferedThrough = contiguousEnd(itag, videoBufferedThrough)
        }
    }

    private fun contiguousEnd(itag: Int, from: Int): Int {
        var next = from
        while (mediaCache.containsKey(key(itag, next + 1))) {
            next++
        }
        return next
    }

    private fun isFullyBuffered(): Boolean =
        audioBufferedThrough >= spec.audioTimeline.getEndSequence() &&
            videoBufferedThrough >= spec.videoTimeline.getEndSequence()

    private fun fail(error: IOException) {
        terminal = error
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    private fun key(itag: Int, sequenceNumber: Int): Long =
        (itag.toLong() shl 32) or (sequenceNumber.toLong() and 0xffffffffL)

    private companion object {
        private const val TAG = "SabrNgSession"
    }
}
