package org.schabi.newpipe.extractor.services.youtube.sabrng

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Builds and sends session-based SABR requests and decodes their UMP responses. */
object YoutubeSabrRequestHelper {
    private const val MWEB_CLIENT_ID = 2
    private const val MAX_INITIALIZATION_BYTES = 4 * 1024 * 1024
    private const val MWEB_USER_AGENT =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    val MWEB_LOCALIZATION = Localization("en", "US")

    @JvmStatic
    @Throws(IOException::class)
    fun fetchInitializationData(
        format: YoutubeSabrInfo.Format,
        poToken: ByteArray,
        timeoutMs: Long
    ): ByteArray {
        val initializationUrl = format.getInitializationUrl()
        val start = format.getInitRangeStart()
        val end = format.getInitRangeEnd()
        if (initializationUrl.isNullOrEmpty() || start < 0 || end < start ||
            end - start >= MAX_INITIALIZATION_BYTES
        ) {
            throw IOException(
                "Invalid SABR initialization range: itag=" + format.getItag() +
                    ", start=" + start + ", end=" + end
            )
        }
        if (poToken.isEmpty()) {
            throw IOException(
                "Missing PO token for SABR initialization range: itag=" + format.getItag()
            )
        }
        val url = appendQueryParameterIfMissing(
            initializationUrl, "pot",
            Base64.getUrlEncoder().withoutPadding().encodeToString(poToken)
        )
        val length = (end - start + 1).toInt()
        val headers: Map<String, List<String>> = mapOf("Range" to listOf("bytes=$start-$end"))
        try {
            val response = if (timeoutMs > 0) {
                NewPipe.getDownloader().getStreaming(url, headers, MWEB_LOCALIZATION, timeoutMs)
            } else {
                NewPipe.getDownloader().getStreaming(url, headers, MWEB_LOCALIZATION)
            }
            response.use {
                if (it.responseCode() != 206 && !(it.responseCode() == 200 && start == 0L)) {
                    throw IOException(
                        "SABR initialization range failed: itag=" + format.getItag() +
                            ", status=" + it.responseCode()
                    )
                }
                return readExactly(it.body(), length)
            }
        } catch (error: ExtractionException) {
            throw IOException("SABR initialization range failed: itag=" + format.getItag(), error)
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun post(
        info: YoutubeSabrInfo,
        request: YoutubeSabrRequest,
        session: YoutubeSabrSession,
        serverAbrStreamingUrl: String,
        requestNumber: Int,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer?,
        segmentStartConsumer: SabrStreamingResponseReader.SegmentConsumer?,
        segmentSpoolDirectory: File?
    ): YoutubeSabrResponse {
        val requestBody = buildMediaRequest(info, request, session, requestNumber > 0)
        val requestStartNs = System.nanoTime()
        var firstSegmentElapsedMs = -1L
        val timedConsumer: SabrStreamingResponseReader.SegmentConsumer? =
            if (segmentConsumer == null) {
                null
            } else {
                SabrStreamingResponseReader.SegmentConsumer { segment ->
                    if (firstSegmentElapsedMs < 0) {
                        firstSegmentElapsedMs = elapsedMs(requestStartNs)
                    }
                    segmentConsumer.accept(segment)
                }
            }
        val timedStartConsumer: SabrStreamingResponseReader.SegmentConsumer? =
            if (segmentStartConsumer == null) {
                null
            } else {
                SabrStreamingResponseReader.SegmentConsumer { segment ->
                    if (firstSegmentElapsedMs < 0) {
                        firstSegmentElapsedMs = elapsedMs(requestStartNs)
                    }
                    segmentStartConsumer.accept(segment)
                }
            }
        NewPipe.getDownloader().postStreaming(
            withSessionParameters(serverAbrStreamingUrl, info.getCpn(), requestNumber),
            buildRequestHeaders(), requestBody, MWEB_LOCALIZATION
        ).use { response ->
            val contentType = response.getHeader("Content-Type")
            if (contentType == null ||
                !contentType.lowercase().contains("application/vnd.yt-ump")
            ) {
                throw SabrProtocolException(
                    "Expected UMP response, got content type: " + contentType +
                        ", status=" + response.responseCode()
                )
            }
            val body = CountingInputStream(response.body())
            val streamed = SabrStreamingResponseReader.read(
                body, timedConsumer, timedStartConsumer, segmentSpoolDirectory
            )
            val result = streamed.getProbeResult()
            result.complete(
                info, streamed.getSegments(), streamed.getSegmentCount(),
                response.responseCode(), contentType, body.getCount(),
                streamed.getMediaPayloadBytes(), streamed.getMediaPartPayloadBytes(),
                streamed.getControlPayloadBytes(), streamed.getTotalPayloadBytes(),
                streamed.getMaxPartBytes(), streamed.getMaxMediaPartPayloadBytes(),
                streamed.getMaxSegmentBytes(), elapsedMs(requestStartNs), firstSegmentElapsedMs
            )
            return result
        }
    }

    @Throws(SabrProtocolException::class)
    private fun buildMediaRequest(
        info: YoutubeSabrInfo,
        sabrRequest: YoutubeSabrRequest,
        session: YoutubeSabrSession,
        followUp: Boolean
    ): ByteArray {
        val ustreamerConfig = info.getVideoPlaybackUstreamerConfig()
        if (ustreamerConfig.isNullOrEmpty()) {
            throw SabrProtocolException("Missing video playback ustreamer config")
        }
        val playbackState = sabrRequest.getPlaybackState()
        val audioTrack = sabrRequest.getAudioTrack()
        val videoTrack = sabrRequest.getVideoTrack()
        val audioFormat = audioTrack?.getFormat()
        val videoFormat = videoTrack?.getFormat()
        val playerTimeMs = playbackState.getPlayerTimeMs()
        val playbackRate = playbackState.getPlaybackRate()
        val bufferedRanges = ArrayList<ByteArray>()
        if (sabrRequest.hasSelectedTracks()) {
            for (track in sabrRequest.getTracks()) {
                addBufferedRange(bufferedRanges, track)
            }
        }
        val includePlaybackState = followUp || playerTimeMs > 0 || bufferedRanges.isNotEmpty()
        val includeSelectedTracks = includePlaybackState && sabrRequest.hasSelectedTracks()
        val trackMode = if (audioTrack != null && videoTrack == null) {
            1
        } else if (videoTrack != null && audioTrack == null) {
            2
        } else {
            0
        }
        val request = SabrProto.Writer()
        request.writeMessage(
            1,
            buildClientAbrState(
                audioFormat, videoFormat, playerTimeMs, followUp || includePlaybackState,
                trackMode, session.getBandwidthEstimate(), playbackRate
            )
        )
        if (includePlaybackState) {
            if (includeSelectedTracks) {
                for (track in sabrRequest.getTracks()) {
                    request.writeMessage(2, SabrProto.formatId(track.getFormat()))
                }
            }
            for (range in bufferedRanges) {
                request.writeMessage(3, range)
            }
            request.writeUInt64(4, playerTimeMs)
        }
        request.writeBytes(5, decodeBase64(ustreamerConfig))
        writePreferredFormats(request, audioFormat, videoFormat)
        request.writeMessage(19, buildStreamerContext(info, session))
        return request.toByteArray()
    }

    private fun buildClientAbrState(
        audioFormat: YoutubeSabrInfo.Format?,
        videoFormat: YoutubeSabrInfo.Format?,
        playerTimeMs: Long,
        includeFollowUpState: Boolean,
        enabledTrackTypesBitfield: Int,
        requestedBandwidthEstimate: Long,
        playbackRate: Float
    ): ByteArray {
        val state = SabrProto.Writer()
        if (includeFollowUpState && videoFormat != null) {
            state.writeInt32(18, Math.max(videoFormat.getWidth(), 640))
            state.writeInt32(19, Math.max(videoFormat.getHeight(), 360))
        }
        if (videoFormat != null) {
            state.writeInt32(21, Math.max(videoFormat.getHeight(), 360))
        }
        if (includeFollowUpState) {
            val bandwidthEstimate = if (requestedBandwidthEstimate > 0) {
                requestedBandwidthEstimate
            } else {
                activeBitrateEstimate(audioFormat, videoFormat)
            }
            if (bandwidthEstimate > 0) {
                state.writeUInt64(23, bandwidthEstimate)
            }
        }
        state.writeInt32(34, 1)
        state.writeFloat(35, if (playbackRate > 0) playbackRate else 1.0f)
        if (enabledTrackTypesBitfield != 0) {
            state.writeInt32(40, enabledTrackTypesBitfield)
        }
        if (audioFormat != null && audioFormat.isDrc()) {
            state.writeBool(46, true)
        }
        state.writeUInt64(28, playerTimeMs)
        if (audioFormat != null) {
            state.writeStringIfNotEmpty(69, audioFormat.getAudioTrackId())
        }
        return state.toByteArray()
    }

    private fun activeBitrateEstimate(
        audioFormat: YoutubeSabrInfo.Format?,
        videoFormat: YoutubeSabrInfo.Format?
    ): Long {
        var bitrate = 0L
        if (audioFormat != null) {
            if (audioFormat.getBitrate() <= 0) return -1
            bitrate += audioFormat.getBitrate()
        }
        if (videoFormat != null) {
            if (videoFormat.getBitrate() <= 0) return -1
            bitrate += videoFormat.getBitrate()
        }
        return if (bitrate > 0) bitrate * 2L else -1
    }

    private fun writePreferredFormats(
        request: SabrProto.Writer,
        audioFormat: YoutubeSabrInfo.Format?,
        videoFormat: YoutubeSabrInfo.Format?
    ) {
        if (audioFormat != null) {
            request.writeMessage(16, SabrProto.formatId(audioFormat))
        }
        if (videoFormat != null) {
            request.writeMessage(17, SabrProto.formatId(videoFormat))
        }
    }

    private fun buildStreamerContext(
        info: YoutubeSabrInfo,
        session: YoutubeSabrSession
    ): ByteArray {
        val context = SabrProto.Writer()
        context.writeMessage(1, buildClientInfo(info))
        val poToken = session.getRawPoToken()
        if (poToken != null && poToken.isNotEmpty()) {
            context.writeBytes(2, poToken)
        }
        val playbackCookie = session.getRawPlaybackCookie()
        if (playbackCookie != null && playbackCookie.isNotEmpty()) {
            context.writeBytes(3, playbackCookie)
        }
        for ((key, value) in session.getActiveSabrContexts()) {
            val sabrContext = SabrProto.Writer()
            sabrContext.writeInt32(1, key)
            sabrContext.writeBytes(2, value)
            context.writeMessage(5, sabrContext.toByteArray())
        }
        for (type in session.getUnsentSabrContextTypes()) {
            context.writeInt32(6, type)
        }
        return context.toByteArray()
    }

    private fun addBufferedRange(ranges: MutableList<ByteArray>, track: YoutubeSabrRequest.Track) {
        val timeline = track.getTimeline()
        val bufferedThrough = track.getBufferedThrough()
        if (timeline == null || bufferedThrough <= 0) {
            return
        }
        val endSequence = Math.min(bufferedThrough, timeline.getEndSequence())
        if (endSequence <= 0) {
            return
        }
        val format = track.getFormat()
        ranges.add(
            buildBufferedRange(
                format.getItag(), format.getLastModified(), format.getXtags(), 0,
                Math.max(0, timeline.getEndMs(endSequence)), 1, endSequence, 1000
            )
        )
    }

    private fun buildClientInfo(info: YoutubeSabrInfo): ByteArray {
        val client = SabrProto.Writer()
        client.writeInt32(16, MWEB_CLIENT_ID)
        client.writeStringIfNotEmpty(17, info.getClientVersion())
        client.writeStringIfNotEmpty(21, "en-US")
        client.writeStringIfNotEmpty(22, "US")
        return client.toByteArray()
    }

    private fun buildBufferedRange(
        itag: Int,
        lastModified: Long,
        xtags: String?,
        startTimeMs: Long,
        durationMs: Long,
        startSegmentIndex: Int,
        endSegmentIndex: Int,
        timescale: Int
    ): ByteArray {
        val format = SabrProto.Writer()
        format.writeInt32(1, itag)
        if (lastModified > 0) {
            format.writeUInt64(2, lastModified)
        }
        format.writeStringIfNotEmpty(3, xtags)

        val timeRange = SabrProto.Writer()
        timeRange.writeUInt64(1, startTimeMs)
        timeRange.writeUInt64(2, durationMs)
        timeRange.writeInt32(3, timescale)

        val range = SabrProto.Writer()
        range.writeMessage(1, format.toByteArray())
        range.writeUInt64(2, startTimeMs)
        range.writeUInt64(3, durationMs)
        range.writeInt32(4, startSegmentIndex)
        range.writeInt32(5, endSegmentIndex)
        range.writeMessage(6, timeRange.toByteArray())
        return range.toByteArray()
    }

    private fun buildRequestHeaders(): Map<String, List<String>> = mapOf(
        "Content-Type" to listOf("application/x-protobuf"),
        "Accept" to listOf("application/vnd.yt-ump"),
        "Accept-Encoding" to listOf("identity"),
        "User-Agent" to listOf(MWEB_USER_AGENT)
    )

    private fun withSessionParameters(url: String, cpn: String, requestNumber: Int): String {
        var result = appendQueryParameterIfMissing(url, "alr", "yes")
        result = appendQueryParameterIfMissing(result, "cpn", cpn)
        return setQueryParameter(result, "rn", requestNumber.toString())
    }

    private fun appendQueryParameterIfMissing(url: String, name: String, value: String): String {
        if (url.matches(Regex(".*(?:[?&])" + name + "=[^&]*.*"))) {
            return url
        }
        return url + (if (url.contains("?")) '&' else '?') + name + '=' + value
    }

    private fun setQueryParameter(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#')
        val baseUrl = if (fragmentIndex < 0) url else url.substring(0, fragmentIndex)
        val fragment = if (fragmentIndex < 0) "" else url.substring(fragmentIndex)
        val queryIndex = baseUrl.indexOf('?')
        val path = if (queryIndex < 0) baseUrl else baseUrl.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else baseUrl.substring(queryIndex + 1)
        val result = StringBuilder(path).append('?')
        var wroteParameter = false
        for (parameter in query.split("&")) {
            if (parameter.isEmpty()) {
                continue
            }
            val equalsIndex = parameter.indexOf('=')
            val parameterName = if (equalsIndex < 0) parameter else parameter.substring(0, equalsIndex)
            if (parameterName == name) {
                continue
            }
            if (wroteParameter) {
                result.append('&')
            }
            result.append(parameter)
            wroteParameter = true
        }
        if (wroteParameter) {
            result.append('&')
        }
        return result.append(name).append('=').append(value).append(fragment).toString()
    }

    @Throws(SabrProtocolException::class)
    private fun decodeBase64(value: String): ByteArray {
        return try {
            Base64.getDecoder().decode(padBase64(value))
        } catch (first: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(padBase64(value))
            } catch (second: IllegalArgumentException) {
                throw SabrProtocolException("Could not decode base64 ustreamer config", second)
            }
        }
    }

    private fun padBase64(value: String): String {
        val padding = (4 - value.length % 4) % 4
        val builder = StringBuilder(value)
        for (i in 0 until padding) {
            builder.append('=')
        }
        return builder.toString()
    }

    private fun elapsedMs(startNs: Long): Long =
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs))

    @Throws(IOException::class)
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) {
                throw IOException(
                    "Truncated SABR initialization range: expected=$length, actual=$offset"
                )
            }
            offset += read
        }
        return data
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        private var count: Long = 0

        @Throws(IOException::class)
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                count++
            }
            return value
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
            }
            return read
        }

        fun getCount(): Long = count
    }
}
