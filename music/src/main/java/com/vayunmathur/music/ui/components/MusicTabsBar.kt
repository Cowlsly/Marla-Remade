package com.vayunmathur.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.music.R

/**
 * The four-tab bar. Split out of [com.vayunmathur.music.ui.MusicTabsScreen] so it takes a plain index plus a
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
