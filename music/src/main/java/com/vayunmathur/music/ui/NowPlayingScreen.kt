package com.vayunmathur.music.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.ui.IconCast
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.music.platform.CastPlayback
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastPickerContract
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import com.vayunmathur.music.platform.PlaybackSource
import com.vayunmathur.music.platform.classifyLyrics
import com.vayunmathur.music.platform.currentLyricIndex
import com.vayunmathur.music.platform.formatDuration

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
    // track carries none this is Lyrics.None and the overlay says so gracefully.
    val lyrics = remember(state.lyrics) { classifyLyrics(state.lyrics) }
    val currentIndex = remember(lyrics, state.positionMs) {
        (lyrics as? Lyrics.Timed)?.let { currentLyricIndex(it.lines, state.positionMs) } ?: -1
    }

    // RAW SCAFFOLD EXCEPTION: nested now-playing scaffold
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    CastButton(state = state)
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
                        LyricsView(lyrics, currentIndex)
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

/**
 * Puts this track on a television, or stops doing so.
 *
 * Talks to [CastPlayback] directly rather than through [MusicActions], the way YouPipe's player
 * does: the picker is an `ActivityResultContract`, so the launcher has to live in a composable, and
 * routing the session through the ViewModel would buy nothing but a second copy of its state.
 *
 * **All this does is open and close the session.** What plays, and where it starts from, is
 * `CastQueue`'s - it hands the current item over as the cast begins and follows the queue from then
 * on. Issuing a `PLAY_MEDIA` from here as well would make two writers of it and restart the track
 * every time this screen was composed.
 *
 * Absent entirely when Cast is not installed. An icon that only ever opened a store listing would be
 * an advertisement in the middle of a transport bar.
 */
@Composable
private fun CastButton(state: NowPlayingUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val castState by CastPlayback.state.collectAsState()
    val growing by CastPlayback.growing.collectAsState()

    val supported = remember { CastPlayback.support(context) == CastClient.Support.READY }
    if (!supported) return

    val song = state.song

    val picker = rememberLauncherForActivityResult(CastPickerContract()) { connected ->
        if (!connected) return@rememberLauncherForActivityResult
        scope.launch { CastPlayback.open(context) }
    }

    when (castState) {
        is CastPlayback.State.Casting -> IconButton(onClick = { CastPlayback.close() }) {
            // A track still being encoded is the window in which seeking does not work and in which a
            // failure can still appear, so it is worth showing even though playback has started.
            if (growing != null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconCastConnected(tint = MaterialTheme.colorScheme.primary)
            }
        }
        CastPlayback.State.Connecting -> IconButton(onClick = {}) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        CastPlayback.State.Idle -> IconButton(
            onClick = { picker.launch(Unit) },
            // Nothing loaded means nothing to send, and a button that silently did nothing would be
            // worse than one that is plainly unavailable.
            enabled = song != null,
        ) {
            IconCast()
        }
    }
}
