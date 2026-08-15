package com.vayunmathur.music.ui

import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.SyncWorker

/**
 * Hosts the four main tabs (Songs / Albums / Artists / Playlists) in a swipeable
 * pager, with the now-playing controls and the tab bar pinned across all four.
 *
 * Tab selection lives in the pager's own state (owned by [TabbedPagerScaffold]),
 * NOT in the nav backstack - so deep navigation (tap an album → AlbumDetail →
 * back) returns the user to whatever tab they were on, scroll position intact.
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

    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_home), { IconLibraryMusic() }) {
            HomeTabContent(backStack, musicViewModel)
        },
        PagerTab(stringResource(R.string.nav_albums), { IconAlbum() }) {
            AlbumsTabContent(backStack, musicViewModel)
        },
        PagerTab(stringResource(R.string.nav_artists), { IconPerson() }) {
            ArtistsTabContent(backStack, musicViewModel)
        },
        PagerTab(stringResource(R.string.nav_playlists), { IconLibraryMusic() }) {
            PlaylistsTabContent(backStack, musicViewModel)
        },
    )

    TabbedPagerScaffold(
        tabs = tabs,
        tabStyle = TabStyle.BottomNav,
        leadingBottomBar = { PlayingBottomBar(musicViewModel, backStack) },
    )
}

/**
 * The four-tab bar. Split out of [MusicTabsScreen] so it takes a plain index plus a
 * callback rather than reaching into the pager — which is what lets the store-listing
 * previews render it without one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicTabsBar(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    val tabs = listOf<Triple<String, @Composable () -> Unit, Int>>(
        Triple(stringResource(R.string.nav_home), { IconLibraryMusic() }, 0),
        Triple(stringResource(R.string.nav_albums), { IconAlbum() }, 1),
        Triple(stringResource(R.string.nav_artists), { IconPerson() }, 2),
        Triple(stringResource(R.string.nav_playlists), { IconLibraryMusic() }, 3),
    )

    // Tabs are a selected index here, so this uses the content slot rather
    // than the back-stack overload of BottomNavBar.
    BottomNavBar {
        tabs.forEach { (name, icon, index) ->
            BottomNavBarItem(
                selected = selectedTab == index,
                onClick = { onSelectTab(index) },
                icon = icon,
                label = name,
            )
        }
    }
}
