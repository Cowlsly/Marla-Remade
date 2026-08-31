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
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.CoverArtImage
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.platform.ArtistUiState
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel

@Composable
fun ArtistPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel, artistId: String) {
    LaunchedEffect(artistId) { viewModel.loadArtist(artistId) }
    val state by viewModel.artist.collectAsStateWithLifecycle()
    ArtistScreen(state, viewModel, backStack, sharedTextKey = "mb-artist-name-$artistId")
}

/** An artist's discography, newest first. */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
    /** Pairs the heading with the search row this artist was opened from. */
    sharedTextKey: Any? = null,
) {
    AppScaffold(
        title = {
            Text(
                state.name.ifBlank { stringResource(R.string.artist) },
                modifier = if (sharedTextKey == null) Modifier else Modifier.sharedText(sharedTextKey),
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            state.error != null -> ErrorState(
                title = stringResource(R.string.load_failed),
                modifier = Modifier.fillMaxSize().padding(padding),
                message = state.error,
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
                        headlineContent = {
                            Text(group.title, modifier = Modifier.sharedText("mb-release-group-title-${group.id}"))
                        },
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
