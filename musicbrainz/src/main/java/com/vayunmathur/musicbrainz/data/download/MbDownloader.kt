package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.localization.Localization

/**
 * The [Downloader] the extractor calls out through, on top of `NetworkClient`.
 *
 * A near-copy of YouPipe's, kept here rather than shared because the two apps are
 * otherwise independent and `:youpipe:extractor` is the only thing they have in common.
 * The browser User-Agent is not cosmetic: the InnerTube endpoints reject the default.
 */
class MbDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val response = NetworkClient.performRequest(
            url = request.url,
            method = request.httpMethod,
            headers = withDefaultHeaders(request.headers),
            body = request.dataToSend,
        )
        Response(
            response.status,
            response.statusMessage,
            response.headers,
            response.body,
            response.url,
        )
    }

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
    ): StreamingResponse = runBlocking { streaming(url, "GET", headers, null, null) }

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
        timeoutMs: Long,
    ): StreamingResponse = runBlocking {
        streaming(url, "GET", headers, null, timeoutMs.coerceAtLeast(1))
    }

    override fun postStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?,
    ): StreamingResponse = runBlocking { streaming(url, "POST", headers, dataToSend, null) }

    private suspend fun streaming(
        url: String,
        method: String,
        headers: Map<String, List<String>>?,
        data: ByteArray?,
        timeoutMs: Long?,
    ): StreamingResponse {
        val (code, respHeaders, stream) = NetworkClient.performRequestInputStream(
            url = url,
            method = method,
            headers = withDefaultHeaders(headers),
            body = data,
            timeoutMs = timeoutMs,
        )
        return StreamingResponse(code, respHeaders, stream)
    }

    private fun withDefaultHeaders(
        headers: Map<String, List<String>>?,
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        if (headers != null) result.putAll(headers)
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] = listOf(DEFAULT_USER_AGENT)
        }
        return result
    }

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
