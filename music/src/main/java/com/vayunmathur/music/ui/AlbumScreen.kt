package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album

@Composable
fun AlbumsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val albums by musicViewModel.albums.collectAsState()

    ListPage<Album, Route, Route.Song>(backStack, albums, stringResource(R.string.page_title_music), { Text(it.name) }, {
        Text(it.artistString(musicViewModel))
    }, {
        Route.AlbumDetail(it)
    }, leadingContent = { album ->
        AlbumArt(album.uri.toUri(), Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(musicViewModel)
    }, sortOrder = Comparator.comparing { it.name }, scrollBehavior = appBarScrollBehavior())
}
