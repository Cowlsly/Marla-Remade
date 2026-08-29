package com.vayunmathur.music

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.ZoomPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.MusicTabsScreen
import com.vayunmathur.music.ui.PlaylistDetailScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog

@Composable
fun Navigation(musicViewModel: MusicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            MusicTabsScreen(backStack, musicViewModel)
        }
        entry<Route.Song> {
            SongScreen(backStack, musicViewModel)
        }
        entry<Route.AlbumDetail>(metadata = ZoomPage()) {
            AlbumDetailScreen(backStack, musicViewModel, it.albumId)
        }
        entry<Route.ArtistDetail>(metadata = ZoomPage()) {
            ArtistDetailScreen(backStack, musicViewModel, it.artistId)
        }
        entry<Route.PlaylistDetail>(metadata = ZoomPage()) {
            PlaylistDetailScreen(backStack, musicViewModel, it.playlistId)
        }
        entry<Route.AddToPlaylistDialog>(metadata = DialogPage()) {
            AddToPlaylistDialog(backStack, musicViewModel, it.musicId)
        }
    }
}
