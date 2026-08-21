package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconScreenShare
import com.vayunmathur.library.ui.IconVolumeOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text

/**
 * Mirroring status and the one control that matters: start or stop.
 *
 * Replaces the media receiver's now-playing card. Volume stays because it is the receiver's own
 * volume and works whether or not anything is being mirrored.
 */
@Composable
fun CastMirrorStatusCard(
    state: CastUiState,
    actions: CastActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(statusLabel(state)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.mirrorPhase == MirrorPhase.Negotiating) {
                    CircularProgressIndicator()
                }
            }
            // Both degradations are worth saying out loud: a silent audio stream or a
            // video-only cast otherwise just looks broken.
            if (state.audioDegraded) {
                Notice(stringResource(R.string.cast_mirror_audio_degraded))
            }
            if (state.videoDegraded) {
                Notice(stringResource(R.string.cast_mirror_video_degraded))
            }
            state.failure?.let { Notice(it, isError = true) }
            if (state.isMirroring || state.mirrorPhase == MirrorPhase.Negotiating) {
                OutlinedButton(
                    onClick = actions::stopMirroring,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cast_stop_mirroring))
                }
            } else {
                Button(
                    onClick = actions::startMirroring,
                    enabled = state.canMirror,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconScreenShare()
                    Text(
                        stringResource(R.string.cast_start_mirroring),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            VolumeRow(state, actions)
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
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
            modifier = Modifier.weight(1f),
        )
    }
}

private fun statusLabel(state: CastUiState): Int = when {
    state.isMirroring && state.audioOnly -> R.string.cast_status_casting_audio
    state.isMirroring -> R.string.cast_status_mirroring
    state.mirrorPhase == MirrorPhase.Negotiating -> R.string.cast_status_starting
    state.mirrorPhase == MirrorPhase.Failed -> R.string.cast_status_failed
    state.audioOnly -> R.string.cast_status_ready_audio
    else -> R.string.cast_status_ready
}
