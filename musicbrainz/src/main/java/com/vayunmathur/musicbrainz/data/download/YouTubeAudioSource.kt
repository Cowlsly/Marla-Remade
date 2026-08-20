package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.musicbrainz.platform.DownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

/**
 * Finds a downloadable audio stream on YouTube, via the extractor YouPipe already vendors.
 *
 * The source of last resort: it needs no account and carries almost everything, but a
 * YouTube upload is a lossy re-encode of unknown provenance and matching a MusicBrainz
 * recording to one is guesswork, so duration does most of the work in [rank].
 *
 * Only progressive streams are used. YouTube also serves audio over SABR, which needs a
 * stateful session and the PO-token machinery that lives in the YouPipe app; a track that
 * is SABR-only is reported as unavailable rather than dragging that whole stack in here.
 */
object YouTubeAudioSource : AudioSource {

    override val id = DownloadSource.YouTube

    private const val SEARCH_RESULTS_CONSIDERED = 8

    // Two uploads within this many seconds are treated as an equal-length match, so YouTube's
    // own relevance order breaks the tie rather than an insignificantly closer runtime.
    internal const val MATCH_TOLERANCE_SECONDS = 3L

    // A candidate whose runtime differs from the catalogued length by more than this is taken
    // to be a different thing - a compilation, an extended mix, a sped-up edit - and dropped,
    // because synced lyrics timed to the real track would drift audibly against it.
    internal const val MAX_DURATION_DIFF_SECONDS = 15L

    override suspend fun resolve(query: AudioQuery): ResolvedAudio? = withContext(Dispatchers.IO) {
        val candidates = search(buildQuery(query.artist, query.title, query.album))
        if (candidates.isEmpty()) return@withContext null
        val ordered = rank(candidates, query.durationMs)
        for (candidate in ordered) {
            val audio = audioFor(candidate.url) ?: continue
            return@withContext audio.copy(sourceTitle = candidate.name)
        }
        null
    }

    private fun buildQuery(artist: String, title: String, album: String?): String = buildString {
        append(artist)
        append(" - ")
        append(title)
        if (!album.isNullOrBlank() && !title.contains(album, ignoreCase = true)) {
            append(' ')
            append(album)
        }
    }

    private fun search(query: String): List<StreamInfoItem> = try {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.getInitialPage()
            .getItems()
            .filterIsInstance<StreamInfoItem>()
            .take(SEARCH_RESULTS_CONSIDERED)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Orders candidates by how close their runtime is to the catalogued one, dropping any
     * that are too far off to be the same recording.
     *
     * Matches within a few seconds are treated as equally good and left in YouTube's own
     * relevance order, which is a better tie-breaker than an arbitrarily closer duration.
     * Candidates beyond [MAX_DURATION_DIFF_SECONDS] are rejected outright so a compilation or
     * a sped-up edit is never downloaded; only if nothing qualifies does the closest survive,
     * so an unusual track still downloads rather than failing.
     */
    private fun rank(candidates: List<StreamInfoItem>, durationMs: Int?): List<StreamInfoItem> {
        if (durationMs == null || durationMs <= 0) return candidates
        val target = durationMs / 1000L
        val scored = candidates.withIndex().map { (index, item) ->
            val duration = item.getDuration()
            val delta = if (duration > 0) abs(duration - target) else Long.MAX_VALUE
            Triple(item, delta, index)
        }
        val close = scored.filter { it.second <= MAX_DURATION_DIFF_SECONDS }
        return (if (close.isNotEmpty()) close else scored)
            .sortedWith(
                compareBy(
                    { if (it.second <= MATCH_TOLERANCE_SECONDS) 0L else it.second },
                    { it.third },
                ),
            )
            .map { it.first }
    }

    private fun audioFor(videoUrl: String): ResolvedAudio? = try {
        val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
        extractor.fetchPage()
        val progressive = extractor.getAudioStreams().filter {
            it.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.isUrl() &&
                it.getContent().isNotBlank()
        }
        // Prefer the highest-bitrate Opus: YouTube's itag 251 Opus (~160 kbps) beats the
        // best progressive AAC (itag 140, 128 kbps), and it is already 48 kHz Opus, so
        // remuxing WebM to Ogg is a lossless stream copy where transcoding would not be.
        val opus = progressive.filter { it.isOpus() }.maxByOrNull { it.effectiveBitrate() }
        if (opus != null) {
            return ResolvedAudio(
                urls = listOf(opus.getContent()),
                suffix = "opus",
                mimeType = "audio/ogg",
                bitrate = opus.effectiveBitrate(),
                sourceTitle = "",
                source = id,
                isOpusPassthrough = true,
            )
        }
        // Fallback: no Opus stream, so keep the best available and let the transcode path
        // re-encode it to Opus like every other lossy-source download.
        val best = progressive.maxByOrNull { it.effectiveBitrate() }
        best?.let {
            val format = it.getFormat()
            ResolvedAudio(
                urls = listOf(it.getContent()),
                suffix = format?.getSuffix() ?: "m4a",
                mimeType = format?.mimeType ?: "audio/mp4",
                bitrate = it.effectiveBitrate(),
                sourceTitle = "",
                source = id,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun AudioStream.isOpus(): Boolean =
        getFormat() == MediaFormat.OPUS || getFormat() == MediaFormat.WEBMA_OPUS

    private fun AudioStream.effectiveBitrate(): Int =
        getAverageBitrate().takeIf { it > 0 } ?: getBitrate()
}
