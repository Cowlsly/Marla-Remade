package com.vayunmathur.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconShuffle
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.ui.SOURCE_ALL_SONGS
import com.vayunmathur.music.ui.SOURCE_ALL_SONGS_NAME
import com.vayunmathur.music.platform.MusicViewModel

@Composable
fun ShufflePlayFab(musicViewModel: MusicViewModel) {
    val allSongs by musicViewModel.music.collectAsState()

    ShufflePlayFab(allSongs) {
        musicViewModel.playShuffled(allSongs, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
    }
}

/** Shuffle-everything FAB. Hidden while the library is still empty. */
@Composable
fun ShufflePlayFab(songs: List<Music>, onShuffle: () -> Unit) {
    if (songs.isNotEmpty()) {
        FloatingActionButton(onShuffle) {
            IconShuffle()
        }
    }
}
