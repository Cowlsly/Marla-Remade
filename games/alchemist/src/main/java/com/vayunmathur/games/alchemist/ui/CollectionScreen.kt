package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.platform.AlchemistViewModel
import com.vayunmathur.games.alchemist.platform.CollectionUiState
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import com.vayunmathur.library.util.NavBackStack

/** Binds [AlchemistViewModel] to the stateless [CollectionScreen]. */
@Composable
fun CollectionPage(
    backStack: NavBackStack<Route>,
    viewModel: AlchemistViewModel
) {
    val availableItems by viewModel.availableItems.collectAsState()
    val allItems by viewModel.allItems.collectAsState()

    CollectionScreen(
        state = CollectionUiState(
            discoveredItems = availableItems,
            totalCount = allItems.size
        ),
        onBack = { backStack.pop() },
        onOpenItemDetails = { backStack.add(Route.ItemDetails(it.toInt())) }
    )
}

/**
 * The discovered-elements grid, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    state: CollectionUiState,
    onBack: () -> Unit,
    onOpenItemDetails: (Long) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.discovered_counter, state.discoveredItems.size, state.totalCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.discoveredItems, key = { it.id }) { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onOpenItemDetails(item.id) }
                    ) {
                        DynamicAlchemyIcon(
                            iconId = item.id,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = item.name,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        TopAppBarOverlay(
            modifier = Modifier.align(Alignment.TopCenter),
            onNavigateBack = onBack,
        )
    }
}
