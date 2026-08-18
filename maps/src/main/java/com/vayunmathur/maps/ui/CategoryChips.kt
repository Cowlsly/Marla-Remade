package com.vayunmathur.maps.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.PoiCategories

/**
 * One quick category: a display label, the Google search [query] it runs on the
 * search page, and the set of OSM POI [types] ([PoiCategories]) it filters the
 * on-map `ma_pois` layer down to on the browse screen.
 */
data class MapCategory(val labelRes: Int, val query: String, val types: Set<Int>)

/**
 * Shared quick-category definitions, reused by both the browse map screen and
 * the search page so the two stay in lock-step (Vela's `CategoryChips`). On the
 * browse map a chip FILTERS the native `ma_pois` layer to its [MapCategory.types];
 * on the search page it runs the [MapCategory.query] Google search (P3).
 */
val MAP_CATEGORIES: List<MapCategory> = listOf(
    MapCategory(R.string.search_category_restaurants, "restaurants", setOf(0, 2)),
    MapCategory(R.string.search_category_coffee, "coffee", setOf(1)),
    MapCategory(R.string.search_category_gas, "gas station", setOf(6)),
    MapCategory(R.string.search_category_groceries, "groceries", setOf(5)),
    MapCategory(R.string.search_category_hotels, "hotels", setOf(8)),
    MapCategory(R.string.search_category_atms, "atm", setOf(9)),
)

/**
 * Horizontally-scrolling row of quick category chips. [onCategory] is invoked
 * with the tapped [MapCategory] — on browse this toggles the on-map POI filter,
 * on the search page it runs the category's Google query. [selected] is the
 * currently-active category (shown highlighted), or null when none is active.
 */
@Composable
fun CategoryChips(
    onCategory: (MapCategory) -> Unit,
    modifier: Modifier = Modifier,
    selected: MapCategory? = null,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MAP_CATEGORIES.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onCategory(category) },
                label = { Text(stringResource(category.labelRes)) },
            )
        }
    }
}
