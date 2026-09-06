package com.vayunmathur.nowplaying.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconMusicNote
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SectionHeader
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.nowplaying.R
import com.vayunmathur.nowplaying.data.DetectionEvent
import com.vayunmathur.nowplaying.platform.DetectionStatus
import com.vayunmathur.nowplaying.platform.NowPlayingActions
import com.vayunmathur.nowplaying.platform.NowPlayingUiState
import java.util.Date
import kotlin.math.roundToInt

/** Current detection state, the listening toggle, and the log of what has been heard. */
@Composable
fun ListenScreen(state: NowPlayingUiState, actions: NowPlayingActions) {
    LazyListScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            if (state.history.isNotEmpty()) {
                IconButton(onClick = actions.onClearHistory) { IconDelete() }
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item { StatusCard(state) }

        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.listen_toggle),
                    supportingText = stringResource(R.string.listen_toggle_hint),
                    checked = state.listening,
                    onCheckedChange = actions.onListeningChange,
                    enabled = state.status != DetectionStatus.Unavailable,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.history_header)) }

        if (state.history.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.history_empty),
                    message = stringResource(R.string.history_empty_hint),
                    icon = { IconHistory() },
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .fillParentMaxHeight(HISTORY_EMPTY_FRACTION),
                )
            }
        } else {
            items(state.history, key = { it.id }) { HistoryRow(it) }
        }
    }
}

private const val HISTORY_EMPTY_FRACTION = 0.5f

@Composable
private fun StatusCard(state: NowPlayingUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.status) {
                DetectionStatus.Music -> IconMusicNote()
                DetectionStatus.Silent -> IconMic()
                DetectionStatus.Idle, DetectionStatus.Unavailable -> IconMicOff()
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        when (state.status) {
                            DetectionStatus.Music -> R.string.status_music
                            DetectionStatus.Silent -> R.string.status_silent
                            DetectionStatus.Idle -> R.string.status_idle
                            DetectionStatus.Unavailable -> R.string.status_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        when (state.status) {
                            DetectionStatus.Music -> R.string.status_music_hint
                            DetectionStatus.Silent -> R.string.status_silent_hint
                            DetectionStatus.Idle -> R.string.status_idle_hint
                            DetectionStatus.Unavailable -> R.string.status_unavailable_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(event: DetectionEvent) {
    val context = LocalContext.current
    // Follows the user's locale and their 12/24-hour setting rather than a hardcoded pattern.
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val dateFormat = android.text.format.DateFormat.getDateFormat(context)
    val start = timeFormat.format(Date(event.startedAt))
    val headline = event.endedAt?.let {
        stringResource(R.string.history_range, start, timeFormat.format(Date(it)))
    } ?: stringResource(R.string.history_range_ongoing, start)

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.history_supporting,
                    dateFormat.format(Date(event.startedAt)),
                    (event.peakConfidence * PERCENT).roundToInt(),
                ),
            )
        },
        leadingContent = { IconMusicNote() },
    )
}

private const val PERCENT = 100
