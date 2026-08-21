package com.vayunmathur.musicbrainz.data.download

import android.content.Context
import com.vayunmathur.musicbrainz.data.tidal.TidalSession
import com.vayunmathur.musicbrainz.network.api.TidalApi
import com.vayunmathur.musicbrainz.network.api.TidalTrack
import com.vayunmathur.musicbrainz.platform.DownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Finds a downloadable stream on Tidal for a signed-in subscriber.
 *
 * Tidal is a catalogue with structured metadata, so matching is far surer than YouTube's:
 * a track carries its ISRC, which is an exact identity match, and failing that its real
 * album title and an exact duration. When no session exists this returns null immediately
 * so [AudioSources] falls straight through to YouTube without a wasted request.
 */
class TidalAudioSource(context: Context) : AudioSource {

    override val id = DownloadSource.Tidal

    private val appContext = context.applicationContext
    private val session = TidalSession(appContext)

    override suspend fun resolve(query: AudioQuery): ResolvedAudio? = withContext(Dispatchers.IO) {
        val token = session.accessToken() ?: return@withContext null
        val country = session.countryCode() ?: return@withContext null

        val results = runCatching {
            TidalApi.search(searchQuery(query), token, country).tracks.items
        }.getOrNull().orEmpty()
        if (results.isEmpty()) return@withContext null

        val match = pick(results, query) ?: return@withContext null
        val stream = bestStream(match.id, token, country) ?: return@withContext null

        ResolvedAudio(
            urls = stream.urls,
            suffix = stream.suffix,
            mimeType = stream.mimeType,
            bitrate = 0,
            sourceTitle = match.title,
            source = id,
        )
    }

    /**
     * Walks down the quality ladder until a stream comes back that can actually be decoded.
     *
     * The ladder is capped at `HIGH`, so the lossless and hi-res tiers are never requested.
     * Everything is re-encoded to a fixed-bitrate Opus file afterwards, and the tiers above
     * `HIGH` cost far more bandwidth than that re-encode can preserve. Tidal downgrades
     * server-side when the subscription or the track cannot serve the ask and reports what it
     * really returned, so the first rung usually succeeds. The remaining rung matters when
     * `playbackInfo` itself fails, or when the manifest that comes back is encrypted or
     * DRM-protected and [TidalManifest] rejects it - dropping a rung is better than losing
     * the download.
     */
    private suspend fun bestStream(trackId: Int, token: String, country: String): TidalStream? {
        for (quality in QUALITY_LADDER) {
            val playback = runCatching {
                TidalApi.playbackInfo(trackId, quality, token, country)
            }.getOrNull() ?: continue
            val stream = runCatching {
                TidalManifest.decode(
                    playback.manifestMimeType,
                    playback.manifest,
                    playback.audioQuality,
                )
            }.getOrNull()
            if (stream != null) return stream
        }
        return null
    }

    private fun searchQuery(query: AudioQuery): String = "${query.artist} ${query.title}".trim()

    /**
     * Picks the best candidate, strongest signal first.
     *
     * An ISRC match is an exact identity match and is taken outright. Otherwise the album
     * title breaks ties - the right album's pressing is almost always what the user wants -
     * and duration rejects anything too far off, reusing the same thresholds as the YouTube
     * source so both reject a mismatch alike. Tidal durations are seconds, not ms.
     */
    internal fun pick(candidates: List<TidalTrack>, query: AudioQuery): TidalTrack? {
        val isrcs = query.isrcs.map { it.uppercase() }.toSet()
        if (isrcs.isNotEmpty()) {
            candidates.firstOrNull { it.isrc?.uppercase() in isrcs }?.let { return it }
        }

        val targetSeconds = query.durationMs?.takeIf { it > 0 }?.let { it / 1000L }
        val scored = candidates.withIndex().map { (index, track) ->
            val delta = if (targetSeconds != null && track.duration > 0) {
                abs(track.duration - targetSeconds)
            } else {
                Long.MAX_VALUE
            }
            val albumMatch = query.album != null &&
                track.album?.title?.equals(query.album, ignoreCase = true) == true
            ScoredTrack(track, delta, albumMatch, index)
        }

        val withinTolerance = scored.filter {
            targetSeconds == null || it.delta <= YouTubeAudioSource.MAX_DURATION_DIFF_SECONDS
        }
        val pool = withinTolerance.ifEmpty { scored }

        return pool.sortedWith(
            compareByDescending<ScoredTrack> { it.albumMatch }
                .thenBy { if (it.delta <= YouTubeAudioSource.MATCH_TOLERANCE_SECONDS) 0L else it.delta }
                .thenBy { it.index },
        ).firstOrNull()?.track
    }

    private data class ScoredTrack(
        val track: TidalTrack,
        val delta: Long,
        val albumMatch: Boolean,
        val index: Int,
    )

    private companion object {
        /**
         * Tidal's own `audioquality` values, best first, capped at `HIGH`.
         *
         * `HIGH` is AAC in an m4a container, so downloads never come back as FLAC.
         */
        val QUALITY_LADDER = listOf("HIGH", "LOW")
    }
}
