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
    val releaseTrackId: String,
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
)

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.Releases,
    val loading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val artists: List<ArtistRow> = emptyList(),
    val releaseGroups: List<ReleaseGroupRow> = emptyList(),
    val recordings: List<RecordingRow> = emptyList(),
)

data class ArtistUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val name: String = "",
    val subtitle: String? = null,
    val releaseGroups: List<ReleaseGroupRow> = emptyList(),
)

data class ReleaseGroupUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val releases: List<ReleaseRow> = emptyList(),
)

data class ReleaseUiState(
    val loading: Boolean = true,
    val error: String? = null,
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
