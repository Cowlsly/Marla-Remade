package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel

/** Binds [NowPlayingScreen] to the ViewModel; renders nothing while the queue is empty. */
@Composable
fun SongScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val state = musicViewModel.nowPlayingState() ?: return
    NowPlayingScreen(state, musicViewModel, backStack)
}
