package com.vayunmathur.maps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.R
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchPhase
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel

/**
 * Google-only search page (amenities.db removed, Decision D2): a text query
 * biased toward the map centre returns Google places, shown Vela-style with
 * quick category chips and a recent-searches list before any text is typed.
 * Results are dispatched back to the navigation registry on selection.
 */
@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    idx: Int?,
    east: Double,
    west: Double,
    north: Double,
    south: Double,
    query: String? = null,
) {
    val searchQuery by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()
    val recents by searchViewModel.recents.collectAsState()
    val searching by searchViewModel.searching.collectAsState()
    val savedHome by savedPlacesViewModel.home.collectAsState()
    val savedWork by savedPlacesViewModel.work.collectAsState()

    // Bias the Google search toward the centre of the visible viewport.
    val nearLat = (north + south) / 2.0
    val nearLon = (east + west) / 2.0

    // Pre-fill from a browse-screen category chip tap (Route carries the query).
    LaunchedEffect(query) {
        if (!query.isNullOrBlank()) {
            searchViewModel.setQuery(query, nearLat, nearLon)
        }
    }

    val actions = remember(nearLat, nearLon, idx) {
        object : SearchActions {
            override fun setQuery(query: String) {
                searchViewModel.setQuery(query, nearLat, nearLon)
            }

            override fun clearRecents() {
                searchViewModel.clearRecents()
            }

            override fun selectSavedPlace(place: SavedPlace) {
                // Mirror the old map-overlay chip's showSavedPlace: select the
                // saved place and return to the map, which recenters onto it.
                viewModel.set(place.toFeature())
                backStack.pop()
            }

            override fun selectResult(result: SearchResult) {
                searchViewModel.recordRecent(result.title)
                // Shared selection path: replace a route waypoint when picking a
                // stop (idx != null), otherwise set the selected feature. Then
                // leave the search page.
                val feature = searchViewModel.toFeature(result)
                if (idx != null) {
                    // The selection could have changed (e.g. user navigated away
                    // and back) between opening search and picking a result.
                    // Tolerate a non-Route current selection rather than crashing.
                    val current = viewModel.selectedFeature.value
                    if (current is SpecificFeature.Route) {
                        viewModel.set(current.copy(waypoints = current.waypoints.mapIndexed { idx2, it ->
                            if (idx2 == idx) feature else it
                        }))
                    } else {
                        viewModel.set(feature)
                    }
                } else {
                    viewModel.set(feature)
                }
                backStack.pop()
            }

            override fun pickContactAddress(address: String) {
                // Run the P3 Google search for the picked address and auto-select
                // the first hit through the shared selectResult path.
                searchViewModel.searchAndSelectFirst(address, nearLat, nearLon) { first ->
                    if (first != null) selectResult(first)
                }
            }

            override fun back() {
                backStack.pop()
            }
        }
    }

    SearchScreen(
        SearchUiState(searchQuery, results, recents, savedHome, savedWork, searching),
        actions,
    )
}

/** The rendered half of [SearchPage]: query text in, results out, no ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchUiState, actions: SearchActions) {
    AppScaffold(
        title = {
            TextField(
                value = state.query,
                onValueChange = { query -> actions.setQuery(query) },
                placeholder = { Text(stringResource(R.string.search_nearby)) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
                leadingIcon = { IconSearch() },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAddressButton(onAddress = { actions.pickContactAddress(it) })
                        VoiceSearchButton(onResult = { actions.setQuery(it) })
                    }
                },
                singleLine = true
            )
        },
        onNavigateBack = { actions.back() },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            CategoryChips(
                onCategory = { actions.setQuery(it.query) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Home/Work quick-access chips (P24: moved here from the map browse
            // overlay). Tapping a set slot selects it and returns to the map; an
            // unset slot is a no-op here (the user is already in search).
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { state.savedHome?.let { actions.selectSavedPlace(it) } },
                    label = {
                        Text(stringResource(if (state.savedHome != null) R.string.saved_place_home else R.string.set_home))
                    },
                    leadingIcon = { IconHome(Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { state.savedWork?.let { actions.selectSavedPlace(it) } },
                    label = {
                        Text(stringResource(if (state.savedWork != null) R.string.saved_place_work else R.string.set_work))
                    },
                    leadingIcon = { IconWork(Modifier.size(18.dp)) },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.phase) {
                    SearchPhase.Searching -> LoadingState(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    SearchPhase.Empty -> EmptyState(
                        title = stringResource(R.string.no_results_found),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    SearchPhase.Results -> ResultsList(state.results, actions)
                    SearchPhase.Recents -> RecentsList(state.recents, actions)
                    SearchPhase.Idle -> Text(
                        text = stringResource(R.string.type_to_search),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsList(results: List<SearchResult>, actions: SearchActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(results, key = { it.id }) { result ->
            ListItem(
                content = { Text(result.title) },
                supportingContent = {
                    Text(
                        result.subtitle
                            ?: stringResource(R.string.coordinates, result.lat.round(4), result.lon.round(4))
                    )
                },
                modifier = Modifier.clickable { actions.selectResult(result) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun RecentsList(recents: List<String>, actions: SearchActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent_searches),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.clear_recents),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { actions.clearRecents() },
                )
            }
        }
        items(recents, key = { it }) { recent ->
            ListItem(
                content = { Text(recent) },
                leadingContent = { IconHistory() },
                modifier = Modifier.clickable { actions.setQuery(recent) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
