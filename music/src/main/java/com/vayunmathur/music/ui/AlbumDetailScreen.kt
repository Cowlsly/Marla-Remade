package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Music
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.AlbumDetailUiState
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.R
import com.vayunmathur.music.util.formatDuration

/** Binds [AlbumDetailContent] to the ViewModel. */
@Composable
fun AlbumDetailScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel, albumId: Long) {
    val albumValue by musicViewModel.albumState(albumId)
    val album = albumValue ?: return
    val allMusic by musicViewModel.music.collectAsState()
    val musicInAlbum = remember(allMusic, albumId) {
        allMusic.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
    }
    val totalDurationMs = remember(musicInAlbum) {
        musicInAlbum.sumOf { it.duration }
    }

    val albumYear = remember(musicInAlbum) {
        val extractedYear = musicInAlbum.firstOrNull()?.year ?: 0
        if (extractedYear > 0) extractedYear.toString() else "Unknown Year"
    }

    val artistIds by musicViewModel.matchedArtistsForAlbum(albumId)

    AlbumDetailContent(
        state = AlbumDetailUiState(
            albumId = albumId,
            name = album.name,
            artUri = album.uri.toUri(),
            artistName = album.artistString(musicViewModel),
            // Only a single-artist album has an unambiguous page to open.
            artistId = artistIds.singleOrNull(),
            // Artist is rendered separately (as a tappable link), so it's passed empty here
            // and the format's leading artist line is trimmed off.
            info = stringResource(
                R.string.album_info_format,
                "",
                albumYear,
                musicInAlbum.size,
                formatDuration(totalDurationMs),
            ).trimStart(),
            tracks = musicInAlbum,
            playingSongId = musicViewModel.playingSongIdFrom("album_$albumId"),
        ),
        actions = musicViewModel,
        backStack = backStack,
        bottomBar = { PlayingBottomBar(musicViewModel, backStack) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailContent(
    state: AlbumDetailUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
    bottomBar: @Composable () -> Unit = {},
) {
    val sourceId = "album_${state.albumId}"

    DetailLazyColumn(
        title = {},
        onNavigateBack = { backStack.pop() },
        bottomBar = bottomBar,
    ) {
            // Header: Album Art
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumArt(state.artUri, Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(state.name, style = MaterialTheme.typography.titleLarge)
                    }, Modifier, {Text(stringResource(R.string.label_album))}, {
                        Column {
                            if (state.artistName.isNotEmpty()) {
                                val artistId = state.artistId
                                Text(
                                    state.artistName,
                                    color = if (artistId != null) MaterialTheme.colorScheme.primary
                                    else Color.Unspecified,
                                    modifier = if (artistId != null) {
                                        Modifier.clickable { backStack.add(Route.ArtistDetail(artistId)) }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                            Text(state.info)
                        }
                    })
                }
            }

            // Action Buttons
            item {
                PlayShuffleRow(
                    onPlay = {
                        actions.playSong(state.tracks, 0, sourceId = sourceId, sourceName = state.name)
                    },
                    onShuffle = {
                        actions.playShuffled(state.tracks, sourceId = sourceId, sourceName = state.name)
                    },
                )
            }

            // Track List Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.label_songs),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Track Items
            itemsIndexed(state.tracks) { idx, music ->
                val isPlaying = music.id == state.playingSongId
                TrackListItem(
                    title = music.title,
                    isPlaying = isPlaying,
                    artUri = music.uri.toUri(),
                    onClick = {
                        actions.playSong(state.tracks, idx, sourceId = sourceId, sourceName = state.name)
                    },
                    leading = {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            if (isPlaying) {
                                IconPlay(
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = music.trackNumber.toString(),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(28.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(music.duration))
                            AddToPlaylistButton(backStack, music)
                        }
                    },
                )
            }
    }
}
