package org.schabi.newpipe.extractor.services.youtube.sabrng

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.utils.Parser
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds a session-based [YoutubeSabrInfo] from a MWEB player response, resolving signature and
 * n-parameter obfuscation via youpipe's shared deobfuscation path. This is the session-stack
 * equivalent of the old `YoutubeSabrProbe.fromPlayerResponse`, but it produces the [YoutubeSabrInfo]
 * consumed by [YoutubeSabrSession] and does not depend on the legacy `sabr` package.
 */
object YoutubeSabrNgInfoBuilder {
    private const val FALLBACK_CLIENT_VERSION = "2.20250122.04.00"

    @JvmStatic
    @JvmOverloads
    @Throws(ExtractionException::class)
    fun buildFromPlayerResponse(
        videoId: String,
        cpn: String,
        playerResponse: JsonObject,
        requestVisitorData: String?,
        poToken: ByteArray? = null
    ): YoutubeSabrInfo {
        val streamingData = playerResponse.getObject("streamingData")
            ?: throw SabrProtocolException("Player response has no streamingData")
        val unresolvedServerAbrStreamingUrl = streamingData.getString("serverAbrStreamingUrl")
        val ustreamerConfig = extractUstreamerConfig(playerResponse)
        val visitorData = if (requestVisitorData.isNullOrEmpty()) {
            extractVisitorData(playerResponse)
        } else {
            requestVisitorData
        }
        val adaptiveFormats = streamingData.getArray("adaptiveFormats")
        val signatures = LinkedHashSet<String>()
        val nParameters = LinkedHashSet<String>()
        collectDecodeParameters(adaptiveFormats, signatures, nParameters)
        extractNParameter(unresolvedServerAbrStreamingUrl)?.let { nParameters.add(it) }

        var decoded: YoutubeApiDecoder.BatchDecodeResult? = null
        var serverAbrStreamingUrl = unresolvedServerAbrStreamingUrl
        if (signatures.isNotEmpty() || nParameters.isNotEmpty()) {
            decoded = YoutubeJavaScriptPlayerManager.deobfuscateBatch(
                videoId, ArrayList(signatures), ArrayList(nParameters)
            )
            serverAbrStreamingUrl = resolveNParameter(unresolvedServerAbrStreamingUrl, decoded)
        }

        val formats = parseFormats(adaptiveFormats, decoded)
        return YoutubeSabrInfo(
            videoId, cpn, resolveClientVersion(), visitorData, serverAbrStreamingUrl,
            ustreamerConfig, formats, poToken
        )
    }

    @Throws(ParsingException::class)
    private fun collectDecodeParameters(
        formats: JsonArray?,
        signatures: MutableSet<String>,
        nParameters: MutableSet<String>
    ) {
        if (formats == null) {
            return
        }
        for (element in formats) {
            val format = element as? JsonObject ?: continue
            val parts = parseStreamingUrl(format)
            parts.signature?.let { signatures.add(it) }
            parts.nParameter?.let { nParameters.add(it) }
        }
    }

    @Throws(ParsingException::class)
    private fun parseFormats(
        formats: JsonArray?,
        decoded: YoutubeApiDecoder.BatchDecodeResult?
    ): List<YoutubeSabrInfo.Format> {
        val result = ArrayList<YoutubeSabrInfo.Format>()
        if (formats == null) {
            return result
        }
        for (element in formats) {
            val formatData = element as? JsonObject ?: continue
            val itag = formatData.getInt("itag") ?: continue
            val parsedFormat = try {
                ItagItem.getItag(itag)
            } catch (ignored: ParsingException) {
                continue
            }
            try {
                fillItagItem(parsedFormat, formatData)
                val audioTrack = formatData.getObject("audioTrack")
                val initRange = formatData.getObject("initRange")
                val indexRange = formatData.getObject("indexRange")
                val initRangeStart = parseLong(initRange?.getString("start"))
                var initRangeEnd = parseLong(initRange?.getString("end"))
                if (indexRange != null) {
                    initRangeEnd = maxOf(initRangeEnd, parseLong(indexRange.getString("end")))
                }
                result.add(
                    YoutubeSabrInfo.Format.fromParsedFormat(
                        parsedFormat,
                        parseLong(formatData.getString("lastModified")),
                        formatData.getString("xtags"),
                        formatData.getString("mimeType"),
                        audioTrack?.getString("id"),
                        audioTrack?.getString("displayName"),
                        formatData.getBoolean("isDrc", false),
                        resolveStreamingUrl(formatData, decoded),
                        initRangeStart, initRangeEnd
                    )
                )
            } catch (ignored: RuntimeException) {
                // Skip malformed formats without discarding the complete response.
            }
        }
        return result
    }

    private fun fillItagItem(itagItem: ItagItem, formatData: JsonObject) {
        val mimeType = formatData.getString("mimeType", "")
        val codec = if (mimeType.contains("codecs")) mimeType.split("\"")[1] else ""
        itagItem.bitrate = formatData.getInt("bitrate", itagItem.bitrate)
        itagItem.width = formatData.getInt("width", 0)
        itagItem.height = formatData.getInt("height", 0)
        if (codec.isNotEmpty()) {
            itagItem.codec = codec
        }
        itagItem.contentLength =
            parseLong(formatData.getString("contentLength")).let { if (it < 0) itagItem.contentLength else it }
        itagItem.approxDurationMs =
            parseLong(formatData.getString("approxDurationMs")).let { if (it < 0) itagItem.approxDurationMs else it }
    }

    @Throws(ParsingException::class)
    private fun resolveStreamingUrl(
        format: JsonObject,
        decoded: YoutubeApiDecoder.BatchDecodeResult?
    ): String? {
        val parts = parseStreamingUrl(format)
        var url = parts.url
        if (url.isNullOrEmpty()) {
            return url
        }
        if (parts.signature != null) {
            if (decoded == null) {
                return null
            }
            val signature = decoded.signatures[parts.signature] ?: return null
            url += (if (url.contains("?")) "&" else "?") +
                encodeUrlComponent(parts.signatureParameter ?: "signature") +
                '=' + encodeUrlComponent(signature)
        }
        return if (decoded == null) url else resolveNParameter(url, decoded)
    }

    @Throws(ParsingException::class)
    private fun parseStreamingUrl(format: JsonObject): StreamingUrlParts {
        var url = format.getString("url")
        var signature: String? = null
        var signatureParameter: String? = null
        val cipher = format.getString("signatureCipher") ?: format.getString("cipher")
        if (url.isNullOrEmpty() && !cipher.isNullOrEmpty()) {
            try {
                val values = Parser.compatParseMap(cipher)
                url = values["url"]
                signature = values["s"]
                signatureParameter = values.getOrDefault("sp", "signature")
            } catch (e: UnsupportedEncodingException) {
                throw ParsingException("Could not parse SABR signature cipher", e)
            }
        }
        return StreamingUrlParts(url, signature, signatureParameter, extractNParameter(url))
    }

    @Throws(ParsingException::class)
    private fun resolveNParameter(
        url: String?,
        decoded: YoutubeApiDecoder.BatchDecodeResult
    ): String? {
        if (url.isNullOrEmpty()) {
            return url
        }
        val encryptedN = extractNParameter(url) ?: return url
        val decryptedN = decoded.nParameters[encryptedN] ?: return url
        val queryMatcher = Regex("([?&])n=([^&]+)").find(url)
        if (queryMatcher != null) {
            val group = queryMatcher.groups[2]!!
            return url.substring(0, group.range.first) + encodeUrlComponent(decryptedN) +
                url.substring(group.range.last + 1)
        }
        val pathMatcher = Regex("/n/([^/?#]+)").find(url) ?: return url
        val group = pathMatcher.groups[1]!!
        return url.substring(0, group.range.first) + decryptedN +
            url.substring(group.range.last + 1)
    }

    @Throws(ParsingException::class)
    private fun extractNParameter(url: String?): String? {
        if (url.isNullOrEmpty()) {
            return null
        }
        val queryMatcher = Regex("([?&])n=([^&]+)").find(url)
        if (queryMatcher != null) {
            return try {
                URLDecoder.decode(queryMatcher.groupValues[2], StandardCharsets.UTF_8.name())
            } catch (e: UnsupportedEncodingException) {
                throw ParsingException("Could not decode SABR n parameter", e)
            }
        }
        return Regex("/n/([^/?#]+)").find(url)?.groupValues?.get(1)
    }

    @Throws(ParsingException::class)
    private fun encodeUrlComponent(value: String): String {
        return try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (e: UnsupportedEncodingException) {
            throw ParsingException("Could not encode SABR URL parameter", e)
        }
    }

    private fun parseLong(value: String?): Long = value?.toLongOrNull() ?: -1

    private fun resolveClientVersion(): String = try {
        YoutubeParsingHelper.getClientVersion()
    } catch (ignored: Exception) {
        FALLBACK_CLIENT_VERSION
    }

    private fun extractVisitorData(response: JsonObject): String? =
        response.getObject("responseContext")?.getString("visitorData")

    private fun extractUstreamerConfig(response: JsonObject): String? =
        response.getObject("playerConfig")
            ?.getObject("mediaCommonConfig")
            ?.getObject("mediaUstreamerRequestConfig")
            ?.getString("videoPlaybackUstreamerConfig")

    private class StreamingUrlParts(
        val url: String?,
        val signature: String?,
        val signatureParameter: String?,
        val nParameter: String?
    )
}
