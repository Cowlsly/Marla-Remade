package com.vayunmathur.games.alchemist.platform

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.data.AlchemyRecipe

/**
 * The UI contract between [AlchemistViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements these interfaces.
 */

/** Everything the crafting board draws. */
data class HomeUiState(
    val placedItems: List<PlacedItem> = emptyList(),
    val paletteItems: List<AlchemyItem> = emptyList(),
    val discoveredCount: Int = 0,
    val totalCount: Int = 0,
    val hideExhausted: Boolean = false,
)

/** Everything the collection grid draws. */
data class CollectionUiState(
    val discoveredItems: List<AlchemyItem> = emptyList(),
    val totalCount: Int = 0,
)

/** Everything the item detail screen draws; [item] is null while the catalog is still loading. */
data class ItemDetailsUiState(
    val item: AlchemyItem? = null,
    val recipes: List<AlchemyRecipe> = emptyList(),
    val discoveredIds: Set<Long> = emptySet(),
)

/**
 * Crafting board callbacks. Every method has a no-op default so a preview can render the
 * board without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface HomeActions {
    fun clearElements() {}
    fun setHideExhausted(value: Boolean) {}
    fun placeElement(id: Long, offset: Offset) {}
    fun updateElementPosition(key: Long, offset: Offset) {}
    fun removeElement(key: Long) {}
    fun duplicateElement(key: Long) {}
    fun tryCombine(movedKey: Long, movedOffset: Offset) {}

    companion object {
        val Noop: HomeActions = object : HomeActions {}
    }
}
