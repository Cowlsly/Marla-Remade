package com.vayunmathur.maps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.maps.data.AmenityRepository

/**
 * A Search Page that filters amenities based on a text query and a geographic bounding box.
 * Results are dispatched back to the navigation registry upon selection.
 */
@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    idx: Int?,
    east: Double,
    west: Double,
    north: Double,
    south: Double
) {
    val context = LocalContext.current
    val repository = remember(context) { AmenityRepository.get(context) }
    val searchQuery by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()

    val actions = object : SearchActions {
        override fun setQuery(query: String) {
            searchViewModel.setQuery(query, repository, west, east, south, north)
        }

        override fun selectResult(result: SearchResult) {
            // Shared selection path: replace a route waypoint when picking a stop
            // (idx != null), otherwise set the selected feature. Then leave the
            // search page.
            val apply: (SpecificFeature.RoutableFeature) -> Unit = { feature ->
                if (idx != null) {
                    // The selection could have changed (e.g. user navigated away and
                    // back) between launching the resolve and the callback firing.
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
            when (result) {
                is SearchResult.Amenity ->
                    searchViewModel.resolveAmenity(result.entity, repository) { apply(it) }
                is SearchResult.Address ->
                    apply(searchViewModel.addressFeature(result.result))
            }
        }

        override fun back() {
            backStack.pop()
        }
    }

    SearchScreen(SearchUiState(searchQuery, results), actions)
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
                singleLine = true
            )
        },
        onNavigateBack = { actions.back() },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (state.results.isEmpty() && state.query.length >= 2) {
                Text(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (state.query.length < 2) {
                Text(
                    text = stringResource(R.string.type_to_search),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.results, key = { it.key }) { result ->
                        ListItem(
                            content = { Text(result.title.ifBlank { stringResource(R.string.unnamed_amenity) }) },
                            supportingContent = {
                                Text(stringResource(R.string.coordinates, result.lat.round(4), result.lon.round(4)))
                            },
                            modifier = Modifier.clickable { actions.selectResult(result) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
