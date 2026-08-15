package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Artist

@Composable
fun ArtistsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val artists by musicViewModel.artists.collectAsState()

    ListPage<Artist, Route, Route.Song>(backStack, artists, stringResource(R.string.page_title_music), { Text(it.name) }, {
    }, {
        Route.ArtistDetail(it)
    }, leadingContent = { artist ->
        val albumIds by musicViewModel.matchedAlbumsForArtist(artist.id)
        val allAlbums by musicViewModel.albums.collectAsState()
        val albumsUris by remember { derivedStateOf { allAlbums.filter { it.id in albumIds }.map { it.uri.toUri() } } }
        AlbumArt(albumsUris, Modifier.size(40.dp))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(musicViewModel)
    }, sortOrder = Comparator.comparing { it.name })
}
