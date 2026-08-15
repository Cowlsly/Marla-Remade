package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.ui.components.PlayShuffleRow
import com.vayunmathur.music.ui.components.TrackListItem
import com.vayunmathur.music.platform.AddToPlaylistButton
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.AlbumDetailUiState
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.formatDuration

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
