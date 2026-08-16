package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * media3 [MediaSource] that publishes a generated DASH manifest (with exact SABR segment counts)
 * and serves segments from a session-based [SabrNgSession] via [SabrNgSegmentDataSource].
 */
@OptIn(UnstableApi::class)
class SabrNgDashMediaSource
@Throws(IOException::class)
constructor(
    context: Context,
    private val mediaItem: MediaItem,
    private val spec: SabrNgSourceSpec,
    onAttestationFailure: (() -> YoutubeSabrInfo?)? = null
) : CompositeMediaSource<Int>() {

    private val session: SabrNgSession
    private val durationUs: Long
    private val childSource: DashMediaSource

    init {
        val spoolDir = File(context.cacheDir, "sabrng").apply { mkdirs() }
        session = SabrNgSession(spec, spoolDir, onAttestationFailure)
        val durationMs = spec.getDurationMs()
        durationUs = if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET
        val dataSourceFactory = DataSource.Factory {
            SabrNgSegmentDataSource(session, SEGMENT_TIMEOUT_MS)
        }
        val manifest = buildManifest(spec, durationMs)
        childSource = DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSourceFactory),
            /* manifestDataSourceFactory= */ null
        ).createMediaSource(manifest, mediaItem)
    }

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        session.start()
        prepareChildSource(0, childSource)
    }

    override fun onChildSourceInfoRefreshed(
        id: Int?,
        mediaSource: MediaSource,
        timeline: Timeline
    ) {
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        if (startPositionUs > 0) {
            session.requestSeek(startPositionUs / 1000L)
        }
        val child = childSource.createPeriod(id, allocator, startPositionUs)
        return SabrNgMediaPeriod(child)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val period = mediaPeriod as SabrNgMediaPeriod
        childSource.releasePeriod(period.child)
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        session.stop()
    }

    private inner class SabrNgMediaPeriod(val child: MediaPeriod) : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            child.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) {
                        callback.onPrepared(this@SabrNgMediaPeriod)
                    }

                    override fun onContinueLoadingRequested(source: MediaPeriod) {
                        callback.onContinueLoadingRequested(this@SabrNgMediaPeriod)
                    }
                },
                positionUs
            )
        }

        @Throws(IOException::class)
        override fun maybeThrowPrepareError() = child.maybeThrowPrepareError()

        override fun getTrackGroups(): TrackGroupArray = child.trackGroups

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
            child.getStreamKeys(trackSelections)

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long =
            child.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
            )

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
            child.discardBuffer(positionUs, toKeyframe)

        override fun readDiscontinuity(): Long = child.readDiscontinuity()

        override fun seekToUs(positionUs: Long): Long {
            session.requestSeek(maxOf(0, positionUs) / 1000L)
            return child.seekToUs(positionUs)
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long = child.getAdjustedSeekPositionUs(positionUs, seekParameters)

        override fun getBufferedPositionUs(): Long = child.bufferedPositionUs

        override fun getNextLoadPositionUs(): Long = child.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
            child.continueLoading(loadingInfo)

        override fun isLoading(): Boolean = child.isLoading

        override fun reevaluateBuffer(positionUs: Long) = child.reevaluateBuffer(positionUs)
    }

    private companion object {
        private const val SEGMENT_TIMEOUT_MS = 30_000L

        @Throws(IOException::class)
        private fun buildManifest(spec: SabrNgSourceSpec, durationMs: Long): DashManifest {
            val mpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" " +
                "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"" +
                formatDuration(durationMs) + "\">" +
                "<Period id=\"0\" start=\"PT0S\">" +
                adaptationSet(spec.videoTimeline, spec.videoFormat, C.TRACK_TYPE_VIDEO) +
                adaptationSet(spec.audioTimeline, spec.audioFormat, C.TRACK_TYPE_AUDIO) +
                "</Period></MPD>"
            try {
                return DashManifestParser().parse(
                    "sabr://${spec.videoId}".toUri(),
                    ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8))
                )
            } catch (e: IOException) {
                throw IOException("Error when parsing generated SABR DASH manifest", e)
            }
        }

        private fun adaptationSet(
            timeline: YoutubeSabrFormatTimeline,
            format: YoutubeSabrInfo.Format,
            trackType: Int
        ): String {
            val mime = containerMimeType(format)
            val codecs = codecs(format)
            val contentType = if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "video"
            val builder = StringBuilder()
                .append("<AdaptationSet id=\"").append(format.getItag())
                .append("\" contentType=\"").append(contentType)
                .append("\" mimeType=\"").append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">")
                .append("<Representation id=\"").append(format.getItag())
                .append("\" bandwidth=\"").append(maxOf(1, format.getBitrate())).append("\"")
            if (!codecs.isNullOrEmpty()) {
                builder.append(" codecs=\"").append(xml(codecs)).append("\"")
            }
            if (trackType == C.TRACK_TYPE_VIDEO) {
                builder.append(" width=\"").append(maxOf(1, format.getWidth()))
                    .append("\" height=\"").append(maxOf(1, format.getHeight())).append("\"")
            } else {
                builder.append(" audioSamplingRate=\"48000\"")
            }
            builder.append(">")
                .append("<BaseURL>sabrseg://").append(format.getItag()).append("/</BaseURL>")
                .append(segmentTemplate(timeline, format))
                .append("</Representation></AdaptationSet>")
            return builder.toString()
        }

        private fun segmentTemplate(
            timeline: YoutubeSabrFormatTimeline,
            format: YoutubeSabrInfo.Format
        ): String {
            val endSegment = timeline.getEndSequence()
            check(endSegment in 1..10_000) {
                "Invalid exact SABR segment count: itag=${format.getItag()}, count=$endSegment"
            }
            val builder = StringBuilder()
                .append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"\$Number\$\">")
                .append("<SegmentTimeline>")
            for (sequence in 1..endSegment) {
                val startMs = timeline.getStartMs(sequence)
                val endMs = timeline.getEndMs(sequence)
                val durationMs = maxOf(1, endMs - startMs)
                builder.append("<S t=\"").append(maxOf(0, startMs))
                    .append("\" d=\"").append(durationMs).append("\"/>")
            }
            return builder.append("</SegmentTimeline></SegmentTemplate>").toString()
        }

        private fun formatDuration(durationMs: Long): String {
            val safeDurationMs = maxOf(1, durationMs)
            return "PT" + (safeDurationMs / 1000) + "." +
                String.format(Locale.US, "%03d", safeDurationMs % 1000) + "S"
        }

        private fun containerMimeType(format: YoutubeSabrInfo.Format): String {
            val mime = format.getMimeType()
            if (mime.isNullOrEmpty()) {
                return if (format.isAudio()) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4
            }
            val semicolon = mime.indexOf(';')
            return if (semicolon >= 0) mime.substring(0, semicolon).trim() else mime.trim()
        }

        private fun codecs(format: YoutubeSabrInfo.Format): String? {
            val mime = format.getMimeType() ?: return null
            val start = mime.indexOf("codecs=")
            if (start < 0) {
                return null
            }
            return mime.substring(start + "codecs=".length).replace("\"", "").trim()
        }

        private fun xml(value: String): String =
            value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
