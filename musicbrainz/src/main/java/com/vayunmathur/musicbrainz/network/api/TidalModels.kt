package com.vayunmathur.musicbrainz.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the Tidal v1 API.
 *
 * Only the fields the downloader uses are declared; the client decodes with
 * `ignoreUnknownKeys`, so the (very large) responses stay readable.
 */

@Serializable
data class TidalArtist(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class TidalAlbum(
    val id: Int = 0,
    val title: String = "",
    /** The cover UID, dashes-to-slashes into a resources.tidal.com image path. */
    val cover: String? = null,
)

@Serializable
data class TidalTrack(
    val id: Int = 0,
    val title: String = "",
    /** Seconds, unlike MusicBrainz's milliseconds. */
    val duration: Int = 0,
    val trackNumber: Int = 0,
    val volumeNumber: Int = 0,
    val isrc: String? = null,
    val audioQuality: String? = null,
    val artist: TidalArtist? = null,
    val artists: List<TidalArtist> = emptyList(),
    val album: TidalAlbum? = null,
)

@Serializable
data class TidalTrackList(
    val items: List<TidalTrack> = emptyList(),
)

@Serializable
data class TidalSearchResult(
    val tracks: TidalTrackList = TidalTrackList(),
)

/**
 * The playback response. [manifest] is base64; [manifestMimeType] says whether it decodes
 * to a Tidal BTS JSON blob or a DASH MPD, which [com.vayunmathur.musicbrainz.data.download.TidalManifest]
 * pulls the stream URLs out of.
 */
@Serializable
data class TidalPlaybackInfo(
    val trackId: Int = 0,
    val audioQuality: String = "",
    @SerialName("manifestMimeType") val manifestMimeType: String = "",
    val manifest: String = "",
)
