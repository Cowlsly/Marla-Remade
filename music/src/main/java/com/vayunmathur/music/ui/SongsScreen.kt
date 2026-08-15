package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.ui.component.ShufflePlayFab
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.SongsUiState

/** Queue id the songs tab plays under; [SOURCE_ALL_SONGS_NAME] is its display label. */
internal const val SOURCE_ALL_SONGS = "all_songs"
internal const val SOURCE_ALL_SONGS_NAME = "All Songs"

/**
 * Songs tab content. No Scaffold / no BottomNavBar — those live in the
 * surrounding [MusicTabsScreen]. ListPage's own Scaffold is kept (it owns
 * the TopAppBar with the embedded search bar and the shuffle FAB).
 */
@Composable
fun SongsScreen(state: SongsUiState, actions: MusicActions, backStack: NavBackStack<Route>) {
    ListPage<Music, Route, Route.Song>(backStack, state.songs, stringResource(R.string.page_title_music), { song ->
        val isPlaying = song.id == state.playingSongId
        Text(
            text = song.title,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
        )
    }, {
        Text(it.artist)
    }, { toPlay ->
        val allSongs = state.songs
        val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
        actions.playSong(allSongs, toPlayIndex, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        Route.Song
    }, leadingContent = { song ->
        val isPlaying = song.id == state.playingSongId
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying) {
                IconPlay(modifier = Modifier.size(24.dp).padding(end = 8.dp))
            }
            AlbumArt(song.uri.toUri(), Modifier.size(40.dp))
        }
    }, trailingContent = { song ->
        AddToPlaylistButton(backStack, song)
    }, itemModifier = { Modifier.clip(RoundedCornerShape(12.dp)) },
    itemColors = { song ->
        if (song.id == state.playingSongId) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        }
    }, searchEnabled = true, fab = {
        ShufflePlayFab(state.songs) {
            actions.playShuffled(state.songs, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        }
    }, sortOrder = Comparator.comparing { it.title })
}
