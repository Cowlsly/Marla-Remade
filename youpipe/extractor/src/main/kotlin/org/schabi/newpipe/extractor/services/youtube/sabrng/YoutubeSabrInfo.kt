package org.schabi.newpipe.extractor.services.youtube.sabrng

import org.schabi.newpipe.extractor.services.youtube.ItagItem
import java.io.Serializable
import java.util.Collections

/**
 * Immutable metadata bundle extracted from a SABR-only player response and consumed by
 * [YoutubeSabrSession] to drive the session-based SABR protocol.
 */
class YoutubeSabrInfo @JvmOverloads constructor(
    videoId: String,
    cpn: String,
    clientVersion: String,
    visitorData: String?,
    serverAbrStreamingUrl: String?,
    videoPlaybackUstreamerConfig: String?,
    formats: List<Format>,
    poToken: ByteArray? = null
) : Serializable {

    private val videoId: String = videoId
    private val cpn: String = cpn
    private val clientVersion: String = clientVersion
    private val visitorData: String? = visitorData
    private val serverAbrStreamingUrl: String? = serverAbrStreamingUrl
    private val videoPlaybackUstreamerConfig: String? = videoPlaybackUstreamerConfig
    private val formats: List<Format> = formats
    private val poToken: ByteArray? = poToken?.clone()

    fun getVideoId(): String = videoId
    fun getCpn(): String = cpn
    fun getClientVersion(): String = clientVersion
    fun getVisitorData(): String? = visitorData
    fun getServerAbrStreamingUrl(): String? = serverAbrStreamingUrl
    fun getVideoPlaybackUstreamerConfig(): String? = videoPlaybackUstreamerConfig
    fun getPoToken(): ByteArray? = poToken?.clone()

    fun getFormats(): List<Format> = Collections.unmodifiableList(formats)

    class Format private constructor(
        parsedFormat: ItagItem,
        private val lastModified: Long,
        private val xtags: String?,
        private val mimeType: String?,
        private val audioTrackId: String?,
        private val audioTrackDisplayName: String?,
        private val drc: Boolean,
        private val initializationUrl: String?,
        private val initRangeStart: Long,
        private val initRangeEnd: Long
    ) : Serializable {
        private val parsedFormat: ItagItem = ItagItem(parsedFormat)

        fun isAudio(): Boolean = mimeType != null && mimeType.startsWith("audio/")
        fun isVideo(): Boolean = mimeType != null && mimeType.startsWith("video/")
        fun getItag(): Int = parsedFormat.id
        fun toItagItem(): ItagItem = ItagItem(parsedFormat)
        fun getLastModified(): Long = lastModified
        fun getXtags(): String? = xtags
        fun getMimeType(): String? = mimeType
        fun getAudioTrackId(): String? = audioTrackId
        fun getAudioTrackDisplayName(): String? = audioTrackDisplayName
        fun isOriginalAudio(): Boolean = audioTrackDisplayName != null &&
            (audioTrackDisplayName.contains("original") || audioTrackDisplayName.contains("yokuqala"))
        fun isDrc(): Boolean = drc
        fun getWidth(): Int = parsedFormat.width
        fun getHeight(): Int = parsedFormat.height
        fun getBitrate(): Int = parsedFormat.bitrate
        fun getContentLength(): Long = parsedFormat.contentLength
        fun getApproxDurationMs(): Long = parsedFormat.approxDurationMs
        fun getInitializationUrl(): String? = initializationUrl
        fun getInitRangeStart(): Long = initRangeStart
        fun getInitRangeEnd(): Long = initRangeEnd

        companion object {
            private const val serialVersionUID = 1L

            @JvmStatic
            fun fromParsedFormat(
                parsedFormat: ItagItem,
                lastModified: Long,
                xtags: String?,
                mimeType: String?,
                audioTrackId: String?,
                audioTrackDisplayName: String?,
                drc: Boolean,
                initializationUrl: String?,
                initRangeStart: Long,
                initRangeEnd: Long
            ): Format = Format(
                parsedFormat, lastModified, xtags, mimeType, audioTrackId,
                audioTrackDisplayName, drc, initializationUrl, initRangeStart, initRangeEnd
            )
        }
    }

    companion object {
        private const val serialVersionUID = 3L
    }
}
