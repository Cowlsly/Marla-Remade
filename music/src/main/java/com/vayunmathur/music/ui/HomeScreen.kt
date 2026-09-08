package com.vayunmathur.music.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.SongsUiState

/**
 * Binds [SongsScreen] to the ViewModel.
 *
 * [rowOwnsSongKeys] and [onSongTapped] carry the morph-origin decision down from `MusicTabsScreen`,
 * which is the only place that can see both candidates for it - this list and the mini-player.
 */
@Composable
fun HomeTabContent(
    backStack: NavBackStack<Route>,
    musicViewModel: MusicViewModel,
    rowOwnsSongKeys: Boolean = false,
    onSongTapped: () -> Unit = {},
) {
    val music by musicViewModel.music.collectAsState()
    val loaded by musicViewModel.loaded.collectAsState()
    SongsScreen(
        state = SongsUiState(
            songs = music,
            playingSongId = musicViewModel.playingSongIdFrom(SOURCE_ALL_SONGS),
            loading = !loaded,
            rowOwnsSongKeys = rowOwnsSongKeys,
        ),
        actions = musicViewModel,
        backStack = backStack,
        onSongTapped = onSongTapped,
    )
}
