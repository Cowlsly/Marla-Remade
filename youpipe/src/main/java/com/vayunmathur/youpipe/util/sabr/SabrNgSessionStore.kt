package com.vayunmathur.youpipe.util.sabr

import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequestHelper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Extractor→player handoff and source-spec factory for the session-based ([SabrNgSession]) SABR
 * bridge. The extractor publishes a [YoutubeSabrInfo] per videoId; the player later builds a
 * [SabrNgSourceSpec] (fetching each selected format's initialization/segment index) for
 * [SabrNgDashMediaSource].
 */
object SabrNgSessionStore {
    private const val INIT_TIMEOUT_MS = 8_000L

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
        val audioInit = YoutubeSabrRequestHelper.fetchInitializationData(
            audioFormat, token, INIT_TIMEOUT_MS
        )
        val videoInit = YoutubeSabrRequestHelper.fetchInitializationData(
            videoFormat, token, INIT_TIMEOUT_MS
        )
        return SabrNgSourceSpec(
            videoId, info, audioFormat, videoFormat, localization, audioInit, videoInit,
            poToken ?: info.getPoToken()
        )
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
