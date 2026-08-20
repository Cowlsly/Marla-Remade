package com.vayunmathur.musicbrainz.platform

import com.vayunmathur.musicbrainz.platform.download.DownloadItem

/**
 * The contract between [MusicBrainzViewModel] and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel, so they
 * can be rendered by a `@Preview` - which is where the store listing images come from.
 * Kept in `util` so the dependency runs one way: `ui` depends on `util`, and the
 * ViewModel implements the actions.
 */

enum class SearchTab { Releases, Artists, Recordings }

/** A row in a list, already resolved against the local library. */
data class ArtistRow(
    val id: String,
    val name: String,
    val subtitle: String? = null,
)

data class ReleaseGroupRow(
    val id: String,
    val title: String,
    val artist: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
)

data class ReleaseRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    /** Release-group cover, shown when the release has no artwork of its own. */
    val fallbackCoverUrl: String? = null,
    val onDevice: Boolean = false,
)

data class RecordingRow(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val releaseId: String? = null,
    val releaseGroupId: String? = null,
    val durationMs: Int? = null,
    val onDevice: Boolean = false,
)

/** A track on a release, plus whether it is already owned or currently downloading. */
data class TrackRow(
    /**
     * Unique within the release and stable across reloads, which is what a lazy list needs
     * as a key. Built from the track's place in the response rather than its MBID, because
     * the catalogue does not carry one and a column of identical keys crashes the list.
     */
    val rowKey: String,
    /** Which medium of the release this came from, as an index into `MbRelease.media`. */
    val mediumIndex: Int,
    /** The release-track MBID, absent unless the catalogue happens to carry one. */
    val releaseTrackId: String?,
    val recordingId: String?,
    val position: Int,
    val title: String,
    val artist: String,
    val durationMs: Int? = null,
    val discNumber: Int = 1,
    /** The recording's ISRCs, which let a catalogue source match on identity. */
    val isrcs: List<String> = emptyList(),
    val onDevice: Boolean = false,
    val download: DownloadItem? = null,
) {
    /**
     * The queue identity for this track, which has to agree with
     * [com.vayunmathur.musicbrainz.platform.download.DownloadRequest.key] or a row cannot
     * find the download it started. Covered by `DownloadKeyTest`.
     */
    fun downloadKey(album: String?): String =
        releaseTrackId ?: recordingId ?: "$artist\u0000$album\u0000$title"
}

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.Releases,
    val loading: Boolean = false,
    val error: String? = null,
    /** The catalogue is still importing, so this is a wait rather than a fault. */
    val notReady: Boolean = false,
    val hasSearched: Boolean = false,
    val artists: List<ArtistRow> = emptyList(),
    val releaseGroups: List<ReleaseGroupRow> = emptyList(),
    val recordings: List<RecordingRow> = emptyList(),
)

data class ArtistUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** The catalogue is still importing, so this is a wait rather than a fault. */
    val notReady: Boolean = false,
    val name: String = "",
    val subtitle: String? = null,
    val releaseGroups: List<ReleaseGroupRow> = emptyList(),
)

data class ReleaseGroupUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** The catalogue is still importing, so this is a wait rather than a fault. */
    val notReady: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val releases: List<ReleaseRow> = emptyList(),
)

data class ReleaseUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** The catalogue is still importing, so this is a wait rather than a fault. */
    val notReady: Boolean = false,
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val subtitle: String? = null,
    val coverUrl: String? = null,
    /** Release-group cover, shown when the release has no artwork of its own. */
    val fallbackCoverUrl: String? = null,
    val tracks: List<TrackRow> = emptyList(),
) {
    val ownedCount: Int get() = tracks.count { it.onDevice }
}

data class SettingsUiState(
    val folderName: String? = null,
    val scanning: Boolean = false,
    val indexedTracks: Int = 0,
    val downloadSource: DownloadSource = DownloadSource.YouTube,
    /** The signed-in Tidal user, or null when signed out. */
    val tidalUsername: String? = null,
)

/** How far along the Tidal device-code sign-in is. */
enum class TidalLoginStatus { Starting, AwaitingUser, Success, Failed }

data class TidalLoginUiState(
    val status: TidalLoginStatus = TidalLoginStatus.Starting,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val error: String? = null,
)

interface MusicBrainzActions {
    fun onQueryChange(query: String) {}
    fun onTabChange(tab: SearchTab) {}
    fun search() {}

    fun loadArtist(id: String) {}
    fun loadReleaseGroup(id: String) {}
    fun loadRelease(id: String) {}

    fun downloadTrack(track: TrackRow) {}
    fun downloadRelease() {}
    fun downloadRecording(recording: RecordingRow) {}
    fun cancelDownload(id: String) {}
    fun clearFinishedDownloads() {}

    fun setMusicFolder(uri: String) {}
    fun rescanLibrary() {}
    fun setDownloadSource(source: DownloadSource) {}
    fun signOutOfTidal() {}

    companion object {
        val Noop: MusicBrainzActions = object : MusicBrainzActions {}
    }
}
