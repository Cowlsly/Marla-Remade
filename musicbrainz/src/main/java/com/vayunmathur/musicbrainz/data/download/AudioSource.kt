package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.musicbrainz.platform.DownloadSource

/**
 * What a source is asked to find.
 *
 * Bundled rather than passed as loose parameters so a source that needs a signal the
 * others ignore - Tidal matches on [isrcs], YouTube cannot - does not change every
 * signature between here and the worker.
 */
data class AudioQuery(
    val artist: String,
    val title: String,
    val album: String?,
    /**
     * The MusicBrainz recording length. Search alone happily returns hour-long
     * compilations and sped-up edits of the same title, and duration is the one signal
     * every source can use to separate them from the actual track.
     */
    val durationMs: Int?,
    /**
     * The recording's ISRCs. An exact identity match when the source catalogues them,
     * which beats every other signal; empty for recordings MusicBrainz has none for.
     */
    val isrcs: List<String> = emptyList(),
)

/** A resolved, directly fetchable audio stream. */
data class ResolvedAudio(
    /**
     * The stream's parts, in order, to be concatenated. A progressive stream is a
     * one-element list; a DASH stream is one entry per segment.
     */
    val urls: List<String>,
    val suffix: String,
    val mimeType: String,
    val bitrate: Int,
    val sourceTitle: String,
    val source: DownloadSource,
    /**
     * True when the stream is already 48 kHz Opus, just in a WebM container, so it only needs
     * remuxing to Ogg rather than transcoding. Re-encoding it would be a second lossy
     * generation for no gain, so this is the one stream that keeps its bits.
     */
    val isOpusPassthrough: Boolean = false,
)

/**
 * One place a track's audio can be fetched from.
 *
 * The seam exists so the sources stay independent of the download pipeline: everything
 * after [resolve] - buffered fetch, tagging, SAF write, index upsert - is the same
 * whichever source produced the URLs.
 */
interface AudioSource {
    val id: DownloadSource

    /** Returns null when this source has no match, so the caller can try the next one. */
    suspend fun resolve(query: AudioQuery): ResolvedAudio?
}
