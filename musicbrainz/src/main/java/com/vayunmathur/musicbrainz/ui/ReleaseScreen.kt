package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.CoverArtImage
import com.vayunmathur.musicbrainz.ui.components.LoadFailureState
import com.vayunmathur.musicbrainz.ui.components.durationLabel
import com.vayunmathur.musicbrainz.ui.components.TrackTrailing
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.ReleaseUiState

@Composable
fun ReleasePage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel, releaseId: String) {
    LaunchedEffect(releaseId) { viewModel.loadRelease(releaseId) }
    val state by viewModel.release.collectAsStateWithLifecycle()
    ReleaseScreen(state, viewModel, backStack)
}

/**
 * One release and its tracklist, with what the user already owns marked.
 *
 * The ownership marks come from the tags read off the user's own files, so music that
 * arrived from anywhere - ripped, bought, downloaded elsewhere - counts.
 */
@Composable
fun ReleaseScreen(
    state: ReleaseUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    val missing = state.tracks.size - state.ownedCount
    AppScaffold(
        title = state.title.ifBlank { stringResource(R.string.album) },
        backStack = backStack,
        floatingActionButton = {
            if (missing > 0) {
                ExtendedFloatingActionButton(
                    text = {
                        Text(pluralStringResource(R.plurals.download_missing, missing, missing))
                    },
                    icon = { IconDownload() },
                    onClick = actions::downloadRelease,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            state.error != null -> LoadFailureState(
                error = state.error,
                notReady = state.notReady,
                notReadyReason = state.notReadyReason,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        CoverArtImage(state.coverUrl, size = 160, fallbackUrl = state.fallbackCoverUrl)
                        Text(state.artist, style = MaterialTheme.typography.titleMedium)
                        SecondaryText(state.subtitle)
                        Text(
                            stringResource(
                                R.string.owned_count,
                                state.ownedCount,
                                state.tracks.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(state.tracks, key = { it.rowKey }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingContent = {
                            SecondaryText(
                                listOfNotNull(
                                    track.artist.ifBlank { null },
                                    durationLabel(track.durationMs).ifBlank { null },
                                    track.download?.error,
                                ).joinToString(" \u00B7 "),
                            )
                        },
                        leadingContent = {
                            Text(
                                track.position.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            TrackTrailing(
                                onDevice = track.onDevice,
                                download = track.download,
                                onDownload = { actions.downloadTrack(track) },
                                onCancel = { actions.cancelDownload(track.downloadKey(state.id, state.title)) },
                            )
                        },
                    )
                }
            }
        }
    }
}




