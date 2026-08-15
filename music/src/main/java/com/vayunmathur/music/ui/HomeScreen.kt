package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.SongsUiState

/** Binds [SongsScreen] to the ViewModel. */
@Composable
fun HomeTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val music by musicViewModel.music.collectAsState()

    SongsScreen(
        state = SongsUiState(
            songs = music,
            playingSongId = musicViewModel.playingSongIdFrom(SOURCE_ALL_SONGS),
        ),
        actions = musicViewModel,
        backStack = backStack,
    )
}
