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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.NavBackStack
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

    // Single sync kickoff for all four tabs (the pager composes them lazily, so
    // doing this per-tab would fire the sync multiple times).
    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

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
                0 -> HomeTabContent(backStack, musicViewModel)
                1 -> AlbumsTabContent(backStack, musicViewModel)
                2 -> ArtistsTabContent(backStack, musicViewModel)
                else -> PlaylistsTabContent(backStack, musicViewModel)
            }
        }
        PlayingBottomBar(musicViewModel, backStack)
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
