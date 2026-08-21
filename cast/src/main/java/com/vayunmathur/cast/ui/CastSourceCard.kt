package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.MirroringAppIds
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/**
 * Choosing what to cast: a local file through the system picker, or a link.
 *
 * A chosen source is shown even before a device is picked, because [CastUiState.pendingSource]
 * is cast automatically once a receiver is joined - so the user can start from either end.
 */
@Composable
fun CastSourceCard(
    state: CastUiState,
    actions: CastActions,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pending = state.pendingSource
            if (pending != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pending.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = actions::clearPendingSource) { IconClose() }
                }
            }
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                IconFolder()
                Text(
                    stringResource(R.string.cast_pick_file),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            UrlField(actions)
            SpikeRow(state, actions)
        }
    }
}

/**
 * THROWAWAY (Phase 0): one button per Cast Streaming app id.
 *
 * All four are here because openscreen names two pairs - a desktop/Chrome pair and an Android
 * pair - and which of them tolerates an unregistered sender is exactly what is unknown. The
 * result is in logcat under `CastController`, not on screen; this is a probe, not a feature.
 *
 * Lands in this file deliberately: it is the file Phase 1 deletes, so the throwaway UI goes
 * away with it whichever way the spike lands.
 */
@Composable
private fun SpikeRow(state: CastUiState, actions: CastActions) {
    HorizontalDivider()
    Text(
        stringResource(R.string.cast_spike_heading),
        style = MaterialTheme.typography.titleSmall,
    )
    val device = state.connectedDevice
    if (device == null) {
        Text(
            stringResource(R.string.cast_spike_needs_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    for (appId in MirroringAppIds.all) {
        OutlinedButton(
            onClick = { actions.spikeMirror(appId) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cast_spike_launch, appId))
        }
    }
}

@Composable
private fun UrlField(actions: CastActions) {
    var url by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.cast_cast_url)) },
            placeholder = { Text(stringResource(R.string.cast_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                actions.castUrl(url)
                url = ""
            },
            enabled = url.isNotBlank(),
        ) {
            Text(stringResource(R.string.cast_start))
        }
    }
}
