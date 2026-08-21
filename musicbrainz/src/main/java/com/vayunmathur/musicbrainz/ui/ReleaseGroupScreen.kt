package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.clickable
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
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.CoverArtImage
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.ReleaseGroupUiState

@Composable
fun ReleaseGroupPage(
    backStack: NavBackStack<Route>,
    viewModel: MusicBrainzViewModel,
    releaseGroupId: String,
) {
    LaunchedEffect(releaseGroupId) { viewModel.loadReleaseGroup(releaseGroupId) }
    val state by viewModel.releaseGroup.collectAsStateWithLifecycle()
    ReleaseGroupScreen(state, viewModel, backStack)
}

/**
 * The editions of one album.
 *
 * MusicBrainz models an album as a release group containing many releases - reissues,
 * regional pressings, deluxe editions - and their tracklists genuinely differ, so the
 * choice is the user's rather than a guess.
 */
@Composable
fun ReleaseGroupScreen(
    state: ReleaseGroupUiState,
    actions: com.vayunmathur.musicbrainz.platform.MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    AppScaffold(
        title = state.title.ifBlank { stringResource(R.string.album) },
        backStack = backStack,
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            state.error != null -> ErrorState(
                title = stringResource(R.string.load_failed),
                modifier = Modifier.fillMaxSize().padding(padding),
                message = state.error,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        CoverArtImage(state.coverUrl, size = 160)
                        Text(state.artist, style = MaterialTheme.typography.titleMedium)
                        Text(
                            pluralStringResource(
                                R.plurals.editions_count,
                                state.releases.size,
                                state.releases.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.releases, key = { it.id }) { release ->
                    ListItem(
                        headlineContent = { Text(release.title) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { backStack.add(Route.Release(release.id)) },
                        supportingContent = { SecondaryText(release.subtitle) },
                        leadingContent = { CoverArtImage(release.coverUrl, fallbackUrl = release.fallbackCoverUrl) },
                        trailingContent = if (release.onDevice) {
                            { IconCheckCircle(tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
