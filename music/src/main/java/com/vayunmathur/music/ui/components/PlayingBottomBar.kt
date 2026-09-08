package com.vayunmathur.music.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel

/**
 * Binds [NowPlayingBar] to the session; renders nothing while the queue is empty.
 *
 * Reads the loaded item and the play/pause flag directly rather than going through
 * `MusicViewModel.nowPlayingState()`, which also carries the playback position and would therefore
 * recompose this bar once a second on every screen it is docked to.
 *
 * [owsSharedKeys] is false only where something else on the same screen is the better origin for
 * the morph into the player - the songs list, when a row was tapped.
 */
@Composable
fun PlayingBottomBar(
    musicViewModel: MusicViewModel,
    backStack: NavBackStack<Route>,
    modifier: Modifier = Modifier,
    owsSharedKeys: Boolean = true,
    onOpen: () -> Unit = { backStack.add(Route.Song) },
) {
    val item by musicViewModel.currentMediaItem.collectAsState()
    val playing by musicViewModel.isPlaying.collectAsState()
    val media = item ?: return
    val metadata = media.mediaMetadata
    NowPlayingBar(
        songId = media.mediaId.toLongOrNull(),
        title = metadata.title?.toString() ?: stringResource(R.string.unknown_title),
        artist = metadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
        artworkUri = metadata.artworkUri,
        isPlaying = playing,
        owsSharedKeys = owsSharedKeys,
        onOpen = onOpen,
        onTogglePlayPause = { musicViewModel.togglePlayPause() },
        onSkipNext = { musicViewModel.skipNext() },
        modifier = modifier,
    )
}
