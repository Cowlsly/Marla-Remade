package com.vayunmathur.youpipe.util.sabr

import android.util.Log
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Extractor→player handoff and source-spec factory for the session-based ([SabrNgSession]) SABR
 * bridge. The extractor publishes a [YoutubeSabrInfo] per videoId; the player later builds a
 * [SabrNgSourceSpec] by driving a short SABR "preparation" transaction to fetch each selected
 * format's initialization segment (which carries the segment index used to publish the DASH
 * timeline).
 *
 * Note: SABR-only formats carry NO per-format media URL, so the init segment CANNOT be fetched with
 * a direct HTTP range GET (that path — `YoutubeSabrRequestHelper.fetchInitializationData` — needs a
 * `initializationUrl` that these formats do not have). The init segment is delivered by the SABR
 * server itself in response to a preparation request, exactly like the follow-up media segments.
 */
object SabrNgSessionStore {
    private const val TAG = "SabrNgSessionStore"
    private const val MAX_INIT_ATTEMPTS = 8
    private const val INIT_BACKOFF_SLEEP_MS = 400L

    private val extractorInfo = ConcurrentHashMap<String, YoutubeSabrInfo>()

    fun putExtractorInfo(videoId: String, info: YoutubeSabrInfo) {
        extractorInfo[videoId] = info
    }

    fun getExtractorInfo(videoId: String): YoutubeSabrInfo? = extractorInfo[videoId]

    fun evict(videoId: String) {
        extractorInfo.remove(videoId)
    }

    @Throws(IOException::class)
    fun createSourceSpec(
        videoId: String,
        preferredVideoItag: Int,
        preferredAudioItag: Int,
        preferredAudioTrackId: String?,
        info: YoutubeSabrInfo,
        poToken: ByteArray?,
        localization: Localization
    ): SabrNgSourceSpec {
        val videoFormat = selectVideoFormat(info, preferredVideoItag)
            ?: throw IOException("No SABR video format for $videoId (itag=$preferredVideoItag)")
        val audioFormat = selectAudioFormat(info, preferredAudioItag, preferredAudioTrackId)
            ?: throw IOException("No SABR audio format for $videoId (itag=$preferredAudioItag)")
        val token = poToken ?: info.getPoToken() ?: ByteArray(0)
        val initByItag = fetchInitializationSegments(
            videoId, info, listOf(audioFormat, videoFormat), token
        )
        val audioInit = initByItag[audioFormat.getItag()]
            ?: throw IOException(
                "SABR did not return an audio init segment for $videoId (itag=${audioFormat.getItag()})"
            )
        val videoInit = initByItag[videoFormat.getItag()]
            ?: throw IOException(
                "SABR did not return a video init segment for $videoId (itag=${videoFormat.getItag()})"
            )
        return SabrNgSourceSpec(
            videoId, info, audioFormat, videoFormat, localization, audioInit, videoInit,
            poToken ?: info.getPoToken()
        )
    }

    /**
     * Drives a short SABR preparation transaction and returns the raw init-segment bytes per itag.
     * The init segment includes the fragment index (mp4 sidx / webm cues) that
     * [SabrNgSourceSpec] parses into a [YoutubeSabrFormatTimeline].
     */
    @Throws(IOException::class)
    private fun fetchInitializationSegments(
        videoId: String,
        info: YoutubeSabrInfo,
        formats: List<YoutubeSabrInfo.Format>,
        token: ByteArray
    ): Map<Int, ByteArray> {
        val session = YoutubeSabrSession(info)
        if (token.isNotEmpty()) {
            session.setPoToken(token)
        }
        val initByItag = HashMap<Int, ByteArray>()
        val neededItags = formats.mapTo(HashSet()) { it.getItag() }
        val consumer = SabrStreamingResponseReader.SegmentConsumer { segment ->
            val header = segment.getHeader()
            if (header.isInitSegment() && neededItags.contains(header.getItag()) &&
                !initByItag.containsKey(header.getItag())
            ) {
                initByItag[header.getItag()] = segment.getData()
            }
        }
        var attempts = 0
        while (initByItag.size < formats.size && attempts < MAX_INIT_ATTEMPTS) {
            attempts++
            val backoffMs = session.getBackoffRemainingMs()
            if (backoffMs > 0) {
                sleepQuietly(minOf(backoffMs, INIT_BACKOFF_SLEEP_MS))
                continue
            }
            val request = YoutubeSabrRequest.preparation(0, formats)
            try {
                session.requestOnce(request, consumer)
            } catch (e: IOException) {
                throw e
            } catch (e: ExtractionException) {
                throw IOException("SABR initialization request failed for $videoId", e)
            }
        }
        if (initByItag.size < formats.size) {
            Log.w(
                TAG,
                "SABR init incomplete for $videoId: got=${initByItag.keys} needed=$neededItags"
            )
        }
        return initByItag
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun selectVideoFormat(
        info: YoutubeSabrInfo,
        preferredItag: Int
    ): YoutubeSabrInfo.Format? {
        val videos = info.getFormats().filter { it.isVideo() }
        return videos.firstOrNull { it.getItag() == preferredItag }
            ?: videos.minByOrNull { if (it.getHeight() > 0) it.getHeight() else Int.MAX_VALUE }
    }

    private fun selectAudioFormat(
        info: YoutubeSabrInfo,
        preferredItag: Int,
        preferredAudioTrackId: String?
    ): YoutubeSabrInfo.Format? {
        val audios = info.getFormats().filter { it.isAudio() }
        if (audios.isEmpty()) {
            return null
        }
        if (preferredItag > 0) {
            audios.firstOrNull {
                it.getItag() == preferredItag &&
                    (preferredAudioTrackId.isNullOrEmpty() ||
                        preferredAudioTrackId == it.getAudioTrackId())
            }?.let { return it }
            audios.firstOrNull { it.getItag() == preferredItag }?.let { return it }
        }
        return audios.firstOrNull { it.isOriginalAudio() }
            ?: audios.maxByOrNull { it.getBitrate() }
    }
}
