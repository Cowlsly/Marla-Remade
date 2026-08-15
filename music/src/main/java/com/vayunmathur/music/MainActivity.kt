package com.vayunmathur.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.MusicTabsScreen
import com.vayunmathur.music.ui.PlaylistDetailScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog
import kotlinx.serialization.Serializable
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.MusicViewModelFactory
import com.vayunmathur.music.platform.PlaybackManager

class MainActivity : ComponentActivity() {
    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModelFactory(application, MusicRepository.get(application), PlaybackManager.getInstance(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val (permissions, message) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO) to getString(R.string.grant_audio_permissions)
                else
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) to getString(R.string.grant_storage_permissions)
                PermissionsChecker(permissions, message) {
                    Navigation(musicViewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data object Song: Route

    @Serializable
    data class AlbumDetail(val albumId: Long): Route

    @Serializable
    data class ArtistDetail(val artistId: Long): Route

    @Serializable
    data class PlaylistDetail(val playlistId: Long): Route

    @Serializable
    data class AddToPlaylistDialog(val musicId: Long): Route
}

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
        entry<Route.AlbumDetail> {
            AlbumDetailScreen(backStack, musicViewModel, it.albumId)
        }
        entry<Route.ArtistDetail> {
            ArtistDetailScreen(backStack, musicViewModel, it.artistId)
        }
        entry<Route.PlaylistDetail> {
            PlaylistDetailScreen(backStack, musicViewModel, it.playlistId)
        }
        entry<Route.AddToPlaylistDialog>(metadata = DialogPage()) {
            AddToPlaylistDialog(backStack, musicViewModel, it.musicId)
        }
    }
}
