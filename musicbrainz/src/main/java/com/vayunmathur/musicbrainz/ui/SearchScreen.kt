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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.SearchBarInputField
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.CoverArtImage
import com.vayunmathur.musicbrainz.ui.components.durationLabel
import com.vayunmathur.musicbrainz.ui.components.TrackTrailing
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.SearchTab
import com.vayunmathur.musicbrainz.platform.SearchUiState

@Composable
fun SearchPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel) {
    val state by viewModel.search.collectAsStateWithLifecycle()
    SearchScreen(state, viewModel, backStack)
}

/**
 * The app's entry point: a MusicBrainz search across the three entity types worth
 * browsing for music.
 *
 * Releases lead because that is what people look for; artists and recordings are the
 * other two ways into the same catalogue.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton({ backStack.add(Route.Downloads) }) { IconDownload() }
            IconButton({ backStack.add(Route.Settings) }) { IconSettings() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The input field rather than CommonSearchBar: that one filters a local list
            // as you type, and every keystroke here would be a network request. This one
            // submits on the keyboard's search key.
            SearchBarInputField(
                query = state.query,
                onQueryChange = actions::onQueryChange,
                onSearch = { actions.search() },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { IconSearch() },
                trailingIcon = if (state.query.isNotEmpty()) {
                    { IconButton({ actions.onQueryChange("") }) { IconClose() } }
                } else {
                    null
                },
            )
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                SearchTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { actions.onTabChange(tab) },
                        text = { Text(stringResource(tab.labelRes())) },
                    )
                }
            }
            when {
                state.loading -> LoadingState(Modifier.fillMaxSize())
                state.error != null -> ErrorState(
                    title = stringResource(R.string.search_failed),
                    modifier = Modifier.fillMaxSize(),
                    message = state.error,
                    retryLabel = stringResource(R.string.retry),
                    onRetry = actions::search,
                )
                !state.hasSearched -> EmptyState(
                    title = stringResource(R.string.search_prompt_title),
                    modifier = Modifier.fillMaxSize(),
                    message = stringResource(R.string.search_prompt_message),
                    icon = { IconSearch() },
                )
                else -> SearchResults(state, actions, backStack)
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    val isEmpty = when (state.tab) {
        SearchTab.Artists -> state.artists.isEmpty()
        SearchTab.Releases -> state.releaseGroups.isEmpty()
        SearchTab.Recordings -> state.recordings.isEmpty()
    }
    if (isEmpty) {
        EmptyState(
            title = stringResource(R.string.no_results),
            modifier = Modifier.fillMaxSize(),
            message = stringResource(R.string.no_results_message),
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        when (state.tab) {
            SearchTab.Artists -> items(state.artists, key = { it.id }) { artist ->
                ListItem(
                    headlineContent = { Text(artist.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.Artist(artist.id)) },
                    supportingContent = { SecondaryText(artist.subtitle) },
                    leadingContent = { IconPerson() },
                )
            }
            SearchTab.Releases -> items(state.releaseGroups, key = { it.id }) { group ->
                ListItem(
                    headlineContent = { Text(group.title) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.ReleaseGroup(group.id)) },
                    supportingContent = {
                        SecondaryText(
                            listOfNotNull(group.artist.ifBlank { null }, group.subtitle)
                                .joinToString(" \u00B7 "),
                        )
                    },
                    leadingContent = { CoverArtImage(group.coverUrl) },
                )
            }
            SearchTab.Recordings -> items(state.recordings, key = { it.id }) { recording ->
                ListItem(
                    headlineContent = { Text(recording.title) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingContent = {
                        SecondaryText(
                            listOfNotNull(
                                recording.artist.ifBlank { null },
                                recording.album,
                                durationLabel(recording.durationMs).ifBlank { null },
                            ).joinToString(" \u00B7 "),
                        )
                    },
                    leadingContent = { IconLibraryMusic() },
                    trailingContent = {
                        TrackTrailing(
                            onDevice = recording.onDevice,
                            download = null,
                            onDownload = { actions.downloadRecording(recording) },
                            onCancel = {},
                        )
                    },
                )
            }
        }
    }
}

private fun SearchTab.labelRes(): Int = when (this) {
    SearchTab.Releases -> R.string.tab_releases
    SearchTab.Artists -> R.string.tab_artists
    SearchTab.Recordings -> R.string.tab_recordings
}




