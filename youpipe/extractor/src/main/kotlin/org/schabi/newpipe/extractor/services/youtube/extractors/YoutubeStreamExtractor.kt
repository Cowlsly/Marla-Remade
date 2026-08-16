package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.MultiInfoItemsCollector
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeDescriptionHelper.attributedDescriptionToHtml
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeMetaInfoHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CONTENT_CHECK_OK
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CPN
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.RACY_CHECK_OK
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.VIDEO_ID
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.fixThumbnailUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateContentPlaybackNonce
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrNgInfoBuilder
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.utils.ExtractorLogger
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.LocaleCompat
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubeStreamExtractor(
    service: StreamingService,
    linkHandler: LinkHandler
) : StreamExtractor(service, linkHandler) {

    companion object {
        private const val PREMIERED = "Premiered "
        private const val PREMIERED_ON = "Premiered on "
        private const val FORMATS = "formats"
        private const val ADAPTIVE_FORMATS = "adaptiveFormats"
        private const val STREAMING_DATA = "streamingData"
        private const val NEXT = "next"
        private const val SIGNATURE_CIPHER = "signatureCipher"
        private const val CIPHER = "cipher"
        private const val PLAYER_CAPTIONS_TRACKLIST_RENDERER = "playerCaptionsTracklistRenderer"
        private const val CAPTIONS = "captions"
        private const val PLAYABILITY_STATUS = "playabilityStatus"
        private const val THUMBNAIL = "thumbnail"
        private const val THUMBNAILS = "thumbnails"
        private const val VIDEO_DETAILS = "videoDetails"
        private const val TITLE = "title"

        @JvmField
        var poTokenProvider: PoTokenProvider? = null

        @JvmField
        var fetchIosClient: Boolean = false

        @JvmStatic
        fun setPoTokenProvider(provider: PoTokenProvider?) {
            poTokenProvider = provider
        }

        @JvmStatic
        fun setFetchIosClient(fetch: Boolean) {
            fetchIosClient = fetch
        }

        @JvmStatic
        @Throws(ParsingException::class)
        private fun checkPlayabilityStatus(playabilityStatus: JsonObject) {
            val status = playabilityStatus.getString("status")
            if (status == null || status.equals("ok", ignoreCase = true)) {
                return
            }
            val reason = playabilityStatus.getString("reason")
            if (reason != null) {
                if (status.equals("login_required", ignoreCase = true)) {
                    if (reason.contains("inappropriate for some users")) {
                        throw AgeRestrictedContentException(
                            "This age-restricted video cannot be watched anonymously"
                        )
                    }
                    if (reason.contains("private")) {
                        throw PrivateContentException("This video is private")
                    }
                    if (reason.contains("a bot")) {
                        throw SignInConfirmNotBotException(
                            "YouTube probably temporarily blocked anonymous watch access with this" +
                                " IP , got error $status: \"$reason\""
                        )
                    }
                }
                if (status.equals("unplayable", ignoreCase = true) ||
                    status.equals("error", ignoreCase = true)
                ) {
                    if (reason.contains("Music Premium")) {
                        throw YoutubeMusicPremiumContentException()
                    }
                    if (reason.contains("payment")) {
                        throw PaidContentException("This video is a paid video")
                    }
                    if (reason.contains("members")) {
                        throw PaidContentException(
                            "This video is only available for members of the channel of this video"
                        )
                    }
                    if (reason.contains("country")) {
                        throw GeographicRestrictionException(
                            "This video is not available in client's country."
                        )
                    }
                    if (reason.contains("closed") || reason.contains("terminated")) {
                        throw AccountTerminatedException(reason)
                    }
                }
            }
            throw ContentNotAvailableException("Got error $status: \"$reason\"")
        }

        private fun isPlayerResponseNotValid(
            playerResponse: JsonObject?,
            videoId: String
        ): Boolean {
            if (playerResponse == null) return true
            return videoId != playerResponse.getObject(VIDEO_DETAILS)?.getString("videoId")
        }

        @JvmStatic
        fun getManifestUrl(
            manifestType: String,
            streamingDataObjects: List<Pair<JsonObject?, String?>>,
            partToAppendToManifestUrlEnd: String
        ): String {
            val manifestKey = manifestType + "ManifestUrl"
            for (obj in streamingDataObjects) {
                val streamingData = obj.first
                if (streamingData != null) {
                    val manifestUrl = streamingData.getString(manifestKey)
                    if (manifestUrl.isNullOrEmpty()) continue
                    val second = obj.second
                    return if (second == null) {
                        "$manifestUrl?$partToAppendToManifestUrlEnd"
                    } else {
                        "$manifestUrl?pot=$second&$partToAppendToManifestUrlEnd"
                    }
                }
            }
            return ""
        }

        private fun parseLikeCountFromLikeButtonRenderer(
            topLevelButtons: JsonArray?
        ): Long {
            if (topLevelButtons == null) throw ParsingException("topLevelButtons null")
            var likesString: String? = null
            val likeToggleButtonRenderer = topLevelButtons.filterIsInstance<JsonObject>()
                .mapNotNull { button ->
                    button.getObject("segmentedLikeDislikeButtonRenderer")
                        ?.getObject("likeButton")
                        ?.getObject("toggleButtonRenderer")
                }
                .firstOrNull { !Utils.isNullOrEmpty(it) }

            if (likeToggleButtonRenderer != null) {
                likesString = likeToggleButtonRenderer.getObject("accessibilityData")
                    ?.getObject("accessibilityData")
                    ?.getString("label")

                if (likesString == null) {
                    likesString = likeToggleButtonRenderer.getObject("accessibility")
                        ?.getString("label")
                }

                if (likesString == null) {
                    likesString = likeToggleButtonRenderer.getObject("defaultText")
                        ?.getObject("accessibility")
                        ?.getObject("accessibilityData")
                        ?.getString("label")
                }

                if (likesString != null && likesString.lowercase().contains("no likes")) {
                    return 0
                }
            }

            if (likesString == null) {
                throw ParsingException("Could not get like count from accessibility data")
            }

            try {
                return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(likesString))
            } catch (e: NumberFormatException) {
                throw ParsingException("Could not parse \"$likesString\" as a long", e)
            }
        }

        private fun parseLikeCountFromLikeButtonViewModel(
            topLevelButtons: JsonArray?
        ): Long {
            if (topLevelButtons == null) throw ParsingException("topLevelButtons null")
            val likeToggleButtonViewModel = topLevelButtons.filterIsInstance<JsonObject>()
                .mapNotNull { button ->
                    button.getObject("segmentedLikeDislikeButtonViewModel")
                        ?.getObject("likeButtonViewModel")
                        ?.getObject("likeButtonViewModel")
                        ?.getObject("toggleButtonViewModel")
                        ?.getObject("toggleButtonViewModel")
                        ?.getObject("defaultButtonViewModel")
                        ?.getObject("buttonViewModel")
                }
                .firstOrNull { !Utils.isNullOrEmpty(it) }

            if (likeToggleButtonViewModel == null) {
                throw ParsingException("Could not find buttonViewModel object")
            }

            val accessibilityText = likeToggleButtonViewModel.getString("accessibilityText")
                ?: throw ParsingException("Could not find buttonViewModel's accessibilityText string")

            try {
                return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(accessibilityText))
            } catch (e: NumberFormatException) {
                throw ParsingException("Could not parse \"$accessibilityText\" as a long", e)
            }
        }
    }

    private var playerResponse: JsonObject? = null
    private var nextResponse: JsonObject? = null

    private var visionOsStreamingData: JsonObject? = null
    private var iosStreamingData: JsonObject? = null
    private var androidStreamingData: JsonObject? = null

    private var videoPrimaryInfoRenderer: JsonObject? = null
    private var videoSecondaryInfoRenderer: JsonObject? = null
    private var playerMicroFormatRenderer: JsonObject? = null
    private var playerCaptionsTracklistRenderer: JsonObject? = null
    private var thumbnailsArray: JsonArray? = null
    private var ageLimit: Int = -1
    private var streamType: StreamType? = null

    private var visionOsCpn: String? = null
    private var iosCpn: String? = null
    private var androidCpn: String? = null

    private var androidStreamingUrlsPoToken: String? = null
    private var iosStreamingUrlsPoToken: String? = null

    private var sabrStreamsBuilt: Boolean = false
    private val sabrAudioStreams: MutableList<AudioStream> = ArrayList()
    private val sabrVideoOnlyStreams: MutableList<VideoStream> = ArrayList()

    @Throws(ParsingException::class)
    override fun getName(): String {
        assertPageFetched()
        var title: String? = playerResponse?.getObject(VIDEO_DETAILS)?.getString(TITLE)

        if (Utils.isNullOrEmpty(title)) {
            title = getTextFromObject(getVideoPrimaryInfoRenderer().getObject(TITLE))
            if (Utils.isNullOrEmpty(title)) {
                throw ParsingException("Could not get name")
            }
        }
        return title
    }

    override fun getTextualUploadDate(): String? {
        var timestamp = playerMicroFormatRenderer?.getString("uploadDate", "") ?: ""
        if (timestamp.isEmpty()) {
            timestamp = playerMicroFormatRenderer?.getString("publishDate", "") ?: ""
        }
        if (timestamp.isNotEmpty()) return timestamp

        val liveDetails = playerMicroFormatRenderer?.getObject("liveBroadcastDetails")
        timestamp = liveDetails?.getString("endTimestamp", "") ?: ""
        if (timestamp.isEmpty()) {
            timestamp = liveDetails?.getString("startTimestamp", "") ?: ""
        }
        if (timestamp.isNotEmpty()) return timestamp
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return null
        }

        val textObject = getVideoPrimaryInfoRenderer().getObject("dateText")
        val rendererDateText = getTextFromObject(textObject)
        if (rendererDateText == null) {
            return null
        } else if (rendererDateText.startsWith(PREMIERED_ON)) {
            return rendererDateText.substring(PREMIERED_ON.length)
        } else if (rendererDateText.startsWith(PREMIERED)) {
            return rendererDateText.substring(PREMIERED.length)
        } else {
            return rendererDateText
        }
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        val dateText = getTextualUploadDate()
        try {
            return DateWrapper.fromOffsetDateTime(dateText)
        } catch (e: ParsingException) {
        }

        try {
            val localization = Localization("en")
            if (dateText != null) {
                TimeAgoPatternsManager.getTimeAgoParserFor(localization)
                    ?.let { return it.parse(dateText) }
            }
        } catch (e: ParsingException) {
        }

        val date = parseOptionalDate(dateText, "MMM dd, yyyy")
            ?: parseOptionalDate(dateText, "dd MMM yyyy")
            ?: throw ParsingException("Could not parse upload date \"$dateText\"")
        return DateWrapper(date.atStartOfDay(), true)
    }

    private fun parseOptionalDate(date: String?, pattern: String): LocalDate? {
        return try {
            LocalDate.parse(date, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        assertPageFetched()
        try {
            return getImagesFromThumbnailsArray(thumbnailsArray!!)
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails")
        }
    }

    @Throws(ParsingException::class)
    override fun getDescription(): Description {
        assertPageFetched()
        val videoSecondaryInfoRendererDescription = getTextFromObject(
            getVideoSecondaryInfoRenderer().getObject("description"), true
        )
        if (!Utils.isNullOrEmpty(videoSecondaryInfoRendererDescription)) {
            return Description(videoSecondaryInfoRendererDescription, Description.HTML)
        }

        val attributedDescription = attributedDescriptionToHtml(
            getVideoSecondaryInfoRenderer().getObject("attributedDescription")
        )
        if (!Utils.isNullOrEmpty(attributedDescription)) {
            return Description(attributedDescription, Description.HTML)
        }

        var description = playerResponse?.getObject(VIDEO_DETAILS)?.getString("shortDescription")
        if (description == null) {
            val descriptionObject = playerMicroFormatRenderer?.getObject("description")
            description = getTextFromObject(descriptionObject)
        }

        return Description(description, Description.PLAIN_TEXT)
    }

    @Throws(ParsingException::class)
    override fun getAgeLimit(): Int {
        if (ageLimit != -1) {
            return ageLimit
        }

        val ageRestricted = getVideoSecondaryInfoRenderer()
            .getObject("metadataRowContainer")
            ?.getObject("metadataRowContainerRenderer")
            ?.getArray("rows")
            ?.filterIsInstance<JsonObject>()
            ?.asSequence()
            ?.flatMap { metadataRow ->
                metadataRow.getObject("metadataRowRenderer")
                    ?.getArray("contents")
                    ?.filterIsInstance<JsonObject>() ?: emptyList()
            }
            ?.flatMap { content ->
                content.getArray("runs")?.filterIsInstance<JsonObject>() ?: emptyList()
            }
            ?.map { run -> run.getString("text", "") }
            ?.any { rowText -> rowText.contains("Age-restricted") } ?: false

        ageLimit = if (ageRestricted) 18 else NO_AGE_LIMIT
        return ageLimit
    }

    @Throws(ParsingException::class)
    override fun getLength(): Long {
        assertPageFetched()

        try {
            val duration = playerResponse?.getObject(VIDEO_DETAILS)?.getString("lengthSeconds")
            if (!duration.isNullOrEmpty()) return java.lang.Long.parseLong(duration)
        } catch (e: Exception) {
        }
        return getDurationFromFirstAdaptiveFormat(
            listOf(androidStreamingData, iosStreamingData)
        ).toLong()
    }

    @Throws(ParsingException::class)
    private fun getDurationFromFirstAdaptiveFormat(streamingDatas: List<JsonObject?>): Int {
        for (streamingData in streamingDatas) {
            if (streamingData == null) continue
            val adaptiveFormats = streamingData.getArray(ADAPTIVE_FORMATS) ?: continue
            if (adaptiveFormats.isEmpty()) continue
            val first = adaptiveFormats.getObject(0) ?: continue
            val durationMs = first.getString("approxDurationMs") ?: continue
            try {
                return Math.round(java.lang.Long.parseLong(durationMs) / 1000f)
            } catch (ignored: NumberFormatException) {
            }
        }

        throw ParsingException("Could not get duration")
    }

    @Throws(ParsingException::class)
    override fun getTimeStamp(): Long {
        val timestamp = getTimestampSeconds("((#|&|\\?)t=\\d*h?\\d*m?\\d+s?)")
        if (timestamp == -2L) {
            return 0
        }
        return timestamp
    }

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        var views = getTextFromObject(
            getVideoPrimaryInfoRenderer().getObject("viewCount")
                ?.getObject("videoViewCountRenderer")
                ?.getObject("viewCount")
        )

        if (Utils.isNullOrEmpty(views)) {
            views = playerResponse?.getObject(VIDEO_DETAILS)?.getString("viewCount")

            if (Utils.isNullOrEmpty(views)) {
                throw ParsingException("Could not get view count")
            }
        }

        if (views.lowercase().contains("no views")) {
            return 0
        }

        return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(views))
    }

    @Throws(ParsingException::class)
    override fun getLikeCount(): Long {
        assertPageFetched()

        if (playerResponse?.getObject(VIDEO_DETAILS)?.getBoolean("allowRatings") != true) {
            return -1L
        }

        val topLevelButtons = getVideoPrimaryInfoRenderer()
            .getObject("videoActions")
            ?.getObject("menuRenderer")
            ?.getArray("topLevelButtons")

        try {
            return parseLikeCountFromLikeButtonViewModel(topLevelButtons)
        } catch (ignored: ParsingException) {
        }

        try {
            return parseLikeCountFromLikeButtonRenderer(topLevelButtons)
        } catch (e: ParsingException) {
            throw ParsingException("Could not get like count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String {
        assertPageFetched()
        val uploaderId = playerResponse?.getObject(VIDEO_DETAILS)?.getString("channelId")
        if (!Utils.isNullOrEmpty(uploaderId)) {
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/$uploaderId")
        }

        throw ParsingException("Could not get uploader url")
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        assertPageFetched()
        val uploaderName = playerResponse?.getObject(VIDEO_DETAILS)?.getString("author")
        if (Utils.isNullOrEmpty(uploaderName)) {
            throw ParsingException("Could not get uploader name")
        }

        return uploaderName
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        val videoOwnerRenderer = getVideoSecondaryInfoRenderer()
            .getObject("owner")
            ?.getObject("videoOwnerRenderer")

        if (videoOwnerRenderer == null) return false

        if (videoOwnerRenderer.containsKey("badges")) {
            return YoutubeParsingHelper.isVerified(videoOwnerRenderer.getArray("badges"))
        }

        val channel = YoutubeParsingHelper.getFirstCollaborator(
            videoOwnerRenderer.getObject("navigationEndpoint")
        )
        if (channel == null) {
            return false
        }

        return YoutubeParsingHelper.hasArtistOrVerifiedIconBadgeAttachment(
            channel.getObject(TITLE)?.getArray("attachmentRuns") ?: JsonArray(emptyList())
        )
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> {
        assertPageFetched()
        val owner = getVideoSecondaryInfoRenderer().getObject("owner")
            ?.getObject("videoOwnerRenderer")
            ?: throw ParsingException("Could not get uploader avatars")

        val imageList: List<Image> = if (owner.containsKey("avatarStack")) {
            getImagesFromThumbnailsArray(
                owner.getObject("avatarStack")
                    ?.getObject("avatarStackViewModel")
                    ?.getArray("avatars")
                    ?.getObject(0)
                    ?.getObject("avatarViewModel")
                    ?.getObject("image")
                    ?.getArray("sources") ?: JsonArray(emptyList())
            )
        } else {
            getImagesFromThumbnailsArray(
                owner.getObject(THUMBNAIL)?.getArray(THUMBNAILS) ?: JsonArray(emptyList())
            )
        }

        if (imageList.isEmpty() && ageLimit == NO_AGE_LIMIT) {
            throw ParsingException("Could not get uploader avatars")
        }

        return imageList
    }

    @Throws(ParsingException::class)
    override fun getUploaderSubscriberCount(): Long {
        val videoOwnerRenderer = JsonUtils.getObject(
            videoSecondaryInfoRenderer!!,
            "owner.videoOwnerRenderer"
        )

        var subscriberCountText: String? = null
        if (videoOwnerRenderer.containsKey("subscriberCountText")) {
            subscriberCountText = getTextFromObject(videoOwnerRenderer.getObject("subscriberCountText"))
        } else {
            val content = YoutubeParsingHelper.getFirstCollaborator(
                videoOwnerRenderer.getObject("navigationEndpoint")
            )?.getObject("subtitle")?.getString("content") ?: ""
            val parts = content.split("•")
            subscriberCountText = if (parts.size > 1) parts[1] else ""
        }

        if (Utils.isNullOrEmpty(subscriberCountText)) {
            return UNKNOWN_SUBSCRIBER_COUNT
        }

        try {
            return Utils.mixedNumberWordToLong(subscriberCountText)
        } catch (e: NumberFormatException) {
            throw ParsingException("Could not get uploader subscriber count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getDashMpdUrl(): String {
        assertPageFetched()
        return getManifestUrl(
            "dash",
            listOf(Pair(androidStreamingData, androidStreamingUrlsPoToken)),
            "mpd_version=7"
        )
    }

    @Throws(ParsingException::class)
    override fun getHlsUrl(): String {
        assertPageFetched()
        return getManifestUrl(
            "hls",
            listOf(
                Pair(visionOsStreamingData, null),
                Pair(iosStreamingData, iosStreamingUrlsPoToken),
                Pair(androidStreamingData, androidStreamingUrlsPoToken)
            ),
            ""
        )
    }

    @Throws(ExtractionException::class)
    override fun getAudioStreams(): List<AudioStream> {
        assertPageFetched()
        val streams = getItags(
            ADAPTIVE_FORMATS, ItagItem.ItagType.AUDIO,
            getAudioStreamBuilderHelper(), "audio"
        )
        // SABR is built alongside the VISIONOS direct streams (matching PipePipe's
        // re-enabled SABR path); the app merges/dedups progressive + SABR downstream.
        buildSabrStreamsIfNeeded()
        streams.addAll(sabrAudioStreams)
        return streams
    }

    @Throws(ExtractionException::class)
    override fun getVideoStreams(): List<VideoStream> {
        assertPageFetched()
        return getItags(
            FORMATS, ItagItem.ItagType.VIDEO,
            getVideoStreamBuilderHelper(false), "video"
        )
    }

    @Throws(ExtractionException::class)
    override fun getVideoOnlyStreams(): List<VideoStream> {
        assertPageFetched()
        val streams = getItags(
            ADAPTIVE_FORMATS, ItagItem.ItagType.VIDEO_ONLY,
            getVideoStreamBuilderHelper(true), "video-only"
        )
        // SABR is built alongside the VISIONOS direct streams (matching PipePipe's
        // re-enabled SABR path); the app merges/dedups progressive + SABR downstream.
        buildSabrStreamsIfNeeded()
        streams.addAll(sabrVideoOnlyStreams)
        return streams
    }

    @Throws(ParsingException::class)
    override fun getSubtitlesDefault(): List<SubtitlesStream> {
        return getSubtitles(MediaFormat.TTML)
    }

    @Throws(ParsingException::class)
    override fun getSubtitles(format: MediaFormat): List<SubtitlesStream> {
        assertPageFetched()

        val subtitlesToReturn = ArrayList<SubtitlesStream>()
        val captionsArray = playerCaptionsTracklistRenderer?.getArray("captionTracks")
            ?: JsonArray(emptyList())

        for (i in 0 until captionsArray.size) {
            val obj = captionsArray.getObject(i) ?: continue
            val languageCode = obj.getString("languageCode")
            val baseUrl = obj.getString("baseUrl")
            val vssId = obj.getString("vssId")

            if (languageCode != null && baseUrl != null && vssId != null) {
                val isAutoGenerated = vssId.startsWith("a.")
                val cleanUrl = baseUrl
                    .replace(Regex("&fmt=[^&]*"), "")
                    .replace(Regex("&tlang=[^&]*"), "")

                subtitlesToReturn.add(
                    SubtitlesStream.Builder()
                        .setContent(cleanUrl + "&fmt=" + format.getSuffix(), true)
                        .setMediaFormat(format)
                        .setLanguageCode(languageCode)
                        .setAutoGenerated(isAutoGenerated)
                        .build()
                )
            }
        }

        return subtitlesToReturn
    }

    @Throws(ParsingException::class)
    override fun getStreamType(): StreamType {
        assertPageFetched()
        return streamType!!
    }

    private fun setStreamType() {
        val playability = playerResponse?.getObject(PLAYABILITY_STATUS)
        streamType = when {
            playability?.containsKey("liveStreamability") == true -> StreamType.LIVE_STREAM
            playerResponse?.getObject(VIDEO_DETAILS)?.getBoolean("isPostLiveDvr", false) == true ->
                StreamType.POST_LIVE_STREAM
            else -> StreamType.VIDEO_STREAM
        }
    }

    @Throws(ExtractionException::class)
    override fun getRelatedItems(): MultiInfoItemsCollector? {
        assertPageFetched()

        if (getAgeLimit() != NO_AGE_LIMIT) {
            return null
        }

        try {
            val collector = MultiInfoItemsCollector(getServiceId())

            val results = nextResponse
                ?.getObject("contents")
                ?.getObject("twoColumnWatchNextResults")
                ?.getObject("secondaryResults")
                ?.getObject("secondaryResults")
                ?.getArray("results") ?: JsonArray(emptyList())

            val timeAgoParser: TimeAgoParser = getTimeAgoParser()
            results.filterIsInstance<JsonObject>()
                .mapNotNull { result ->
                    when {
                        result.containsKey("compactVideoRenderer") -> {
                            YoutubeStreamInfoItemExtractor(
                                result.getObject("compactVideoRenderer").orEmptyObject(), timeAgoParser
                            )
                        }
                        result.containsKey("compactRadioRenderer") -> {
                            YoutubeMixOrPlaylistInfoItemExtractor(
                                result.getObject("compactRadioRenderer").orEmptyObject()
                            )
                        }
                        result.containsKey("compactPlaylistRenderer") -> {
                            YoutubeMixOrPlaylistInfoItemExtractor(
                                result.getObject("compactPlaylistRenderer").orEmptyObject()
                            )
                        }
                        result.containsKey("lockupViewModel") -> {
                            val lockupViewModel = result.getObject("lockupViewModel").orEmptyObject()
                            val contentType = lockupViewModel.getString("contentType")
                            when (contentType) {
                                "LOCKUP_CONTENT_TYPE_PLAYLIST",
                                "LOCKUP_CONTENT_TYPE_PODCAST" ->
                                    YoutubeMixOrPlaylistLockupInfoItemExtractor(lockupViewModel)
                                "LOCKUP_CONTENT_TYPE_VIDEO" ->
                                    YoutubeStreamInfoItemLockupExtractor(lockupViewModel, timeAgoParser)
                                else -> null
                            }
                        }
                        else -> null
                    }
                }
                .forEach { collector.commit(it) }

            return collector
        } catch (e: Exception) {
            throw ParsingException("Could not get related videos", e)
        }
    }

    override fun getErrorMessage(): String? {
        return try {
            getTextFromObject(
                playerResponse?.getObject(PLAYABILITY_STATUS)
                    ?.getObject("errorScreen")
                    ?.getObject("playerErrorMessageRenderer")
                    ?.getObject("reason")
            )
        } catch (e: NullPointerException) {
            null
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val videoId = getId()

        val localization = getExtractorLocalization()
        val contentCountry = getExtractorContentCountry()

        val poTokenProviderInstance = poTokenProvider
        val noPoTokenProviderSet = poTokenProviderInstance == null

        // Default anonymous client = VISIONOS, matching PipePipe's current default
        // (NewPipe.youtubePlayerClient = "visionos"; PipePipe commit 3b2aa5e "default
        // anonymous YouTube client to VisionOS", which supersedes the earlier android_vr
        // default). VISIONOS sends a valid visitorData, so YouTube does not bot-flag it.
        // The anonymous ANDROID_VR request has no visitorData and is now rejected with
        // SignInConfirmNotBotException (LOGIN_REQUIRED), which is why android_vr must NOT
        // be the primary/gating client.
        fetchVisionOsClient(localization, contentCountry, videoId)

        setStreamType()

        if (fetchIosClient) {
            val iosPoTokenResult = if (noPoTokenProviderSet) null
            else poTokenProviderInstance.getIosClientPoToken(videoId)
            fetchIosClient(localization, contentCountry, videoId, iosPoTokenResult)
        }

        fetchWebClientMetadataAndSetThumbnails(localization, contentCountry, videoId)

        val nextBody = prepareDesktopJsonBuilder(localization, contentCountry)
            .value(VIDEO_ID, videoId)
            .value(CONTENT_CHECK_OK, true)
            .value(RACY_CHECK_OK, true)
            .done().toString()
            .toByteArray(Charsets.UTF_8)
        nextResponse = getJsonPostResponse(NEXT, nextBody, localization)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchVisionOsClient(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String
    ) {
        visionOsCpn = generateContentPlaybackNonce()

        playerResponse = YoutubeStreamHelper.getVisionOsPlayerResponse(
            contentCountry, localization, videoId, visionOsCpn!!
        )

        checkPlayabilityStatus(playerResponse!!.getObject(PLAYABILITY_STATUS).orEmptyObject())
        if (isPlayerResponseNotValid(playerResponse, videoId)) {
            throw ExtractionException("VISIONOS player response is not valid")
        }

        visionOsStreamingData = playerResponse?.getObject(STREAMING_DATA)

        playerCaptionsTracklistRenderer = playerResponse?.getObject(CAPTIONS)
            ?.getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER)
    }

    private fun fetchIosClient(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String,
        iosPoTokenResult: PoTokenResult?
    ) {
        try {
            iosCpn = generateContentPlaybackNonce()

            val iosPlayerResponse = YoutubeStreamHelper.getIosPlayerResponse(
                contentCountry, localization, videoId, iosCpn!!, iosPoTokenResult
            )

            if (!isPlayerResponseNotValid(iosPlayerResponse, videoId)) {
                iosStreamingData = iosPlayerResponse.getObject(STREAMING_DATA)

                if (Utils.isNullOrEmpty(playerCaptionsTracklistRenderer)) {
                    playerCaptionsTracklistRenderer = iosPlayerResponse.getObject(CAPTIONS)
                        ?.getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER)
                }

                if (iosPoTokenResult != null) {
                    iosStreamingUrlsPoToken = iosPoTokenResult.streamingDataPoToken
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun fetchWebClientMetadataAndSetThumbnails(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String
    ) {
        try {
            val webPlayerResponse = YoutubeStreamHelper.getWebMetadataPlayerResponse(
                localization, contentCountry, videoId
            )

            if (!isPlayerResponseNotValid(webPlayerResponse, videoId)) {
                playerMicroFormatRenderer = webPlayerResponse.getObject("microformat")
                    ?.getObject("playerMicroformatRenderer")

                val thumbnailWebJsonObj = webPlayerResponse.getObject(VIDEO_DETAILS)
                    ?.getObject(THUMBNAIL)
                if (thumbnailWebJsonObj?.containsKey(THUMBNAILS) == true) {
                    thumbnailsArray = thumbnailWebJsonObj.getArray(THUMBNAILS)
                } else {
                    thumbnailsArray = playerResponse?.getObject(VIDEO_DETAILS)
                        ?.getObject(THUMBNAIL)
                        ?.getArray(THUMBNAILS)
                }
            }
        } catch (e: Exception) {
            playerMicroFormatRenderer = JsonObject(emptyMap())
            thumbnailsArray = playerResponse?.getObject(VIDEO_DETAILS)
                ?.getObject(THUMBNAIL)
                ?.getArray(THUMBNAILS)
        }
    }

    private fun getVideoPrimaryInfoRenderer(): JsonObject {
        if (videoPrimaryInfoRenderer != null) {
            return videoPrimaryInfoRenderer!!
        }

        videoPrimaryInfoRenderer = getVideoInfoRenderer("videoPrimaryInfoRenderer")
        return videoPrimaryInfoRenderer!!
    }

    private fun getVideoSecondaryInfoRenderer(): JsonObject {
        if (videoSecondaryInfoRenderer != null) {
            return videoSecondaryInfoRenderer!!
        }

        videoSecondaryInfoRenderer = getVideoInfoRenderer("videoSecondaryInfoRenderer")
        return videoSecondaryInfoRenderer!!
    }

    private fun getVideoInfoRenderer(videoRendererName: String): JsonObject {
        return nextResponse?.getObject("contents")
            ?.getObject("twoColumnWatchNextResults")
            ?.getObject("results")
            ?.getObject("results")
            ?.getArray("contents")
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.containsKey(videoRendererName) }
            ?.getObject(videoRendererName) ?: JsonObject(emptyMap())
    }

    private fun buildSabrStreamsIfNeeded() {
        if (sabrStreamsBuilt) {
            return
        }
        sabrStreamsBuilt = true
        val videoId: String = try {
            getId()
        } catch (e: Exception) {
            return
        }
        val sabrInfo: YoutubeSabrInfo = try {
            YoutubeSabrNgInfoBuilder.fetchSabrInfo(
                videoId,
                getExtractorLocalization(),
                getExtractorContentCountry()
            )
        } catch (e: Exception) {
            ExtractorLogger.d("YoutubeSabr", "SABR fetch failed for {}: {}", videoId, e)
            return
        }
        val actualFormats = sabrInfo.getFormats()
        if (actualFormats.isEmpty()) {
            ExtractorLogger.d("YoutubeSabr", "SABR fetch returned no info/formats for {}", videoId)
        }
        val serverAbrStreamingUrl = sabrInfo.getServerAbrStreamingUrl() ?: ""
        var av1Count = 0
        for (format in actualFormats) {
            try {
                val itagItem = format.toItagItem()
                val idStr = format.getItag().toString()

                if (format.isAudio()) {
                    val builder = AudioStream.Builder()
                        .setContent(serverAbrStreamingUrl, false)
                        .setMediaFormat(itagItem.mediaFormat)
                        .setAverageBitrate(format.getBitrate())
                        .setItagItem(itagItem)
                        .setDeliveryMethod(DeliveryMethod.SABR)
                        .setDeliveryMethodInfo(sabrInfo)
                    var streamId = idStr
                    val trackId = format.getAudioTrackId()
                    if (!trackId.isNullOrEmpty()) {
                        val langPart = trackId.split(".")[0]
                        val displayName = format.getAudioTrackDisplayName()
                        builder.setAudioTrackId(trackId)
                            .setAudioTrackName(displayName ?: langPart)
                            .setAudioLocale(Locale(langPart.split("-")[0]))
                        streamId = "$idStr-$trackId"
                    }
                    val audioStreamId = streamId
                    val stream = builder.setId(audioStreamId).build()
                    if (sabrAudioStreams.none { audioStreamId == it.getId() }) {
                        sabrAudioStreams.add(stream)
                    }
                } else if (format.isVideo()) {
                    val codec = itagItem.codec
                    if (codec != null && codec.contains("av01")) {
                        av1Count++
                    }
                    val resolution = if (format.getHeight() > 0) "${format.getHeight()}p" else ""
                    val stream = VideoStream.Builder()
                        .setId(idStr)
                        .setContent(serverAbrStreamingUrl, false)
                        .setMediaFormat(itagItem.mediaFormat)
                        .setIsVideoOnly(true)
                        .setItagItem(itagItem)
                        .setResolution(resolution)
                        .setDeliveryMethod(DeliveryMethod.SABR)
                        .setDeliveryMethodInfo(sabrInfo)
                        .build()
                    if (sabrVideoOnlyStreams.none { idStr == it.getId() }) {
                        sabrVideoOnlyStreams.add(stream)
                    }
                }
            } catch (e: Exception) {
            }
        }
        ExtractorLogger.d(
            "YoutubeSabr", "SABR built video={} audio={} (av1={}) for {}",
            sabrVideoOnlyStreams.size, sabrAudioStreams.size, av1Count, videoId
        )
        sabrAudioStreams.sortByDescending { it.getAverageBitrate() }
    }

    private fun <T : Stream> getItags(
        streamingDataKey: String,
        itagTypeWanted: ItagItem.ItagType,
        streamBuilderHelper: (ItagInfo) -> T,
        streamTypeExceptionMessage: String
    ): MutableList<T> {
        try {
            val videoId = getId()
            val streamList = mutableListOf<T>()

            val pairs = listOf(
                Pair(androidStreamingData, Pair(androidCpn, androidStreamingUrlsPoToken)),
                Pair(visionOsStreamingData, Pair(visionOsCpn, null as String?)),
                Pair(iosStreamingData, Pair(iosCpn, iosStreamingUrlsPoToken))
            )

            for (pair in pairs) {
                val streamingData = pair.first
                val second = pair.second
                val cpn = second.first
                val poToken = second.second
                if (streamingData == null || cpn == null) continue
                getStreamsFromStreamingDataKey(
                    videoId, streamingData, streamingDataKey,
                    itagTypeWanted, cpn, poToken
                ).forEach { itagInfo ->
                    val stream = streamBuilderHelper(itagInfo)
                    if (!Stream.containSimilarStream(stream, streamList)) {
                        streamList.add(stream)
                    }
                }
            }

            return streamList
        } catch (e: Exception) {
            throw ParsingException("Could not get $streamTypeExceptionMessage streams", e)
        }
    }

    private fun getAudioStreamBuilderHelper(): (ItagInfo) -> AudioStream {
        return { itagInfo ->
            val itagItem = itagInfo.getItagItem()
            val builder = AudioStream.Builder()
                .setId(itagItem.id.toString())
                .setContent(itagInfo.content, itagInfo.getIsUrl())
                .setMediaFormat(itagItem.mediaFormat)
                .setAverageBitrate(itagItem.averageBitrate)
                .setAudioTrackId(itagItem.audioTrackId)
                .setAudioTrackName(itagItem.audioTrackName)
                .setAudioLocale(itagItem.audioLocale)
                .setAudioTrackType(itagItem.audioTrackType)
                .setItagItem(itagItem)

            if (streamType == StreamType.LIVE_STREAM ||
                streamType == StreamType.POST_LIVE_STREAM ||
                !itagInfo.getIsUrl()
            ) {
                builder.setDeliveryMethod(DeliveryMethod.DASH)
            }

            builder.build()
        }
    }

    private fun getVideoStreamBuilderHelper(
        areStreamsVideoOnly: Boolean
    ): (ItagInfo) -> VideoStream {
        return { itagInfo ->
            val itagItem = itagInfo.getItagItem()
            val builder = VideoStream.Builder()
                .setId(itagItem.id.toString())
                .setContent(itagInfo.content, itagInfo.getIsUrl())
                .setMediaFormat(itagItem.mediaFormat)
                .setIsVideoOnly(areStreamsVideoOnly)
                .setItagItem(itagItem)

            val resolutionString = itagItem.resolutionString
            builder.setResolution(resolutionString ?: "")

            if (streamType != StreamType.VIDEO_STREAM || !itagInfo.getIsUrl()) {
                builder.setDeliveryMethod(DeliveryMethod.DASH)
            }

            builder.build()
        }
    }

    private fun getStreamsFromStreamingDataKey(
        videoId: String,
        streamingData: JsonObject,
        streamingDataKey: String,
        itagTypeWanted: ItagItem.ItagType,
        contentPlaybackNonce: String,
        poToken: String?
    ): Sequence<ItagInfo> {
        if (!streamingData.containsKey(streamingDataKey)) {
            return emptySequence()
        }

        val array = streamingData.getArray(streamingDataKey) ?: return emptySequence()
        return array.filterIsInstance<JsonObject>()
            .asSequence()
            .mapNotNull { formatData ->
                try {
                    val itagInt = formatData.getInt("itag") ?: return@mapNotNull null
                    val itagItem = ItagItem.getItag(itagInt)
                    if (itagItem.itagType == itagTypeWanted) {
                        buildAndAddItagInfoToList(
                            videoId, formatData, itagItem,
                            itagItem.itagType, contentPlaybackNonce, poToken
                        )
                    } else null
                } catch (ignored: ExtractionException) {
                    null
                }
            }
    }

    @Throws(ExtractionException::class)
    private fun buildAndAddItagInfoToList(
        videoId: String,
        formatData: JsonObject,
        itagItem: ItagItem,
        itagType: ItagItem.ItagType,
        contentPlaybackNonce: String,
        poToken: String?
    ): ItagInfo? {
        var streamUrl: String? = null
        if (formatData.containsKey("url")) {
            streamUrl = formatData.getString("url")
        } else {
            var cipherString = formatData.getString(CIPHER)
            if (cipherString == null) {
                cipherString = formatData.getString(SIGNATURE_CIPHER)
            }

            if (Utils.isNullOrEmpty(cipherString)) {
                return null
            }

            val cipher = Parser.compatParseMap(cipherString)
            val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                videoId, cipher.getOrDefault("s", "")
            )
            streamUrl = cipher["url"] + "&" + cipher["sp"] + "=" + signature
        }

        if (streamUrl == null) return null

        streamUrl = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
            videoId, streamUrl
        )

        streamUrl += "&$CPN=$contentPlaybackNonce"

        if (poToken != null) {
            streamUrl += "&pot=$poToken"
        }

        val initRange = formatData.getObject("initRange")
        val indexRange = formatData.getObject("indexRange")
        val mimeType = formatData.getString("mimeType", "")
        val codec = if (mimeType.contains("codecs")) {
            val parts = mimeType.split("\"")
            if (parts.size > 1) parts[1] else ""
        } else ""

        itagItem.bitrate = formatData.getInt("bitrate") ?: 0
        itagItem.width = formatData.getInt("width") ?: 0
        itagItem.height = formatData.getInt("height") ?: 0
        itagItem.initStart =
            initRange?.getString("start", "-1")?.toIntOrNull() ?: -1
        itagItem.initEnd =
            initRange?.getString("end", "-1")?.toIntOrNull() ?: -1
        itagItem.indexStart =
            indexRange?.getString("start", "-1")?.toIntOrNull() ?: -1
        itagItem.indexEnd =
            indexRange?.getString("end", "-1")?.toIntOrNull() ?: -1
        itagItem.quality = formatData.getString("quality")
        itagItem.codec = codec
        itagItem.isDrc = formatData.getBoolean("isDrc", false)
        itagItem.lastModified =
            formatData.getString("lastModified", "-1").toLongOrNull() ?: -1
        itagItem.xtags = formatData.getString("xtags")

        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.POST_LIVE_STREAM) {
            itagItem.targetDurationSec = formatData.getInt("targetDurationSec") ?: -1
        }

        if (itagType == ItagItem.ItagType.VIDEO || itagType == ItagItem.ItagType.VIDEO_ONLY) {
            itagItem.fps = formatData.getInt("fps") ?: -1
        } else if (itagType == ItagItem.ItagType.AUDIO) {
            val audioSampleRateStr = formatData.getString("audioSampleRate") ?: "0"
            itagItem.sampleRate = audioSampleRateStr.toIntOrNull() ?: 0
            itagItem.audioChannels =
                formatData.getInt("audioChannels", 2)

            val audioTrackId = formatData.getObject("audioTrack")?.getString("id")
            if (!Utils.isNullOrEmpty(audioTrackId)) {
                itagItem.audioTrackId = audioTrackId
                val dot = audioTrackId.indexOf(".")
                if (dot != -1) {
                    LocaleCompat.forLanguageTag(audioTrackId.substring(0, dot))
                        ?.let { locale -> itagItem.audioLocale = locale }
                }
                itagItem.audioTrackType =
                    YoutubeParsingHelper.extractAudioTrackType(itagItem.xtags)
            }

            itagItem.audioTrackName =
                formatData.getObject("audioTrack")?.getString("displayName")
        }

        itagItem.contentLength =
            formatData.getString("contentLength", ItagItem.CONTENT_LENGTH_UNKNOWN.toString())
                .toLongOrNull() ?: ItagItem.CONTENT_LENGTH_UNKNOWN
        itagItem.approxDurationMs =
            formatData.getString("approxDurationMs", ItagItem.APPROX_DURATION_MS_UNKNOWN.toString())
                .toLongOrNull() ?: ItagItem.APPROX_DURATION_MS_UNKNOWN

        val itagInfo = ItagInfo(streamUrl, itagItem)

        if (streamType == StreamType.VIDEO_STREAM) {
            itagInfo.setIsUrl(
                !formatData.getString("type", "")
                    .equals("FORMAT_STREAM_TYPE_OTF", ignoreCase = true)
            )
        } else {
            itagInfo.setIsUrl(streamType != StreamType.POST_LIVE_STREAM)
        }

        return itagInfo
    }

    @Throws(ExtractionException::class)
    override fun getFrames(): List<Frameset> {
        try {
            val storyboards = playerResponse?.getObject("storyboards")
            val storyboardsRenderer = storyboards?.let { sb ->
                if (sb.containsKey("playerLiveStoryboardSpecRenderer")) {
                    sb.getObject("playerLiveStoryboardSpecRenderer")
                } else {
                    sb.getObject("playerStoryboardSpecRenderer")
                }
            }

            if (storyboardsRenderer == null) {
                return emptyList()
            }

            val storyboardsRendererSpec = storyboardsRenderer.getString("spec")
                ?: return emptyList()

            val spec = storyboardsRendererSpec.split("|")
            val url = spec[0]
            val result = ArrayList<Frameset>(spec.size - 1)

            for (i in 1 until spec.size) {
                val parts = spec[i].split("#")
                if (parts.size != 8 || parts[5].toIntOrNull() == 0) {
                    continue
                }
                val totalCount = parts[2].toInt()
                val framesPerPageX = parts[3].toInt()
                val framesPerPageY = parts[4].toInt()
                val baseUrl = url.replace("\$L", (i - 1).toString())
                    .replace("\$N", parts[6]) + "&sigh=" + parts[7]
                val urls: List<String>
                if (baseUrl.contains("\$M")) {
                    val totalPages = Math.ceil(totalCount / (framesPerPageX * framesPerPageY).toDouble()).toInt()
                    urls = ArrayList(totalPages)
                    for (j in 0 until totalPages) {
                        urls.add(baseUrl.replace("\$M", j.toString()))
                    }
                } else {
                    urls = listOf(baseUrl)
                }
                result.add(
                    Frameset(
                        urls,
                        parts[0].toInt(),
                        parts[1].toInt(),
                        totalCount,
                        parts[5].toInt(),
                        framesPerPageX,
                        framesPerPageY
                    )
                )
            }
            return result
        } catch (e: Exception) {
            throw ExtractionException("Could not get frames", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getPrivacy(): Privacy {
        val isUnlisted = playerMicroFormatRenderer?.getBoolean("isUnlisted") ?: false
        val badges = getVideoPrimaryInfoRenderer().getArray("badges")
        val hasUnlistedBadge = badges?.filterIsInstance<JsonObject>()
            ?.any { badge ->
                "PRIVACY_UNLISTED" == badge.getObject("metadataBadgeRenderer")
                    ?.getObject("icon")
                    ?.getString("iconType")
            } ?: false
        return if (isUnlisted || hasUnlistedBadge) Privacy.UNLISTED else Privacy.PUBLIC
    }

    @Throws(ParsingException::class)
    override fun getCategory(): String {
        return playerMicroFormatRenderer?.getString("category", "") ?: ""
    }

    @Throws(ParsingException::class)
    override fun getLicence(): String {
        val metadataRowRenderer = getVideoSecondaryInfoRenderer()
            .getObject("metadataRowContainer")
            ?.getObject("metadataRowContainerRenderer")
            ?.getArray("rows")
            ?.getObject(0)
            ?.getObject("metadataRowRenderer")
            ?: return "YouTube licence"

        val contents = metadataRowRenderer.getArray("contents")
        val license = contents?.getObject(0)?.let { getTextFromObject(it) }
        return if (license != null && "Licence" == getTextFromObject(metadataRowRenderer.getObject(TITLE))) {
            license
        } else "YouTube licence"
    }

    override fun getLanguageInfo(): Locale? = null

    @Throws(ParsingException::class)
    override fun getTags(): List<String> {
        return JsonUtils.getStringListFromJsonArray(
            playerResponse?.getObject(VIDEO_DETAILS)?.getArray("keywords")
                ?: JsonArray(emptyList())
        )
    }

    @Throws(ParsingException::class)
    override fun getStreamSegments(): List<StreamSegment> {
        val engagementPanels = nextResponse?.getArray("engagementPanels")
            ?: return emptyList()

        val segmentsArray = engagementPanels.filterIsInstance<JsonObject>()
            .firstOrNull { panel ->
                "engagement-panel-macro-markers-description-chapters" ==
                    panel.getObject("engagementPanelSectionListRenderer")
                        ?.getString("panelIdentifier")
            }
            ?.getObject("engagementPanelSectionListRenderer")
            ?.getObject("content")
            ?.getObject("macroMarkersListRenderer")
            ?.getArray("contents")

        if (segmentsArray == null) {
            return emptyList()
        }

        val duration = getLength()
        val segments = mutableListOf<StreamSegment>()
        val iterator = segmentsArray.filterIsInstance<JsonObject>()
            .mapNotNull { it.getObject("macroMarkersListItemRenderer") }
            .iterator()

        while (iterator.hasNext()) {
            val segmentJson = iterator.next()
            val startTimeSeconds = segmentJson.getObject("onTap")
                ?.getObject("watchEndpoint")
                ?.getInt("startTimeSeconds", -1) ?: -1

            if (startTimeSeconds == -1) {
                throw ParsingException("Could not get stream segment start time.")
            }
            if (startTimeSeconds > duration) break

            val title = getTextFromObject(segmentJson.getObject(TITLE))
            if (Utils.isNullOrEmpty(title)) {
                throw ParsingException("Could not get stream segment title.")
            }

            val segment = StreamSegment(title, startTimeSeconds)
            segment.url = getUrl() + "?t=" + startTimeSeconds
            if (segmentJson.containsKey(THUMBNAIL)) {
                val previewsArray = segmentJson.getObject(THUMBNAIL)
                    ?.getArray(THUMBNAILS)
                if (previewsArray != null && !previewsArray.isEmpty()) {
                    val previewUrl = previewsArray.getObject(previewsArray.size - 1)
                        ?.getString("url")
                    if (previewUrl != null) {
                        segment.setPreviewUrl(fixThumbnailUrl(previewUrl))
                    }
                }
            }
            segments.add(segment)
        }
        return segments
    }

    @Throws(ParsingException::class)
    override fun getMetaInfo(): List<MetaInfo> {
        return YoutubeMetaInfoHelper.getMetaInfo(
            nextResponse
                ?.getObject("contents")
                ?.getObject("twoColumnWatchNextResults")
                ?.getObject("results")
                ?.getObject("results")
                ?.getArray("contents") ?: JsonArray(emptyList())
        )
    }
}
