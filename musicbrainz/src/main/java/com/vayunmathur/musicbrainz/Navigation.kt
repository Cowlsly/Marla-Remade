package com.vayunmathur.musicbrainz

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.ui.ArtistPage
import com.vayunmathur.musicbrainz.ui.DownloadsPage
import com.vayunmathur.musicbrainz.ui.ReleaseGroupPage
import com.vayunmathur.musicbrainz.ui.ReleasePage
import com.vayunmathur.musicbrainz.ui.SearchPage
import com.vayunmathur.musicbrainz.ui.SettingsPage
import com.vayunmathur.musicbrainz.ui.TidalLoginPage

@Composable
fun Navigation(viewModel: MusicBrainzViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Search)
    MainNavigation(backStack) {
        entry<Route.Search> { SearchPage(backStack, viewModel) }
        entry<Route.Artist> { ArtistPage(backStack, viewModel, it.artistId) }
        entry<Route.ReleaseGroup> { ReleaseGroupPage(backStack, viewModel, it.releaseGroupId) }
        entry<Route.Release> { ReleasePage(backStack, viewModel, it.releaseId) }
        entry<Route.Downloads> { DownloadsPage(backStack, viewModel) }
        entry<Route.Settings> { SettingsPage(backStack, viewModel) }
        entry<Route.TidalLogin> { TidalLoginPage(backStack, viewModel) }
    }
}
