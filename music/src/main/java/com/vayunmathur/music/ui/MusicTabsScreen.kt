package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.isNavLeaving
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.SyncWorker
import com.vayunmathur.music.ui.components.MusicTabsBar
import com.vayunmathur.music.ui.components.PlayingBottomBar
import kotlinx.coroutines.launch

/**
 * Hosts the four main tabs (Songs / Albums / Artists / Playlists) in a swipeable
 * pager, with the now-playing controls and the tab bar pinned across all four.
 *
 * A plain [Column] rather than a scaffold: each tab page brings its own, and
 * `MainNavigation` already owns the outer one, so a third would only nest.
 *
 * Tab selection lives in the pager's own state, NOT in the nav backstack - so deep
 * navigation (tap an album → AlbumDetail → back) returns the user to whatever tab
 * they were on, scroll position intact.
 */
@Composable
fun MusicTabsScreen(
    backStack: NavBackStack<Route>,
    musicViewModel: MusicViewModel,
) {
    val context = LocalContext.current

    // Single kickoff for all four tabs (the pager composes them lazily, so doing this per-tab
    // would fire it four times).
    //
    // The refresh runs here rather than being left to WorkManager because this is the first point
    // at which the audio permission is definitely granted - PermissionsChecker gates the whole
    // navigation graph - and because going through the scheduler would put the list behind a job
    // dispatch for no reason. `enqueue` only registers the content-URI triggers that notice later
    // changes to the library.
    LaunchedEffect(Unit) {
        musicViewModel.refreshLibrary()
        SyncWorker.enqueue(context)
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    // Which element the song morph should use as its counterpart.
    //
    // The graph loops here: a row tap carries the song up to the player, but coming back the song
    // belongs in the mini-player, not in the row it left. Both are composed the whole time, so a
    // static key would leave the morph with two origins and no way to choose.
    //
    // Leaving, it is whichever of the two was actually tapped. Arriving - the way back from the
    // player - it is always the bar.
    var tappedSongRow by remember { mutableStateOf(false) }
    val leaving = isNavLeaving()
    val barOwnsSongKeys = !leaving || !tappedSongRow

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // The bars below already cover the navigation bar, so a page's own
            // scaffold must not inset for it again and leave a gap.
            modifier = Modifier
                .weight(1f)
                .consumeWindowInsets(WindowInsets.navigationBars),
        ) { page ->
            when (page) {
                0 -> HomeTabContent(
                    backStack,
                    musicViewModel,
                    rowOwnsSongKeys = leaving && tappedSongRow,
                    onSongTapped = { tappedSongRow = true },
                )
                1 -> AlbumsTabContent(backStack, musicViewModel)
                2 -> ArtistsTabContent(backStack, musicViewModel)
                else -> PlaylistsTabContent(backStack, musicViewModel)
            }
        }
        PlayingBottomBar(
            musicViewModel,
            backStack,
            owsSharedKeys = barOwnsSongKeys,
            onOpen = { tappedSongRow = false; backStack.add(Route.Song) },
        )
        MusicTabsBar(
            selectedTab = pagerState.currentPage,
            onSelectTab = { index ->
                if (pagerState.currentPage != index) {
                    scope.launch { pagerState.animateScrollToPage(index) }
                }
            },
        )
    }
}
