package com.vayunmathur.musicbrainz.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.platform.download.DownloadItem
import com.vayunmathur.musicbrainz.platform.download.DownloadState
import com.vayunmathur.musicbrainz.platform.ArtistUiState
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.ReleaseGroupRow
import com.vayunmathur.musicbrainz.platform.ReleaseUiState
import com.vayunmathur.musicbrainz.platform.SearchTab
import com.vayunmathur.musicbrainz.platform.SearchUiState
import com.vayunmathur.musicbrainz.platform.TrackRow

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:musicbrainz`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio
 * but is not collected as a screenshot test. Previews must also be class members, not
 * top-level functions. Order comes from the function names (Preview1..., Preview2...).
 *
 * Cover art URLs are left null throughout - [CoverArtImage] skips the network under
 * `LocalInspectionMode` anyway, so the artwork tiles render as glyphs.
 */
class MetadataPreviews {

    private val backStack = NavBackStack<Route>(arrayOf(Route.Search))

    private fun releaseGroup(id: String, title: String, artist: String, subtitle: String) =
        ReleaseGroupRow(id = id, title = title, artist = artist, subtitle = subtitle)

    private fun track(
        position: Int,
        title: String,
        artist: String,
        durationMs: Int,
        onDevice: Boolean = false,
        download: DownloadItem? = null,
    ) = TrackRow(
        rowKey = "0/$position",
        mediumIndex = 0,
        releaseTrackId = null,
        recordingId = "recording-$position",
        position = position,
        title = title,
        artist = artist,
        durationMs = durationMs,
        onDevice = onDevice,
        download = download,
    )

    @PreviewTest
    @Preview(name = "1-search", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Search() {
        DynamicTheme(darkTheme = true) {
            SearchScreen(
                state = SearchUiState(
                    query = "the neon owls",
                    tab = SearchTab.Releases,
                    hasSearched = true,
                    releaseGroups = listOf(
                        releaseGroup("1", "After Hours", "The Neon Owls", "2024 · Album"),
                        releaseGroup("2", "Golden Hour", "The Neon Owls", "2022 · Album"),
                        releaseGroup("3", "Static Bloom", "The Neon Owls", "2021 · EP"),
                        releaseGroup("4", "Neon Rain", "The Neon Owls", "2019 · Album"),
                        releaseGroup("5", "First Light", "The Neon Owls", "2017 · Single"),
                    ),
                ),
                actions = MusicBrainzActions.Noop,
                backStack = backStack,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-release", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Release() {
        DynamicTheme(darkTheme = true) {
            ReleaseScreen(
                state = ReleaseUiState(
                    loading = false,
                    id = "release-1",
                    title = "After Hours",
                    artist = "The Neon Owls",
                    subtitle = "2024 · Official · Digital Media · 8 tracks",
                    tracks = listOf(
                        track(1, "Midnight Drive", "The Neon Owls", 254_000, onDevice = true),
                        track(2, "Golden Hour", "The Neon Owls", 211_000, onDevice = true),
                        track(3, "Long Way Home", "The Neon Owls", 198_000),
                        track(
                            4,
                            "Static Bloom",
                            "The Neon Owls",
                            226_000,
                            download = DownloadItem(
                                id = "track-4",
                                title = "Static Bloom",
                                artist = "The Neon Owls",
                                album = "After Hours",
                                state = DownloadState.Downloading,
                                progress = 0.6f,
                            ),
                        ),
                        track(5, "Neon Rain", "The Neon Owls", 241_000),
                        track(6, "Afterglow", "The Neon Owls", 220_000),
                    ),
                ),
                actions = MusicBrainzActions.Noop,
                backStack = backStack,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-artist", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Artist() {
        DynamicTheme(darkTheme = true) {
            ArtistScreen(
                state = ArtistUiState(
                    loading = false,
                    name = "The Neon Owls",
                    subtitle = "Group · United Kingdom · 2015",
                    releaseGroups = listOf(
                        releaseGroup("1", "After Hours", "", "2024 · Album"),
                        releaseGroup("2", "Golden Hour", "", "2022 · Album"),
                        releaseGroup("3", "Static Bloom", "", "2021 · EP"),
                        releaseGroup("4", "Neon Rain", "", "2019 · Album"),
                        releaseGroup("5", "Coastlines", "", "2018 · Album"),
                        releaseGroup("6", "First Light", "", "2017 · Single"),
                    ),
                ),
                actions = MusicBrainzActions.Noop,
                backStack = backStack,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-downloads", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Downloads() {
        DynamicTheme(darkTheme = true) {
            DownloadsScreen(
                downloads = listOf(
                    DownloadItem(
                        id = "a",
                        title = "Long Way Home",
                        artist = "The Neon Owls",
                        album = "After Hours",
                        state = DownloadState.Downloading,
                        progress = 0.42f,
                    ),
                    DownloadItem(
                        id = "b",
                        title = "Neon Rain",
                        artist = "The Neon Owls",
                        album = "After Hours",
                        state = DownloadState.Searching,
                    ),
                    DownloadItem(
                        id = "c",
                        title = "Afterglow",
                        artist = "The Neon Owls",
                        album = "After Hours",
                        state = DownloadState.Queued,
                    ),
                    DownloadItem(
                        id = "d",
                        title = "Midnight Drive",
                        artist = "The Neon Owls",
                        album = "After Hours",
                        state = DownloadState.Done,
                        progress = 1f,
                    ),
                ),
                actions = MusicBrainzActions.Noop,
                backStack = backStack,
            )
        }
    }
}

