package org.schabi.newpipe.extractor.services.youtube

import com.google.protobuf.InvalidProtocolBufferException
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.Base64
import java.util.Random
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.ANDROID_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.DESKTOP_CLIENT_PLATFORM
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.IOS_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.IOS_DEVICE_MODEL
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.IOS_USER_AGENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.VISIONOS_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.VISIONOS_DEVICE_MODEL
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.VISIONOS_USER_AGENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_CLIENT_ID
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_CLIENT_NAME
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_HARDCODED_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_REMIX_CLIENT_ID
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_REMIX_CLIENT_NAME
import org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_REMIX_HARDCODED_CLIENT_VERSION
import org.schabi.newpipe.extractor.services.youtube.protos.video.Xtags.XTags
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.RandomStringFromAlphabetGenerator
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.HTTP
import org.schabi.newpipe.extractor.utils.Utils.HTTPS
import org.schabi.newpipe.extractor.utils.Utils.escapeHtml
import org.schabi.newpipe.extractor.utils.Utils.getStringResultFromRegexArray
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * Compatibility builder that mimics old nanojson JsonBuilder API but builds
 * kotlinx.serialization.json.JsonObject/JsonArray.
 *
 * This is needed to keep existing Java/Kotlin call sites that use
 * `prepareDesktopJsonBuilder(...).value(...).done()` working after nanojson removal.
 */
class YoutubeJsonBuilder {
    private sealed class Node {
        abstract val key: String?
    }

    private data class ObjectNode(override val key: String?, val map: MutableMap<String, JsonElement>) :
        Node()

    private data class ArrayNode(override val key: String?, val list: MutableList<JsonElement>) : Node()

    private val stack = mutableListOf<Node>()

    constructor() {
        stack.add(ObjectNode(null, mutableMapOf()))
    }

    constructor(root: JsonObject) {
        stack.add(ObjectNode(null, root.toMutableMap()))
    }

    private val current: Node get() = stack.last()

    fun `object`(key: String): YoutubeJsonBuilder {
        stack.add(ObjectNode(key, mutableMapOf()))
        return this
    }

    fun `object`(): YoutubeJsonBuilder {
        // Special handling: if this is the initial root creation (JsonWriter.string().object() pattern),
        // where stack contains only empty root, treat as no-op.
        if (stack.size == 1) {
            val root = stack[0] as? ObjectNode
            if (root != null && root.map.isEmpty()) {
                return this
            }
        }
        stack.add(ObjectNode(null, mutableMapOf()))
        return this
    }

    fun array(key: String): YoutubeJsonBuilder {
        stack.add(ArrayNode(key, mutableListOf()))
        return this
    }

    fun array(): YoutubeJsonBuilder {
        stack.add(ArrayNode(null, mutableListOf()))
        return this
    }

    @JvmName("endNode")
    fun end(): YoutubeJsonBuilder {
        if (stack.size <= 1) return this
        val popped = stack.removeAt(stack.lastIndex)
        val built: JsonElement = when (popped) {
            is ObjectNode -> JsonObject(popped.map)
            is ArrayNode -> JsonArray(popped.list)
        }
        val parent = stack.last()
        when (parent) {
            is ObjectNode -> {
                val k = popped.key
                if (k != null) {
                    parent.map[k] = built
                } else {
                    // anonymous object inside object -> merge its fields into parent
                    if (popped is ObjectNode) {
                        parent.map.putAll(popped.map)
                    }
                }
            }
            is ArrayNode -> {
                parent.list.add(built)
            }
        }
        return this
    }

    fun value(key: String, value: String?): YoutubeJsonBuilder {
        val cur = current as? ObjectNode
            ?: throw IllegalStateException("value(key) called outside object")
        cur.map[key] = if (value == null) JsonNull else JsonPrimitive(value)
        return this
    }

    fun value(key: String, value: Boolean): YoutubeJsonBuilder {
        val cur = current as? ObjectNode ?: throw IllegalStateException()
        cur.map[key] = JsonPrimitive(value)
        return this
    }

    fun value(key: String, value: Number): YoutubeJsonBuilder {
        val cur = current as? ObjectNode ?: throw IllegalStateException()
        cur.map[key] = JsonPrimitive(value)
        return this
    }

    fun value(key: String, value: JsonElement?): YoutubeJsonBuilder {
        val cur = current as? ObjectNode ?: throw IllegalStateException()
        cur.map[key] = value ?: JsonNull
        return this
    }

    // Array value overloads (not used in current codebase but for completeness)
    fun value(value: String?): YoutubeJsonBuilder {
        val cur = current as? ArrayNode ?: throw IllegalStateException()
        cur.list.add(if (value == null) JsonNull else JsonPrimitive(value))
        return this
    }

    fun done(): JsonObject {
        while (stack.size > 1) {
            end()
        }
        val root = stack.first() as ObjectNode
        return JsonObject(root.map)
    }

    fun getObject(key: String): JsonObject? {
        val cur = current as? ObjectNode ?: return null
        return cur.map[key] as? JsonObject
    }

    fun getArray(key: String): JsonArray? {
        val cur = current as? ObjectNode ?: return null
        return cur.map[key] as? JsonArray
    }
}

object YoutubeParsingHelper {

    const val YOUTUBEI_V1_URL = "https://www.youtube.com/youtubei/v1/"
    const val YOUTUBEI_V1_GAPIS_URL = "https://youtubei.googleapis.com/youtubei/v1/"
    private const val YOUTUBE_MUSIC_URL = "https://music.youtube.com"
    const val DISABLE_PRETTY_PRINT_PARAMETER = "prettyPrint=false"
    const val CPN = "cpn"
    const val VIDEO_ID = "videoId"
    const val CONTENT_CHECK_OK = "contentCheckOk"
    const val RACY_CHECK_OK = "racyCheckOk"

    private var clientVersion: String? = null
    private var youtubeMusicClientVersion: String? = null
    private var clientVersionExtracted = false
    private var hardcodedClientVersionValid: Boolean? = null

    private val INNERTUBE_CONTEXT_CLIENT_VERSION_REGEXES = arrayOf(
        "INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([0-9\\.]+?)\"",
        "innertube_context_client_version\":\"([0-9\\.]+?)\"",
        "client.version=([0-9\\.]+)"
    )
    private val INITIAL_DATA_REGEXES = arrayOf(
        "window\\[\"ytInitialData\"\\]\\s*=\\s*(\\{.*?\\});",
        "var\\s*ytInitialData\\s*=\\s*(\\{.*?\\});"
    )

    private const val CONTENT_PLAYBACK_NONCE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private var numberGenerator: Random = Random()

    private const val FEED_BASE_CHANNEL_ID =
        "https://www.youtube.com/feeds/videos.xml?channel_id="
    private const val FEED_BASE_USER = "https://www.youtube.com/feeds/videos.xml?user="
    private val C_WEB_PATTERN = Pattern.compile("&c=WEB")
    private val C_WEB_EMBEDDED_PLAYER_PATTERN = Pattern.compile("&c=WEB_EMBEDDED_PLAYER")
    private val C_ANDROID_PATTERN = Pattern.compile("&c=ANDROID")
    private val C_IOS_PATTERN = Pattern.compile("&c=IOS")
    private val C_VISIONOS_PATTERN = Pattern.compile("&c=VISIONOS")

    private val GOOGLE_URLS = setOf("google.", "m.google.", "www.google.")
    private val INVIDIOUS_URLS = setOf(
        "invidio.us", "dev.invidio.us",
        "www.invidio.us", "redirect.invidious.io", "invidious.snopyta.org", "yewtu.be",
        "tube.connect.cafe", "tubus.eduvid.org", "invidious.kavin.rocks", "invidious.site",
        "invidious-us.kavin.rocks", "piped.kavin.rocks", "vid.mint.lgbt", "invidiou.site",
        "invidious.fdn.fr", "invidious.048596.xyz", "invidious.zee.li", "vid.puffyan.us",
        "ytprivate.com", "invidious.namazso.eu", "invidious.silkky.cloud", "ytb.trom.tf",
        "invidious.exonip.de", "inv.riverside.rocks", "invidious.blamefran.net", "y.com.cm",
        "invidious.moomoo.me", "yt.cyberhost.uk"
    )
    private val YOUTUBE_URLS = setOf(
        "youtube.com", "www.youtube.com",
        "m.youtube.com", "music.youtube.com"
    )

    private var consentAccepted = false

    @JvmStatic
    fun isGoogleURL(url: String): Boolean {
        val cachedUrl = extractCachedUrlIfNeeded(url)
        try {
            val u = URL(cachedUrl)
            return GOOGLE_URLS.any { u.host.startsWith(it) }
        } catch (e: MalformedURLException) {
            return false
        }
    }

    @JvmStatic
    fun isYoutubeURL(url: URL): Boolean {
        return YOUTUBE_URLS.contains(url.host.lowercase(java.util.Locale.ROOT))
    }

    @JvmStatic
    fun isYoutubeServiceURL(url: URL): Boolean {
        val host = url.host
        return host.equals("www.youtube-nocookie.com", ignoreCase = true)
                || host.equals("youtu.be", ignoreCase = true)
    }

    @JvmStatic
    fun isHooktubeURL(url: URL): Boolean {
        val host = url.host
        return host.equals("hooktube.com", ignoreCase = true)
    }

    @JvmStatic
    fun isInvidiousURL(url: URL): Boolean {
        return INVIDIOUS_URLS.contains(url.host.lowercase(java.util.Locale.ROOT))
    }

    @JvmStatic
    fun isY2ubeURL(url: URL): Boolean {
        return url.host.equals("y2u.be", ignoreCase = true)
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun parseDurationString(input: String): Int {
        if (!input.matches(".*\\d.*".toRegex()) && !input.equals("SHORTS", ignoreCase = true)) {
            throw ParsingException("Error duration string contains no digits: $input")
        }
        // Java's String.split drops trailing empty strings, and the segment count picks
        // the unit offset below, so a trailing separator must not add one.
        val splitInput = (if (input.contains(":")) input.split(":") else input.split("."))
            .dropLastWhile { it.isEmpty() }
        val units = intArrayOf(24, 60, 60, 1)
        val offset = units.size - splitInput.size
        if (offset < 0) {
            throw ParsingException("Error duration string with unknown format: $input")
        }
        var duration = 0
        for (i in splitInput.indices) {
            duration = units[i + offset] * (duration + convertDurationToInt(splitInput[i]))
        }
        return duration
    }

    private fun convertDurationToInt(input: String?): Int {
        if (input == null || input.isEmpty()) return 0
        val clearedInput = Utils.removeNonDigitCharacters(input)
        return try {
            clearedInput.toInt()
        } catch (ex: NumberFormatException) {
            0
        }
    }

    @JvmStatic
    fun getFeedUrlFrom(channelIdOrUser: String): String {
        return when {
            channelIdOrUser.startsWith("user/") -> FEED_BASE_USER + channelIdOrUser.replace("user/", "")
            channelIdOrUser.startsWith("channel/") -> FEED_BASE_CHANNEL_ID + channelIdOrUser.replace(
                "channel/",
                ""
            )
            else -> FEED_BASE_CHANNEL_ID + channelIdOrUser
        }
    }

    @JvmStatic
    fun isYoutubeMixId(playlistId: String): Boolean = playlistId.startsWith("RD")

    @JvmStatic
    fun isYoutubeMyMixId(playlistId: String): Boolean = playlistId.startsWith("RDMM")

    @JvmStatic
    fun isYoutubeMusicMixId(playlistId: String): Boolean =
        playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDCLAK")

    @JvmStatic
    fun isYoutubeGenreMixId(playlistId: String): Boolean = playlistId.startsWith("RDGMEM")

    @JvmStatic
    @Throws(ParsingException::class)
    fun extractVideoIdFromMixId(playlistId: String): String {
        if (isNullOrEmpty(playlistId)) {
            throw ParsingException("Video id could not be determined from empty playlist id")
        } else if (isYoutubeMyMixId(playlistId)) {
            return playlistId.substring(4)
        } else if (isYoutubeMusicMixId(playlistId)) {
            return playlistId.substring(6)
        } else if (isYoutubeGenreMixId(playlistId)) {
            throw ParsingException("Video id could not be determined from genre mix id: $playlistId")
        } else if (isYoutubeMixId(playlistId)) {
            if (playlistId.length != 13) {
                throw ParsingException("Video id could not be determined from mix id: $playlistId")
            }
            return playlistId.substring(2)
        } else {
            throw ParsingException("Video id could not be determined from playlist id: $playlistId")
        }
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun extractPlaylistTypeFromPlaylistId(playlistId: String?): PlaylistInfo.PlaylistType {
        if (isNullOrEmpty(playlistId)) {
            throw ParsingException("Could not extract playlist type from empty playlist id")
        } else if (isYoutubeMusicMixId(playlistId)) {
            return PlaylistInfo.PlaylistType.MIX_MUSIC
        } else if (isYoutubeGenreMixId(playlistId)) {
            return PlaylistInfo.PlaylistType.MIX_GENRE
        } else if (isYoutubeMixId(playlistId)) {
            return PlaylistInfo.PlaylistType.MIX_STREAM
        } else {
            return PlaylistInfo.PlaylistType.NORMAL
        }
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun extractPlaylistTypeFromPlaylistUrl(playlistUrl: String): PlaylistInfo.PlaylistType {
        try {
            return extractPlaylistTypeFromPlaylistId(
                Utils.getQueryValue(Utils.stringToURL(playlistUrl), "list")
            )
        } catch (e: MalformedURLException) {
            throw ParsingException("Could not extract playlist type from malformed url", e)
        }
    }

    @Throws(ParsingException::class)
    private fun getInitialData(html: String): JsonObject {
        try {
            return JsonUtils.toJsonObject(
                getStringResultFromRegexArray(html, INITIAL_DATA_REGEXES, 1)
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get ytInitialData", e)
        }
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun isHardcodedClientVersionValid(): Boolean {
        hardcodedClientVersionValid?.let {
            return it
        }
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("hl", "en-GB")
                    put("gl", "GB")
                    put("clientName", WEB_CLIENT_NAME)
                    put("clientVersion", WEB_HARDCODED_CLIENT_VERSION)
                    put("platform", DESKTOP_CLIENT_PLATFORM)
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
            put("fetchLiveState", true)
        }.toString().toByteArray(Charsets.UTF_8)

        val headers = getClientHeaders(WEB_CLIENT_ID, WEB_HARDCODED_CLIENT_VERSION)

        val response = org.schabi.newpipe.extractor.NewPipe.getDownloader().postWithContentTypeJson(
            YOUTUBEI_V1_URL + "guide?" + DISABLE_PRETTY_PRINT_PARAMETER,
            headers, body
        )
        val responseBody = response.responseBody()
        val responseCode = response.responseCode()

        val valid = responseBody.length > 5000 && responseCode == 200
        hardcodedClientVersionValid = valid
        return valid
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun extractClientVersionFromSwJs() {
        if (clientVersionExtracted) return
        val url = "https://www.youtube.com/sw.js"
        val headers = getOriginReferrerHeaders("https://www.youtube.com")
        val response = org.schabi.newpipe.extractor.NewPipe.getDownloader().get(url, headers).responseBody()
        try {
            clientVersion = getStringResultFromRegexArray(
                response,
                INNERTUBE_CONTEXT_CLIENT_VERSION_REGEXES,
                1
            )
        } catch (e: Parser.RegexException) {
            throw ParsingException(
                "Could not extract YouTube WEB InnerTube client version from sw.js",
                e
            )
        }
        clientVersionExtracted = true
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun extractClientVersionFromHtmlSearchResultsPage() {
        if (clientVersionExtracted) return

        val url = "https://www.youtube.com/results?search_query=&ucbcb=1"
        val html = org.schabi.newpipe.extractor.NewPipe.getDownloader().get(url, getCookieHeader()).responseBody()
        val initialData = getInitialData(html)
        val serviceTrackingParams = initialData.getObject("responseContext")
            ?.getArray("serviceTrackingParams")

        val serviceTrackingList = serviceTrackingParams?.mapNotNull { it as? JsonObject } ?: emptyList()

        clientVersion = getClientVersionFromServiceTrackingParam(
            serviceTrackingList, "CSI", "cver"
        )

        if (clientVersion == null) {
            try {
                clientVersion = getStringResultFromRegexArray(
                    html,
                    INNERTUBE_CONTEXT_CLIENT_VERSION_REGEXES,
                    1
                )
            } catch (ignored: Exception) {
            }
        }

        if (isNullOrEmpty(clientVersion)) {
            clientVersion = getClientVersionFromServiceTrackingParam(
                serviceTrackingList, "ECATCHER", "client.version"
            )
        }

        if (clientVersion == null) {
            throw ParsingException(
                "Could not extract YouTube WEB InnerTube client version from HTML search results page"
            )
        }

        clientVersionExtracted = true
    }

    private fun getClientVersionFromServiceTrackingParam(
        serviceTrackingParams: List<JsonObject>,
        serviceName: String,
        clientVersionKey: String
    ): String? {
        for (serviceTrackingParam in serviceTrackingParams) {
            if (serviceTrackingParam.getString("service") != serviceName) continue
            val params = serviceTrackingParam.getArray("params")?.mapNotNull { it as? JsonObject }
                ?: continue
            for (param in params) {
                if (param.getString("key") != clientVersionKey) continue
                val value = param.getString("value")
                if (!isNullOrEmpty(value)) return value
            }
        }
        return null
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getClientVersion(): String {
        if (!isNullOrEmpty(clientVersion)) {
            return clientVersion!!
        }

        try {
            extractClientVersionFromSwJs()
        } catch (e: Exception) {
            extractClientVersionFromHtmlSearchResultsPage()
        }

        if (clientVersionExtracted) {
            return clientVersion!!
        }

        if (isHardcodedClientVersionValid()) {
            clientVersion = WEB_HARDCODED_CLIENT_VERSION
            return clientVersion!!
        }

        throw ExtractionException("Could not get YouTube WEB client version")
    }

    @JvmStatic
    fun resetClientVersion() {
        clientVersion = null
        clientVersionExtracted = false
    }

    @JvmStatic
    fun setNumberGenerator(random: Random) {
        numberGenerator = random
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun isHardcodedYoutubeMusicClientVersionValid(): Boolean {
        val url =
            "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?" + DISABLE_PRETTY_PRINT_PARAMETER

        val json = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", WEB_REMIX_CLIENT_NAME)
                    put("clientVersion", WEB_REMIX_HARDCODED_CLIENT_VERSION)
                    put("hl", "en-GB")
                    put("gl", "GB")
                    put("platform", DESKTOP_CLIENT_PLATFORM)
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
            put("input", "")
        }.toString().toByteArray(Charsets.UTF_8)

        val headers = HashMap(getOriginReferrerHeaders(YOUTUBE_MUSIC_URL))
        headers.putAll(getClientHeaders(WEB_REMIX_CLIENT_ID, WEB_HARDCODED_CLIENT_VERSION))

        val response = org.schabi.newpipe.extractor.NewPipe.getDownloader().postWithContentTypeJson(url, headers, json)
        return response.responseBody().length > 500 && response.responseCode() == 200
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class, Parser.RegexException::class)
    fun getYoutubeMusicClientVersion(): String {
        if (!isNullOrEmpty(youtubeMusicClientVersion)) {
            return youtubeMusicClientVersion!!
        }
        if (isHardcodedYoutubeMusicClientVersionValid()) {
            youtubeMusicClientVersion = WEB_REMIX_HARDCODED_CLIENT_VERSION
            return youtubeMusicClientVersion!!
        }

        try {
            val url = "https://music.youtube.com/sw.js"
            val headers = getOriginReferrerHeaders(YOUTUBE_MUSIC_URL)
            val response = org.schabi.newpipe.extractor.NewPipe.getDownloader().get(url, headers).responseBody()

            youtubeMusicClientVersion = getStringResultFromRegexArray(
                response,
                INNERTUBE_CONTEXT_CLIENT_VERSION_REGEXES,
                1
            )
        } catch (e: Exception) {
            val url = "https://music.youtube.com/?ucbcb=1"
            val html = org.schabi.newpipe.extractor.NewPipe.getDownloader().get(url, getCookieHeader()).responseBody()

            youtubeMusicClientVersion = getStringResultFromRegexArray(
                html,
                INNERTUBE_CONTEXT_CLIENT_VERSION_REGEXES,
                1
            )
        }

        return youtubeMusicClientVersion!!
    }

    @JvmStatic
    fun getUrlFromNavigationEndpoint(navigationEndpoint: JsonObject?): String? {
        if (navigationEndpoint == null) {
            return null
        }
        if (navigationEndpoint.containsKey("urlEndpoint")) {
            var internUrl = navigationEndpoint.getObject("urlEndpoint")?.getString("url") ?: ""
            if (internUrl.startsWith("https://www.youtube.com/redirect?")) {
                internUrl = internUrl.substring(23)
            }

            if (internUrl.startsWith("/redirect?")) {
                internUrl = internUrl.substring(10)
                val params = internUrl.split("&")
                for (param in params) {
                    val split = param.split("=", limit = 2)
                    if (split[0] == "q") {
                        return Utils.decodeUrlUtf8(split.getOrNull(1) ?: "")
                    }
                }
            } else if (internUrl.startsWith("http")) {
                return internUrl
            } else if (internUrl.startsWith("/channel") || internUrl.startsWith("/user") || internUrl.startsWith("/watch")) {
                return "https://www.youtube.com$internUrl"
            }
        }

        if (navigationEndpoint.containsKey("browseEndpoint")) {
            val browseEndpoint = navigationEndpoint.getObject("browseEndpoint")
            val canonicalBaseUrl = browseEndpoint?.getString("canonicalBaseUrl")
            val browseId = browseEndpoint?.getString("browseId")

            if (browseId != null) {
                if (browseId.startsWith("UC")) {
                    return "https://www.youtube.com/channel/$browseId"
                } else if (browseId.startsWith("VL")) {
                    return "https://www.youtube.com/playlist?list=" + browseId.substring(2)
                }
            }

            if (!isNullOrEmpty(canonicalBaseUrl)) {
                return "https://www.youtube.com$canonicalBaseUrl"
            }
        }

        if (navigationEndpoint.containsKey("watchEndpoint")) {
            val watchEndpoint = navigationEndpoint.getObject("watchEndpoint")
            val videoId = watchEndpoint?.getString(VIDEO_ID) ?: return null
            val url = StringBuilder()
            url.append("https://www.youtube.com/watch?v=").append(videoId)
            if (watchEndpoint.containsKey("playlistId")) {
                url.append("&list=").append(watchEndpoint.getString("playlistId"))
            }
            if (watchEndpoint.containsKey("startTimeSeconds")) {
                val start = watchEndpoint.getString("startTimeSeconds")
                    ?: (watchEndpoint["startTimeSeconds"] as? JsonPrimitive)?.content
                if (start != null) {
                    url.append("&t=").append(start)
                } else {
                    // try int
                    val intVal = (watchEndpoint["startTimeSeconds"] as? JsonPrimitive)?.content
                    if (intVal != null) url.append("&t=").append(intVal)
                }
            }
            return url.toString()
        }

        if (navigationEndpoint.containsKey("watchPlaylistEndpoint")) {
            return "https://www.youtube.com/playlist?list=" +
                    navigationEndpoint.getObject("watchPlaylistEndpoint")?.getString("playlistId")
        }

        if (navigationEndpoint.containsKey("showDialogCommand")) {
            try {
                val listItems = JsonUtils.getArray(
                    navigationEndpoint,
                    "showDialogCommand.panelLoadingStrategy.inlineContent.dialogViewModel.customContent.listViewModel.listItems"
                )
                val command = JsonUtils.getObject(
                    listItems.getObject(0).orEmptyObject(),
                    "listItemViewModel.rendererContext.commandContext.onTap.innertubeCommand"
                )
                return getUrlFromNavigationEndpoint(command)
            } catch (p: Exception) {
            }
        }

        if (navigationEndpoint.containsKey("commandMetadata")) {
            val metadata = navigationEndpoint.getObject("commandMetadata")?.getObject("webCommandMetadata")
            if (metadata != null && metadata.containsKey("url")) {
                return "https://www.youtube.com" + metadata.getString("url")
            }
        }

        return null
    }

    @JvmStatic
    fun getTextFromObject(textObject: JsonObject?, html: Boolean): String? {
        if (textObject == null || textObject.isEmpty()) return null

        if (textObject.containsKey("simpleText")) {
            return textObject.getString("simpleText")
        }

        val runs = textObject.getArray("runs") ?: return null
        if (runs.isEmpty()) return null

        val textBuilder = StringBuilder()
        for (elem in runs) {
            val run = elem as? JsonObject ?: continue
            var text = run.getString("text") ?: ""

            if (html) {
                if (run.containsKey("navigationEndpoint")) {
                    val nav = run.getObject("navigationEndpoint")
                    if (nav != null) {
                        val url = getUrlFromNavigationEndpoint(nav)
                        if (!isNullOrEmpty(url)) {
                            text = "<a href=\"" + escapeHtml(url) + "\">" +
                                    escapeHtml(text) + "</a>"
                        }
                    }
                }

                val isBold = run.getBoolean("bold") == true
                val isItalic = run.getBoolean("italics") == true
                val isStrike = run.getBoolean("strikethrough") == true

                if (isBold) textBuilder.append("<b>")
                if (isItalic) textBuilder.append("<i>")
                if (isStrike) textBuilder.append("<s>")

                textBuilder.append(text)

                if (isStrike) textBuilder.append("</s>")
                if (isItalic) textBuilder.append("</i>")
                if (isBold) textBuilder.append("</b>")
            } else {
                textBuilder.append(text)
            }
        }

        var text = textBuilder.toString()

        if (html) {
            text = text.replace("\n".toRegex(), "<br>")
            text = text.replace(" {2}".toRegex(), " &nbsp;")
        }

        return text
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun getTextFromObjectOrThrow(textObject: JsonObject, error: String): String {
        val result = getTextFromObject(textObject)
        if (result == null) {
            throw ParsingException("Could not extract text: $error")
        }
        return result
    }

    @JvmStatic
    fun getTextFromObject(textObject: JsonObject?): String? {
        return getTextFromObject(textObject, false)
    }

    @JvmStatic
    fun getUrlFromObject(textObject: JsonObject?): String? {
        if (textObject == null || textObject.isEmpty()) return null

        val runs = textObject.getArray("runs") ?: return null
        if (runs.isEmpty()) return null

        for (textPartElem in runs) {
            val textPart = textPartElem as? JsonObject ?: continue
            val url = getUrlFromNavigationEndpoint(
                textPart.getObject("navigationEndpoint") ?: continue
            )
            if (!isNullOrEmpty(url)) return url
        }

        return null
    }

    @JvmStatic
    fun getTextAtKey(jsonObject: JsonObject, theKey: String): String? {
        val element = jsonObject[theKey]
        return if (element is JsonPrimitive && element.isString) {
            element.content
        } else {
            getTextFromObject(jsonObject.getObject(theKey))
        }
    }

    @JvmStatic
    fun fixThumbnailUrl(thumbnailUrl: String): String {
        var result = thumbnailUrl
        if (result.startsWith("//")) {
            result = result.substring(2)
        }

        if (result.startsWith(HTTP)) {
            result = Utils.replaceHttpWithHttps(result) ?: result
        } else if (!result.startsWith(HTTPS)) {
            result = "https://$result"
        }

        return result
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun getThumbnailsFromInfoItem(infoItem: JsonObject): List<Image> {
        try {
            val thumbs = infoItem.getObject("thumbnail")?.getArray("thumbnails")
                ?: throw ParsingException("Could not get thumbnails")
            return getImagesFromThumbnailsArray(thumbs)
        } catch (e: Exception) {
            if (e is ParsingException) throw e
            throw ParsingException("Could not get thumbnails from InfoItem", e)
        }
    }

    @JvmStatic
    fun getImagesFromThumbnailsArray(thumbnails: JsonArray): List<Image> {
        return thumbnails.mapNotNull { it as? JsonObject }
            .filter { !isNullOrEmpty(it.getString("url")) }
            .map { thumbnail ->
                val height = thumbnail["height"]?.let { el ->
                    (el as? JsonPrimitive)?.content?.toIntOrNull()
                } ?: Image.HEIGHT_UNKNOWN
                val url = thumbnail.getString("url")!!
                Image(
                    fixThumbnailUrl(url),
                    height,
                    thumbnail["width"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                        ?: Image.WIDTH_UNKNOWN,
                    Image.ResolutionLevel.fromHeight(height)
                )
            }
    }

    @JvmStatic
    @Throws(ParsingException::class, MalformedURLException::class)
    fun getValidJsonResponseBody(response: Response): String {
        if (response.responseCode() == 404) {
            throw ContentNotAvailableException(
                "Not found (\"" + response.responseCode() + " " + response.responseMessage() + "\")"
            )
        }

        val responseBody = response.responseBody()
        if (responseBody.length < 50) {
            throw ParsingException("JSON response is too short")
        }

        val latestUrl = URL(response.latestUrl())
        if (latestUrl.host.equals("www.youtube.com", ignoreCase = true)) {
            val path = latestUrl.path
            if (path.equals("/oops", ignoreCase = true) || path.equals("/error", ignoreCase = true)) {
                throw ContentNotAvailableException("Content unavailable")
            }
        }

        val responseContentType = response.getHeader("Content-Type")
        if (responseContentType != null && responseContentType.lowercase().contains("text/html")) {
            throw ParsingException(
                "Got HTML document, expected JSON response (latest url was: \"" + response.latestUrl() + "\")"
            )
        }

        return responseBody
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getJsonPostResponse(
        endpoint: String,
        body: ByteArray,
        localization: Localization
    ): JsonObject {
        val headers = getYouTubeHeaders()

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                org.schabi.newpipe.extractor.NewPipe.getDownloader().postWithContentTypeJson(
                    YOUTUBEI_V1_URL + endpoint + "?" + DISABLE_PRETTY_PRINT_PARAMETER,
                    headers,
                    body,
                    localization
                )
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getJsonPostResponse(
        endpoint: String,
        queryParameters: List<String>,
        body: ByteArray,
        localization: Localization
    ): JsonObject {
        val headers = getYouTubeHeaders()

        val queryParametersString = if (queryParameters.isEmpty()) {
            "?$DISABLE_PRETTY_PRINT_PARAMETER"
        } else {
            "?" + queryParameters.joinToString("&") + "&$DISABLE_PRETTY_PRINT_PARAMETER"
        }

        return JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                org.schabi.newpipe.extractor.NewPipe.getDownloader().postWithContentTypeJson(
                    YOUTUBEI_V1_URL + endpoint + queryParametersString,
                    headers,
                    body,
                    localization
                )
            )
        )
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun prepareDesktopJsonBuilder(
        localization: Localization,
        contentCountry: ContentCountry
    ): YoutubeJsonBuilder {
        val base = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("hl", localization.getLocalizationCode())
                    put("gl", contentCountry.countryCode)
                    put("clientName", WEB_CLIENT_NAME)
                    put("clientVersion", getClientVersion())
                    put("originalUrl", "https://www.youtube.com")
                    put("platform", DESKTOP_CLIENT_PLATFORM)
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
        }
        return YoutubeJsonBuilder(base)
    }

    @JvmStatic
    fun getAndroidUserAgent(localization: Localization?): String {
        return "com.google.android.youtube/" + ANDROID_CLIENT_VERSION +
                " (Linux; U; Android 15; " +
                (localization ?: Localization.DEFAULT).getCountryCode() + ") gzip"
    }

    @JvmStatic
    fun getIosUserAgent(localization: Localization?): String {
        return "com.google.ios.youtube/" + IOS_CLIENT_VERSION + "(" + IOS_DEVICE_MODEL +
                "; U; CPU iOS " + IOS_USER_AGENT_VERSION + " like Mac OS X; " +
                (localization ?: Localization.DEFAULT).getCountryCode() + ")"
    }

    @JvmStatic
    fun getVisionOsUserAgent(localization: Localization?): String {
        return "com.google.visionos.youtube/" + VISIONOS_CLIENT_VERSION + "(" +
                VISIONOS_DEVICE_MODEL + "; U; CPU visionOS " + VISIONOS_USER_AGENT_VERSION +
                " like Mac OS X; " +
                (localization ?: Localization.DEFAULT).getCountryCode() + ")"
    }

    @JvmStatic
    fun getYoutubeMusicHeaders(): Map<String, List<String>> {
        val headers = HashMap(getOriginReferrerHeaders(YOUTUBE_MUSIC_URL))
        headers.putAll(getClientHeaders(WEB_REMIX_CLIENT_ID, youtubeMusicClientVersion ?: WEB_REMIX_HARDCODED_CLIENT_VERSION))
        return headers
    }

    @JvmStatic
    @Throws(ExtractionException::class, IOException::class)
    fun getYouTubeHeaders(): Map<String, List<String>> {
        val headers = getClientInfoHeaders().toMutableMap()
        headers["Cookie"] = listOf(generateConsentCookie())
        return headers
    }

    @JvmStatic
    @Throws(ExtractionException::class, IOException::class)
    fun getClientInfoHeaders(): Map<String, List<String>> {
        val headers = HashMap(getOriginReferrerHeaders("https://www.youtube.com"))
        headers.putAll(getClientHeaders(WEB_CLIENT_ID, getClientVersion()))
        return headers
    }

    @JvmStatic
    fun getOriginReferrerHeaders(url: String): Map<String, List<String>> {
        val urlList = listOf(url)
        return mapOf("Origin" to urlList, "Referer" to urlList)
    }

    @JvmStatic
    fun getClientHeaders(name: String, version: String): Map<String, List<String>> {
        return mapOf(
            "X-YouTube-Client-Name" to listOf(name),
            "X-YouTube-Client-Version" to listOf(version)
        )
    }

    @JvmStatic
    fun getCookieHeader(): Map<String, List<String>> {
        return mapOf("Cookie" to listOf(generateConsentCookie()))
    }

    @JvmStatic
    fun addCookieHeader(headers: MutableMap<String, List<String>>) {
        val existing = headers["Cookie"]
        if (existing == null) {
            headers["Cookie"] = mutableListOf(generateConsentCookie())
        } else {
            val mutable = if (existing is MutableList) existing else existing.toMutableList()
            mutable.add(generateConsentCookie())
            headers["Cookie"] = mutable
        }
    }

    @JvmStatic
    fun addLoggedInHeaders(headers: MutableMap<String, List<String>>) {
        // No authenticated session in this fork.
    }

    @JvmStatic
    fun getSessionPoToken(
        clientName: String,
        localization: Localization,
        contentCountry: ContentCountry
    ): YoutubeSessionPoToken? {
        val provider = org.schabi.newpipe.extractor.NewPipe.getYoutubeSessionPoTokenProvider()
            ?: return null
        return try {
            provider.getSessionPoToken(clientName, localization, contentCountry, false)
        } catch (error: Exception) {
            System.err.println(
                "Could not obtain session-bound YouTube PO token: " +
                        error.javaClass.simpleName + ": " + error.message
            )
            null
        }
    }

    @JvmStatic
    fun generateConsentCookie(): String {
        return "SOCS=" + if (isConsentAccepted()) "CAISAiAD" else "CAE="
    }

    @JvmStatic
    fun extractCookieValue(cookieName: String, response: Response): String {
        val cookies = response.responseHeaders()["set-cookie"] ?: return ""
        var result = ""
        for (cookie in cookies) {
            val startIndex = cookie.indexOf(cookieName)
            if (startIndex != -1) {
                val end = cookie.indexOf(";", startIndex)
                val endIdx = if (end == -1) cookie.length else end
                result = cookie.substring(startIndex + cookieName.length + "=".length, endIdx)
            }
        }
        return result
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun defaultAlertsCheck(initialData: JsonObject) {
        val alerts = initialData.getArray("alerts")
        if (alerts != null && alerts.isNotEmpty()) {
            val alertRenderer = alerts.getObject(0)?.getObject("alertRenderer")
            val alertText = alertRenderer?.getObject("text")?.let { getTextFromObject(it) }
            val alertType = alertRenderer?.getString("type", "") ?: ""

            if (alertType.equals("ERROR", ignoreCase = true)) {
                if (alertText != null &&
                    (alertText.contains("This account has been terminated") ||
                            alertText.contains("This channel was removed"))
                ) {
                    if (alertText.matches(".*violat(ed|ion|ing).*".toRegex()) ||
                        alertText.contains("infringement")
                    ) {
                        throw AccountTerminatedException(
                            alertText,
                            AccountTerminatedException.Reason.VIOLATION
                        )
                    } else {
                        throw AccountTerminatedException(alertText)
                    }
                }
                throw ContentNotAvailableException("Got error: \"$alertText\"")
            }
        }
    }

    @JvmStatic
    fun extractCachedUrlIfNeeded(url: String?): String? {
        if (url == null) return null
        return if (url.contains("webcache.googleusercontent.com")) {
            url.split("cache:")[1]
        } else url
    }

    @JvmStatic
    fun isVerified(badges: JsonArray?): Boolean {
        if (badges == null || badges.isEmpty()) return false
        for (elem in badges) {
            val badge = elem as? JsonObject ?: continue
            val style = badge.getObject("metadataBadgeRenderer")?.getString("style")
            if (style == "BADGE_STYLE_TYPE_VERIFIED" || style == "BADGE_STYLE_TYPE_VERIFIED_ARTIST") {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun hasArtistOrVerifiedIconBadgeAttachment(attachmentRuns: JsonArray): Boolean {
        return attachmentRuns.mapNotNull { it as? JsonObject }
            .any { attachmentRun ->
                attachmentRun.getObject("element")
                    ?.getObject("type")
                    ?.getObject("imageType")
                    ?.getObject("image")
                    ?.getArray("sources")
                    ?.mapNotNull { it as? JsonObject }
                    ?.any { source ->
                        val imageName = source.getObject("clientResource")?.getString("imageName")
                        imageName == "CHECK_CIRCLE_FILLED" || imageName == "AUDIO_BADGE" || imageName == "MUSIC_FILLED"
                    } == true
            }
    }

    @JvmStatic
    fun generateContentPlaybackNonce(): String {
        return RandomStringFromAlphabetGenerator.generate(
            CONTENT_PLAYBACK_NONCE_ALPHABET, 16, numberGenerator
        )
    }

    @JvmStatic
    fun generateTParameter(): String {
        return RandomStringFromAlphabetGenerator.generate(
            CONTENT_PLAYBACK_NONCE_ALPHABET, 12, numberGenerator
        )
    }

    @JvmStatic
    fun isWebStreamingUrl(url: String): Boolean {
        return Parser.isMatch(C_WEB_PATTERN, url)
    }

    @JvmStatic
    fun isWebEmbeddedPlayerStreamingUrl(url: String): Boolean {
        return Parser.isMatch(C_WEB_EMBEDDED_PLAYER_PATTERN, url)
    }

    @JvmStatic
    fun isAndroidStreamingUrl(url: String): Boolean {
        return Parser.isMatch(C_ANDROID_PATTERN, url)
    }

    @JvmStatic
    fun isIosStreamingUrl(url: String): Boolean {
        return Parser.isMatch(C_IOS_PATTERN, url)
    }

    @JvmStatic
    fun isVisionOsStreamingUrl(url: String): Boolean {
        return Parser.isMatch(C_VISIONOS_PATTERN, url)
    }

    @JvmStatic
    fun setConsentAccepted(accepted: Boolean) {
        consentAccepted = accepted
    }

    @JvmStatic
    fun isConsentAccepted(): Boolean = consentAccepted

    @JvmStatic
    fun extractAudioTrackType(xtags: String?): AudioTrackType? {
        if (xtags == null) return null
        val atype: String?
        try {
            atype = XTags.parseFrom(Base64.getUrlDecoder().decode(xtags))
                .xtagsList
                .firstOrNull { it.key == "acont" }
                ?.value
        } catch (ignored: InvalidProtocolBufferException) {
            return null
        }

        if (atype == null) return null

        return when (atype) {
            "original" -> AudioTrackType.ORIGINAL
            "dubbed", "dubbed-auto" -> AudioTrackType.DUBBED
            "descriptive" -> AudioTrackType.DESCRIPTIVE
            "secondary" -> AudioTrackType.SECONDARY
            else -> null
        }
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun getVisitorDataFromInnertube(
        innertubeClientRequestInfo: InnertubeClientRequestInfo,
        localization: Localization,
        contentCountry: ContentCountry,
        httpHeaders: Map<String, List<String>>,
        innertubeDomainAndVersionEndpoint: String,
        embedUrl: String?,
        useGuideEndpoint: Boolean
    ): String {
        val builder = prepareJsonBuilder(
            localization, contentCountry, innertubeClientRequestInfo, embedUrl
        )

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val visitorData = JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                org.schabi.newpipe.extractor.NewPipe.getDownloader()
                    .postWithContentTypeJson(
                        innertubeDomainAndVersionEndpoint +
                                (if (useGuideEndpoint) "guide" else "visitor_id") + "?" +
                                DISABLE_PRETTY_PRINT_PARAMETER,
                        httpHeaders, body
                    )
            )
        )
            .getObject("responseContext")
            ?.getString("visitorData")

        if (isNullOrEmpty(visitorData)) {
            throw ParsingException("Could not get visitorData")
        }

        return visitorData
    }

    @JvmStatic
    fun prepareJsonBuilder(
        localization: Localization,
        contentCountry: ContentCountry,
        innertubeClientRequestInfo: InnertubeClientRequestInfo,
        embedUrl: String?
    ): YoutubeJsonBuilder {
        val base = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", innertubeClientRequestInfo.clientInfo.clientName)
                    put("clientVersion", innertubeClientRequestInfo.clientInfo.clientVersion)
                    val clientScreen = innertubeClientRequestInfo.clientInfo.clientScreen
                    if (clientScreen != null) put("clientScreen", clientScreen)
                    val platform = innertubeClientRequestInfo.deviceInfo.platform
                    if (platform != null) put("platform", platform)
                    val visitorData = innertubeClientRequestInfo.clientInfo.visitorData
                    if (visitorData != null) put("visitorData", visitorData)
                    val deviceMake = innertubeClientRequestInfo.deviceInfo.deviceMake
                    if (deviceMake != null) put("deviceMake", deviceMake)
                    val deviceModel = innertubeClientRequestInfo.deviceInfo.deviceModel
                    if (deviceModel != null) put("deviceModel", deviceModel)
                    val osName = innertubeClientRequestInfo.deviceInfo.osName
                    if (osName != null) put("osName", osName)
                    val osVersion = innertubeClientRequestInfo.deviceInfo.osVersion
                    if (osVersion != null) put("osVersion", osVersion)
                    val sdk = innertubeClientRequestInfo.deviceInfo.androidSdkVersion
                    if (sdk > 0) put("androidSdkVersion", sdk)
                    val userAgent = innertubeClientRequestInfo.clientInfo.userAgent
                    if (userAgent != null) put("userAgent", userAgent)
                    val timeZone = innertubeClientRequestInfo.clientInfo.timeZone
                    if (timeZone != null) put("timeZone", timeZone)
                    put("hl", localization.getLocalizationCode())
                    put("gl", contentCountry.countryCode)
                    put("utcOffsetMinutes", 0)
                }
                if (embedUrl != null) {
                    putJsonObject("thirdParty") {
                        put("embedUrl", embedUrl)
                    }
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
        }
        return YoutubeJsonBuilder(base)
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun getFirstCollaborator(navigationEndpoint: JsonObject?): JsonObject? {
        return try {
            val listItems = JsonUtils.getArray(
                navigationEndpoint,
                "showDialogCommand.panelLoadingStrategy.inlineContent.dialogViewModel.customContent.listViewModel.listItems"
            )
            listItems.getObject(0)?.getObject("listItemViewModel")
        } catch (e: ParsingException) {
            null
        }
    }
}
