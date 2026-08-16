package org.schabi.newpipe.extractor.services.youtube

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.NewPipe.getDownloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_EMBEDDED_CLIENT_ID
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_EMBEDDED_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CONTENT_CHECK_OK
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CPN
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.RACY_CHECK_OK
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.VIDEO_ID
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateTParameter
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getOriginReferrerHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYouTubeHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareJsonBuilder
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.getObject
import java.io.IOException

object YoutubeStreamHelper {

    private const val PLAYER = "player"
    private const val SERVICE_INTEGRITY_DIMENSIONS = "serviceIntegrityDimensions"
    private const val PO_TOKEN = "poToken"
    private const val BASE_YT_DESKTOP_WATCH_URL = "https://www.youtube.com/watch?v="

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getWebMetadataPlayerResponse(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String
    ): JsonObject {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
        innertubeClientRequestInfo.clientInfo.clientVersion = getClientVersion()

        val headers = getYouTubeHeaders()

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                innertubeClientRequestInfo, localization, contentCountry, headers,
                YOUTUBEI_V1_URL, null, false
            )

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, null
        )

        addVideoIdCpnAndOkChecks(builder, videoId, null)

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER +
            "&\$fields=microformat,videoDetails.thumbnail.thumbnails,videoDetails.videoId"

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getWebEmbeddedPlayerResponse(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String,
        cpn: String,
        webEmbeddedPoTokenResult: PoTokenResult?,
        signatureTimestamp: Int
    ): JsonObject {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebEmbeddedPlayerClient()

        val headers = HashMap(getClientHeaders(WEB_EMBEDDED_CLIENT_ID, WEB_EMBEDDED_CLIENT_VERSION))
        headers.putAll(getOriginReferrerHeaders("https://www.youtube.com"))

        val embedUrl = BASE_YT_DESKTOP_WATCH_URL + videoId

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
            webEmbeddedPoTokenResult?.visitorData
                ?: YoutubeParsingHelper.getVisitorDataFromInnertube(
                    innertubeClientRequestInfo, localization, contentCountry, headers,
                    YOUTUBEI_V1_URL, embedUrl, false
                )

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, embedUrl
        )

        addVideoIdCpnAndOkChecks(builder, videoId, cpn)

        addPlaybackContext(builder, embedUrl, signatureTimestamp)

        if (webEmbeddedPoTokenResult != null) {
            addPoToken(builder, webEmbeddedPoTokenResult.playerRequestPoToken)
        }

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)
        val url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getAndroidPlayerResponse(
        contentCountry: ContentCountry,
        localization: Localization,
        videoId: String,
        cpn: String,
        androidPoTokenResult: PoTokenResult
    ): JsonObject {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofAndroidClient()
        innertubeClientRequestInfo.clientInfo.visitorData = androidPoTokenResult.visitorData

        val headers = getMobileClientHeaders(getAndroidUserAgent(localization))

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, null
        )

        addVideoIdCpnAndOkChecks(builder, videoId, cpn)

        addPoToken(builder, androidPoTokenResult.playerRequestPoToken)

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER +
            "&t=" + generateTParameter() + "&id=" + videoId

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getAndroidReelPlayerResponse(
        contentCountry: ContentCountry,
        localization: Localization,
        videoId: String,
        cpn: String
    ): JsonObject? {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofAndroidClient()

        val headers = getMobileClientHeaders(getAndroidUserAgent(localization))

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                innertubeClientRequestInfo, localization, contentCountry, headers,
                YOUTUBEI_V1_GAPIS_URL, null, false
            )

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, null
        )

        builder.`object`("playerRequest")
        addVideoIdCpnAndOkChecks(builder, videoId, cpn)
        builder.end()
            .value("disablePlayerResponse", false)

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val url = YOUTUBEI_V1_GAPIS_URL + "reel/reel_item_watch" + "?" +
            DISABLE_PRETTY_PRINT_PARAMETER + "&t=" + generateTParameter() + "&id=" + videoId +
            "&\$fields=playerResponse"

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        ).getObject("playerResponse")
    }

    /**
     * ANDROID_VR request headers, matching PipePipe's android_vr player call exactly.
     *
     * The standard www.youtube.com InnerTube player endpoint is used (NOT the gapis host, and
     * without the &t=/&id= params which the gapis PO-token flow uses). X-YouTube-Client-Name (28)
     * and X-YouTube-Client-Version identify the client.
     */
    @JvmStatic
    fun getAndroidVrHeaders(): Map<String, List<String>> = mapOf(
        "Content-Type" to listOf("application/json"),
        "User-Agent" to listOf(ClientsConstants.ANDROID_VR_USER_AGENT),
        "X-YouTube-Client-Name" to listOf(ClientsConstants.ANDROID_VR_CLIENT_ID),
        "X-YouTube-Client-Version" to listOf(ClientsConstants.ANDROID_VR_CLIENT_VERSION)
    )

    @JvmStatic
    fun getAndroidVrPlayerUrl(): String =
        YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER

    /**
     * Builds the ANDROID_VR /player request body byte-for-byte identical to PipePipe's
     * `fetchConfiguredJsonPlayer` for the signed-out path: a minimal context.client (no
     * request/user/thirdParty objects), a playbackContext carrying html5Preference +
     * signatureTimestamp, then cpn/videoId/contentCheckOk/racyCheckOk. On the signed-out
     * path there is no visitorData and no serviceIntegrityDimensions/poToken.
     *
     * The field order matches PipePipe exactly so the serialized JSON is identical.
     */
    @JvmStatic
    fun buildAndroidVrPlayerRequestBody(
        localization: Localization,
        contentCountry: ContentCountry,
        videoId: String,
        cpn: String,
        signatureTimestamp: Int
    ): JsonObject {
        val builder = YoutubeJsonBuilder()
        builder.`object`("context")
            .`object`("client")
            .value("utcOffsetMinutes", 0)
            .value("timeZone", "UTC")
            .value("hl", localization.getLocalizationCode())
            .value("gl", contentCountry.countryCode)
            .value("userAgent", ClientsConstants.ANDROID_VR_USER_AGENT)
            .value("clientName", ClientsConstants.ANDROID_VR_CLIENT_NAME)
            .value("clientVersion", ClientsConstants.ANDROID_VR_CLIENT_VERSION)
            .value("deviceMake", ClientsConstants.ANDROID_VR_DEVICE_MAKE)
            .value("deviceModel", ClientsConstants.ANDROID_VR_DEVICE_MODEL)
            .value("androidSdkVersion", ClientsConstants.ANDROID_VR_SDK_VERSION)
            .value("osName", ClientsConstants.ANDROID_VR_OS_NAME)
            .value("osVersion", ClientsConstants.ANDROID_VR_OS_VERSION)
            .end()
            .end()
            .`object`("playbackContext")
            .`object`("contentPlaybackContext")
            .value("html5Preference", "HTML5_PREF_WANTS")
            .value("signatureTimestamp", signatureTimestamp)
            .end()
            .end()
            .value(CPN, cpn)
            .value(VIDEO_ID, videoId)
            .value(CONTENT_CHECK_OK, true)
            .value(RACY_CHECK_OK, true)
        return builder.done()
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getAndroidVrPlayerResponse(
        contentCountry: ContentCountry,
        localization: Localization,
        videoId: String,
        cpn: String
    ): JsonObject {
        // ANDROID_VR returns direct stream URLs and does not require a PO Token or visitorData,
        // which is why it is far more reliable than the SABR (WEB) path right now.
        val headers = getAndroidVrHeaders()

        val body = buildAndroidVrPlayerRequestBody(
            localization, contentCountry, videoId, cpn,
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        ).toString().toByteArray(Charsets.UTF_8)

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(
                    getAndroidVrPlayerUrl(), headers, body, localization
                )
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getIosPlayerResponse(
        contentCountry: ContentCountry,
        localization: Localization,
        videoId: String,
        cpn: String,
        iosPoTokenResult: PoTokenResult?
    ): JsonObject {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofIosClient()

        val headers = getMobileClientHeaders(getIosUserAgent(localization))

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
            iosPoTokenResult?.visitorData
                ?: YoutubeParsingHelper.getVisitorDataFromInnertube(
                    innertubeClientRequestInfo, localization, contentCountry, headers,
                    YOUTUBEI_V1_URL, null, false
                )

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, null
        )

        addVideoIdCpnAndOkChecks(builder, videoId, cpn)

        if (iosPoTokenResult != null) {
            addPoToken(builder, iosPoTokenResult.playerRequestPoToken)
        }

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER +
            "&t=" + generateTParameter() + "&id=" + videoId

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getVisionOsPlayerResponse(
        contentCountry: ContentCountry,
        localization: Localization,
        videoId: String,
        cpn: String
    ): JsonObject {
        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofVisionOsClient()

        val headers = getMobileClientHeaders(getVisionOsUserAgent(localization))

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                innertubeClientRequestInfo, localization, contentCountry, headers,
                YOUTUBEI_V1_URL, null, false
            )

        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, null
        )

        addVideoIdCpnAndOkChecks(builder, videoId, cpn)

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER +
            "&t=" + generateTParameter() + "&id=" + videoId

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)
            )
        )
    }

    private fun addVideoIdCpnAndOkChecks(
        builder: YoutubeJsonBuilder,
        videoId: String,
        cpn: String?
    ) {
        builder.value(VIDEO_ID, videoId)

        if (cpn != null) {
            builder.value(CPN, cpn)
        }

        builder.value(CONTENT_CHECK_OK, true)
            .value(RACY_CHECK_OK, true)
    }

    private fun addPlaybackContext(
        builder: YoutubeJsonBuilder,
        referer: String,
        signatureTimestamp: Int
    ) {
        builder.`object`("playbackContext")
            .`object`("contentPlaybackContext")
            .value("signatureTimestamp", signatureTimestamp)
            .value("referer", referer)
            .end()
            .end()
    }

    private fun addPoToken(builder: YoutubeJsonBuilder, poToken: String) {
        builder.`object`(SERVICE_INTEGRITY_DIMENSIONS)
            .value(PO_TOKEN, poToken)
            .end()
    }

    private fun getMobileClientHeaders(userAgent: String): Map<String, List<String>> =
        mapOf(
            "User-Agent" to listOf(userAgent),
            "X-Goog-Api-Format-Version" to listOf("2")
        )
}
