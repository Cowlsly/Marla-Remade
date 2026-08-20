package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.CoverArtImage
import com.vayunmathur.musicbrainz.ui.components.LoadFailureState
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.platform.ArtistUiState
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel

@Composable
fun ArtistPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel, artistId: String) {
    LaunchedEffect(artistId) { viewModel.loadArtist(artistId) }
    val state by viewModel.artist.collectAsStateWithLifecycle()
    ArtistScreen(state, viewModel, backStack)
}

/** An artist's discography, newest first. */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    AppScaffold(
        title = state.name.ifBlank { stringResource(R.string.artist) },
        backStack = backStack,
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            state.error != null -> LoadFailureState(
                error = state.error,
                notReady = state.notReady,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            state.releaseGroups.isEmpty() -> EmptyState(
                title = stringResource(R.string.no_releases),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                state.subtitle?.let { subtitle ->
                    item {
                        Text(
                            subtitle,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.releaseGroups, key = { it.id }) { group ->
                    ListItem(
                        headlineContent = { Text(group.title) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { backStack.add(Route.ReleaseGroup(group.id)) },
                        supportingContent = { SecondaryText(group.subtitle) },
                        leadingContent = { CoverArtImage(group.coverUrl) },
                    )
                }
            }
        }
    }
}
