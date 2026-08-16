package com.vayunmathur.maps.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R

/** One quick-search category chip: a display label and the query it runs. */
data class MapCategory(val labelRes: Int, val query: String)

/**
 * Shared quick-category definitions, reused by both the browse map screen and
 * the search page so the two stay in lock-step (Vela's `CategoryChips`). Each
 * chip runs the same Google search query wired up in P3.
 */
val MAP_CATEGORIES: List<MapCategory> = listOf(
    MapCategory(R.string.search_category_restaurants, "restaurants"),
    MapCategory(R.string.search_category_coffee, "coffee"),
    MapCategory(R.string.search_category_gas, "gas station"),
    MapCategory(R.string.search_category_groceries, "groceries"),
    MapCategory(R.string.search_category_hotels, "hotels"),
    MapCategory(R.string.search_category_atms, "atm"),
)

/**
 * Horizontally-scrolling row of quick category chips. [onCategory] is invoked
 * with the chip's Google search query — on browse this opens the search page
 * pre-filled with that query; on the search page it runs the query in place.
 */
@Composable
fun CategoryChips(onCategory: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MAP_CATEGORIES.forEach { category ->
            AssistChip(
                onClick = { onCategory(category.query) },
                label = { Text(stringResource(category.labelRes)) },
            )
        }
    }
}
