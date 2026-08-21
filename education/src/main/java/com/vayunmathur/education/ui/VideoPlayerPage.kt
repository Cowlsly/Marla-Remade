package com.vayunmathur.education.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.vayunmathur.education.Route
import com.vayunmathur.education.util.ResolvedStreams
import com.vayunmathur.education.util.VideoExtractor
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

private sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(val streams: ResolvedStreams) : PlayerUiState
    data object Failed : PlayerUiState
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerPage(
    backStack: NavBackStack<Route>,
    youtubeId: String,
    title: String,
) {
    var state by remember(youtubeId) { mutableStateOf<PlayerUiState>(PlayerUiState.Loading) }

    LaunchedEffect(youtubeId) {
        state = try {
            PlayerUiState.Ready(VideoExtractor.resolve(youtubeId))
        } catch (e: Exception) {
            PlayerUiState.Failed
        }
    }

    AppScaffold(
        title = { Text(title, maxLines = 1) },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                PlayerUiState.Loading -> CircularProgressIndicator()
                is PlayerUiState.Ready -> VideoSurface(s.streams)
                PlayerUiState.Failed -> VideoError(youtubeId)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(streams: ResolvedStreams) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(streams) {
        val factory = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
        val source = if (streams.audioUrl == null) {
            factory.createMediaSource(MediaItem.fromUri(streams.videoUrl))
        } else {
            MergingMediaSource(
                factory.createMediaSource(MediaItem.fromUri(streams.videoUrl)),
                factory.createMediaSource(MediaItem.fromUri(streams.audioUrl)),
            )
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
            }
            delay(300)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
            PlayPauseButton(
                player = player,
                modifier = Modifier
                    .align(Alignment.Center),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(DateUtils.formatElapsedTime(position / 1000), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = position.toFloat(),
                onValueChange = { dragging = true; position = it.toLong() },
                onValueChangeFinished = { player.seekTo(position); dragging = false },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(DateUtils.formatElapsedTime(duration / 1000), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VideoError(youtubeId: String) {
    val context = LocalContext.current
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.couldn_t_load_this_video), color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.check_your_connection_or_open_it_in_yout),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
            val uri = "https://www.youtube.com/watch?v=$youtubeId".toUri()
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }) { Text(stringResource(R.string.open_in_youtube)) }
    }
}
