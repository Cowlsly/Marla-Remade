package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastPlayerState
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.IconVolumeOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text

/**
 * Transport and volume for whatever is on the receiver.
 *
 * The scrub bar is driven locally while the thumb is held and only committed on release: the
 * receiver's position is polled once a second, so following it live would fight the user's
 * finger. Volume is the receiver's own volume, not the phone's.
 */
@Composable
fun CastNowPlayingCard(
    state: CastUiState,
    actions: CastActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.title ?: stringResource(R.string.cast_nothing_playing),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(playerStateLabel(state.playerState)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScrubBar(state, actions)
            TransportRow(state, actions)
            VolumeRow(state, actions)
        }
    }
}

@Composable
private fun ScrubBar(state: CastUiState, actions: CastActions) {
    val duration = state.durationSec
    // A live stream has no end, so there is nothing to scrub along and the position alone is
    // the only honest thing to show.
    if (duration == null || duration <= 0.0) {
        Text(formatTime(state.positionSec), style = MaterialTheme.typography.bodySmall)
        return
    }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing ?: state.positionSec.toFloat()
    Slider(
        value = shown.coerceIn(0f, duration.toFloat()),
        onValueChange = { scrubbing = it },
        onValueChangeFinished = {
            scrubbing?.let { actions.seek(it.toDouble()) }
            scrubbing = null
        },
        valueRange = 0f..duration.toFloat(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shown.toDouble()), style = MaterialTheme.typography.bodySmall)
        Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TransportRow(state: CastUiState, actions: CastActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.playerState == CastPlayerState.Playing) {
            IconButton(onClick = actions::pause) { IconPause() }
        } else {
            IconButton(onClick = actions::play, enabled = state.hasMedia) { IconPlay() }
        }
        IconButton(onClick = actions::stopPlayback, enabled = state.hasMedia) { IconStop() }
    }
}

@Composable
private fun VolumeRow(state: CastUiState, actions: CastActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.setMuted(!state.muted) }) {
            if (state.muted) IconVolumeOff() else IconVolumeUp()
        }
        Slider(
            value = state.volumeLevel.toFloat(),
            onValueChange = { actions.setVolume(it.toDouble()) },
            enabled = !state.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun playerStateLabel(playerState: CastPlayerState): Int = when (playerState) {
    CastPlayerState.Buffering -> R.string.cast_state_buffering
    CastPlayerState.Playing -> R.string.cast_state_playing
    CastPlayerState.Paused -> R.string.cast_state_paused
    CastPlayerState.Idle -> R.string.cast_state_idle
}

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
