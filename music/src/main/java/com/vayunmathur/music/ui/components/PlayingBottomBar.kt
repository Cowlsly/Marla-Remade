package com.vayunmathur.music.ui.components

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.util.MusicViewModel

/** Binds [NowPlayingBar] to the ViewModel; renders nothing while the queue is empty. */
@Composable
fun PlayingBottomBar(
    musicViewModel: MusicViewModel,
    backStack: NavBackStack<Route>
) {
    val state = musicViewModel.nowPlayingState() ?: return
    NowPlayingBar(state, musicViewModel) { backStack.add(Route.Song) }
}
