package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.media3.common.Player
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.ui.component.MusicTabsBar
import com.vayunmathur.music.ui.component.NowPlayingBar
import com.vayunmathur.music.util.AlbumDetailUiState
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.NowPlayingUiState
import com.vayunmathur.music.util.SongsUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:music`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Nothing here touches MediaStore. The sample tracks carry plausible `content://` URIs
 * because the screens build MediaItems from them, but [com.vayunmathur.music.util.AlbumArt]
 * short-circuits to its placeholder under `LocalInspectionMode`, so no thumbnail is ever
 * read off the device — which is also why the artwork tiles render as glyphs rather than
 * covers.
 */
class MetadataPreviews {

    private fun song(
        id: Long,
        title: String,
        artist: String,
        artistId: Long,
        album: String,
        albumId: Long,
        durationMs: Long,
        trackNumber: Int,
    ) = Music(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        uri = "content://media/external/audio/media/$id",
        duration = durationMs,
        trackNumber = trackNumber,
        year = 2024,
    )

    private val library = listOf(
        song(1, "Midnight Drive", "The Neon Owls", 10, "After Hours", 100, 254_000, 1),
        song(2, "Golden Hour", "The Neon Owls", 10, "After Hours", 100, 211_000, 2),
        song(3, "Paper Planes", "Marina Vale", 11, "Coastlines", 101, 187_000, 1),
        song(4, "Coastlines", "Marina Vale", 11, "Coastlines", 101, 232_000, 2),
        song(5, "Slow Mornings", "Kite & Ember", 12, "Homebound", 102, 199_000, 1),
        song(6, "City Lights", "Kite & Ember", 12, "Homebound", 102, 243_000, 2),
        song(7, "Wildflower", "June Sparrow", 13, "Meadow", 103, 176_000, 1),
        song(8, "Riptide Blue", "June Sparrow", 13, "Meadow", 103, 268_000, 2),
    )

    private val nowPlaying = NowPlayingUiState(
        title = "Midnight Drive",
        artist = "The Neon Owls",
        album = "After Hours",
        artworkUri = "content://media/external/audio/albumart/100".toUri(),
        isPlaying = true,
        positionMs = 96_000,
        durationMs = 254_000,
        shuffle = true,
        repeatMode = Player.REPEAT_MODE_ALL,
        artistId = 10,
        albumId = 100,
        sourceId = "all_songs",
        sourceName = "All Songs",
    )

    private val backStack = NavBackStack<Route>(arrayOf(Route.Home))

    /**
     * The tab scaffolding [MusicTabsScreen] puts around every tab: the mini player docked
     * over the four-tab bar. Rebuilt here rather than reused because the real one owns a
     * pager and kicks off a MediaStore sync.
     */
    @Composable
    private fun Tabbed(selectedTab: Int, content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            Scaffold(
                bottomBar = {
                    Column(Modifier.fillMaxWidth()) {
                        NowPlayingBar(nowPlaying, MusicActions.Noop, onOpen = {})
                        MusicTabsBar(selectedTab = selectedTab, onSelectTab = {})
                    }
                },
            ) { padding ->
                Box(Modifier.padding(bottom = padding.calculateBottomPadding())) { content() }
            }
        }
    }

    @PreviewTest
    @Preview(name = "1-songs", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Songs() {
        Tabbed(selectedTab = 0) {
            SongsScreen(
                state = SongsUiState(songs = library, playingSongId = 1),
                actions = MusicActions.Noop,
                backStack = backStack,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-now-playing", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2NowPlaying() {
        DynamicTheme(darkTheme = true) {
            NowPlayingScreen(
                state = nowPlaying,
                actions = MusicActions.Noop,
                backStack = backStack,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-album", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Album() {
        DynamicTheme(darkTheme = true) {
            AlbumDetailContent(
                state = AlbumDetailUiState(
                    albumId = 100,
                    name = "After Hours",
                    artUri = "content://media/external/audio/albumart/100".toUri(),
                    artistName = "The Neon Owls",
                    artistId = 10,
                    info = "2024 • 8 songs • 29:10",
                    tracks = listOf(
                        song(1, "Midnight Drive", "The Neon Owls", 10, "After Hours", 100, 254_000, 1),
                        song(2, "Golden Hour", "The Neon Owls", 10, "After Hours", 100, 211_000, 2),
                        song(9, "Long Way Home", "The Neon Owls", 10, "After Hours", 100, 198_000, 3),
                        song(10, "Static Bloom", "The Neon Owls", 10, "After Hours", 100, 226_000, 4),
                        song(11, "Neon Rain", "The Neon Owls", 10, "After Hours", 100, 241_000, 5),
                        song(12, "Afterglow", "The Neon Owls", 10, "After Hours", 100, 220_000, 6),
                    ),
                    playingSongId = 1,
                ),
                actions = MusicActions.Noop,
                backStack = backStack,
                bottomBar = { NowPlayingBar(nowPlaying, MusicActions.Noop, onOpen = {}) },
            )
        }
    }
}
