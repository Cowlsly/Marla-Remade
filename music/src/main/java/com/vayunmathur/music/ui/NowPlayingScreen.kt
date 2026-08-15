package com.vayunmathur.music.ui

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRepeat
import com.vayunmathur.library.ui.IconRepeatOne
import com.vayunmathur.library.ui.IconShuffle
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.NowPlayingUiState
import com.vayunmathur.music.util.PlaybackSource
import com.vayunmathur.music.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
) {
    // UI States
    var showLyrics by remember { mutableStateOf(false) }
    // Lyrics are read from the playing file's embedded tags (see EmbeddedLyrics); when a
    // track carries none this is empty and the overlay shows "no lyrics" gracefully.
    val rawLyrics = state.lyrics

    val parsedLyrics = remember(rawLyrics) { parseLyrics(rawLyrics) }
    val currentLyricIndex = remember(parsedLyrics, state.positionMs) {
        parsedLyrics.indexOfLast { it.timestamp <= state.positionMs }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    val sourceName = state.sourceName
                    if (state.sourceId != null && sourceName != null) {
                        TextButton(onClick = {
                            when (val src = PlaybackSource.parse(state.sourceId)) {
                                PlaybackSource.AllSongs -> backStack.reset(Route.Home)
                                is PlaybackSource.Album -> backStack.reset(Route.Home, Route.AlbumDetail(src.albumId))
                                is PlaybackSource.Playlist -> backStack.reset(Route.Home, Route.PlaylistDetail(src.playlistId))
                                is PlaybackSource.Artist -> backStack.reset(Route.Home, Route.ArtistDetail(src.artistId))
                                null -> {}
                            }
                        }) {
                            Text(
                                stringResource(R.string.go_to_source, sourceName),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Toggleable Album Art / Lyrics Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { showLyrics = !showLyrics }
            ) {
                Crossfade(targetState = showLyrics, label = "LyricsToggle") { isShowingLyrics ->
                    if (isShowingLyrics) {
                        LyricsView(parsedLyrics, currentLyricIndex)
                    } else {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(12.dp)
                        ) {
                            AlbumArt(state.artworkUri, Modifier.fillMaxSize())
                        }
                    }
                }
            }

            // Song Info
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val artistId = state.artistId
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (artistId != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (artistId != null) {
                            Modifier.clickable { backStack.add(Route.ArtistDetail(artistId)) }
                        } else {
                            Modifier
                        },
                    )
                    val albumId = state.albumId
                    if (state.album.isNotEmpty()) {
                        Text(
                            state.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (albumId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (albumId != null) {
                                Modifier.clickable { backStack.add(Route.AlbumDetail(albumId)) }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                IconButton(onClick = {}) {
                    IconMoreVert(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Progress Slider
            Column {
                Slider(
                    value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f,
                    onValueChange = { actions.seekTo((it * state.durationMs).toLong()) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(state.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(state.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { actions.toggleRepeat() }) {
                    val repeatTint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> IconRepeatOne(tint = repeatTint)
                        else -> IconRepeat(tint = repeatTint)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.skipPrevious() }) {
                        IconSkipPrevious(Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { actions.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        val tint = MaterialTheme.colorScheme.onPrimaryContainer
                        if (state.isPlaying) IconPause(tint = tint) else IconPlay(tint = tint)
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { actions.skipNext() }) {
                        IconSkipNext(Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                IconButton(onClick = { actions.toggleShuffle() }) {
                    IconShuffle(tint = if (state.shuffle) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Parses LRC lyric content. Public so [LyricsView] tests can reuse it if needed. */
fun parseLyrics(lrcContent: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    // Regex to match [mm:ss.xx] text
    val lyricPattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")

    lrcContent.lines().forEach { line ->
        try {
            val match = lyricPattern.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val ms = match.groupValues[3].toLong()
                val text = match.groupValues[4].trim()

                // Convert to total milliseconds
                val timestamp = (min * 60 * 1000) + (sec * 1000) + (if (match.groupValues[3].length == 2) ms * 10 else ms)
                if (text.isNotEmpty()) {
                    lines.add(LyricLine(timestamp, text))
                }
            }
        } catch (e: Exception) {
            Log.e("SongScreen", "Error parsing lyric line: $line", e)
        }
    }
    return lines.sortedBy { it.timestamp }
}
