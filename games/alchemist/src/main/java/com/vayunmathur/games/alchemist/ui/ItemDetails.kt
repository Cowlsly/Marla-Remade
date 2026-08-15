package com.vayunmathur.games.alchemist.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.data.AlchemyRecipe
import com.vayunmathur.games.alchemist.platform.AlchemistViewModel
import com.vayunmathur.games.alchemist.platform.ItemDetailsUiState
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack

/** Binds [AlchemistViewModel] to the stateless [ItemDetailsScreen]. */
@Composable
fun ItemDetailsPage(
    backStack: NavBackStack<Route>,
    viewModel: AlchemistViewModel,
    itemId: Int
) {
    val allItems by viewModel.allItems.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val itemsIds by viewModel.itemIds.collectAsState()

    val item = remember(allItems, itemId) { allItems.firstOrNull { it.id == itemId.toLong() } }

    ItemDetailsScreen(
        state = ItemDetailsUiState(item = item, recipes = recipes, discoveredIds = itemsIds),
        onBack = { backStack.pop() }
    )
}

/**
 * The recipe breakdown for one element, with no dependency on the ViewModel so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsScreen(
    state: ItemDetailsUiState,
    onBack: () -> Unit
) {
    val item = state.item
    val recipes = state.recipes
    val itemsIds = state.discoveredIds

    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(R.string.item_details)) },
            navigationIcon = { IconNavigation(onBack) }
        )
    }) { paddingValues ->
        if (item == null) {
            // Catalog not yet loaded or item id unknown — render an empty surface
            // rather than crashing.
            Column(Modifier.padding(paddingValues)) {}
            return@Scaffold
        }

        Column(
            Modifier
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DynamicAlchemyIcon(
                            iconId = item.id,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.id, item.id),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.padding(8.dp, 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // all recipes that have this item as an output
                    val makeThis = recipes.filter { item.id in it.outputs }
                    stickyHeader {
                        SectionHeader(stringResource(R.string.recipes, makeThis.size))
                    }
                    items(makeThis, key = { "make-${it.inputs.joinToString(",")}->${it.outputs.joinToString(",")}" }) { recipe ->
                        RecipeCard(recipe, isItemDiscovered = { id -> id in itemsIds })
                    }

                    // all recipes that have this item as an input
                    val whereIHaveInput = recipes.filter { item.id in it.inputs }
                    val showers = whereIHaveInput.filter { r -> r.outputs.all { it in itemsIds } }
                    val locked = whereIHaveInput.count { r -> r.outputs.all { it !in itemsIds } }
                    stickyHeader {
                        SectionHeader(stringResource(R.string.used_in, whereIHaveInput.size))
                    }
                    items(showers, key = { "used-${it.inputs.joinToString(",")}->${it.outputs.joinToString(",")}" }) { recipe ->
                        RecipeCard(recipe, isItemDiscovered = { id -> id in itemsIds })
                    }
                    if (locked > 0) {
                        item {
                            RecipeCard(
                                AlchemyRecipe(emptyList(), emptyList()),
                                { false },
                                lockedCount = locked
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeItemIcons(ids: List<Long>, isItemDiscovered: (Long) -> Boolean) {
    ids.forEachIndexed { index, id ->
        if (index > 0) {
            Text("+", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        DynamicAlchemyIcon(iconId = id, undiscovered = !isItemDiscovered(id), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Box(Modifier.padding(2.dp, 2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(4.dp)
        )
    }
}

@Composable
fun RecipeCard(
    recipe: AlchemyRecipe,
    isItemDiscovered: (Long) -> Boolean = { true },
    lockedCount: Int = -1
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (lockedCount > 0) {
                Column {
                    Text(
                        pluralStringResource(R.plurals.locked_recipes, lockedCount, lockedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.locked_description),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                return@Card
            }

            RecipeItemIcons(recipe.inputs, isItemDiscovered)
            Text(
                "=",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            RecipeItemIcons(recipe.outputs, isItemDiscovered)
        }
    }
}
