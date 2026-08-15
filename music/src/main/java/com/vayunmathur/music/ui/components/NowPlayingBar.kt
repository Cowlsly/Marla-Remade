package com.vayunmathur.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BottomAppBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.NowPlayingUiState

/** The mini player docked above the tab bar. Tapping anywhere opens the full player. */
@Composable
fun NowPlayingBar(
    state: NowPlayingUiState,
    actions: MusicActions,
    onOpen: () -> Unit,
) {
    val progressFactor =
        if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f

    BottomAppBar(
        Modifier.height(100.dp).invisibleClickable(onOpen)
    ) {
        Column {
            // Progress bar pinned to the top of the bar
            LinearProgressIndicator(
                progress = { progressFactor },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        text = state.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    AlbumArt(state.artworkUri, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { actions.togglePlayPause() }) {
                            if (state.isPlaying) IconPause() else IconPlay()
                        }
                        IconButton(onClick = { actions.skipNext() }) {
                            IconSkipNext()
                        }
                    }
                }
            )
        }
    }
}
