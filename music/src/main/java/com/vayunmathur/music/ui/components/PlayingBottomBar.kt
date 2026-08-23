package com.vayunmathur.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel

/** Binds [NowPlayingBar] to the session player; renders nothing while the queue is empty. */
@Composable
fun PlayingBottomBar(
    musicViewModel: MusicViewModel,
    backStack: NavBackStack<Route>,
    modifier: Modifier = Modifier,
) {
    val player by musicViewModel.player.collectAsState()
    val item by musicViewModel.currentMediaItem.collectAsState()
    if (item == null) return
    val connected = player ?: return
    NowPlayingBar(connected, modifier) { backStack.add(Route.Song) }
}
