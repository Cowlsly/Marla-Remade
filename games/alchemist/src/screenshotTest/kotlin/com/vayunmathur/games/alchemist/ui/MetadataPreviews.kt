package com.vayunmathur.games.alchemist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.data.AlchemyRecipe
import com.vayunmathur.games.alchemist.platform.CollectionUiState
import com.vayunmathur.games.alchemist.platform.HomeActions
import com.vayunmathur.games.alchemist.platform.HomeUiState
import com.vayunmathur.games.alchemist.platform.ItemDetailsUiState
import com.vayunmathur.games.alchemist.platform.PlacedItem
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * A plausible early-game inventory, taken from the real `items.json` so the ids line up with
 * the `icon_NNN` drawables the board renders. Sorted by name, the way the ViewModel sorts it.
 */
private val PALETTE = listOf(
    AlchemyItem(4, "Air", false),
    AlchemyItem(15, "Cloud", false),
    AlchemyItem(14, "Dust", false),
    AlchemyItem(3, "Earth", false),
    AlchemyItem(11, "Energy", false),
    AlchemyItem(2, "Fire", false),
    AlchemyItem(17, "Geyser", false),
    AlchemyItem(6, "Lava", false),
    AlchemyItem(12, "Mud", false),
    AlchemyItem(10, "Ocean", false),
    AlchemyItem(24, "Plant", false),
    AlchemyItem(7, "Pressure", false),
    AlchemyItem(13, "Rain", false),
    AlchemyItem(28, "Sand", false),
    AlchemyItem(9, "Sea", false),
    AlchemyItem(22, "Sky", false),
    AlchemyItem(5, "Steam", false),
    AlchemyItem(27, "Stone", false),
    AlchemyItem(16, "Storm", false),
    AlchemyItem(1, "Water", false),
)

/** Total elements in the shipped catalog, so the "discovered / total" counters read true. */
private const val CATALOG_SIZE = 720

/**
 * Store listing images for `:games:alchemist`. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :games:alchemist:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/games-alchemist/`, where `release.sh` picks them up.
 *
 * Order comes from the function names — the generated PNG filenames embed them, so
 * `Preview1Board`/`Preview2Collection`/... sort into listing order. Renumber if you reorder.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions, or the engine silently discovers no tests.
 *
 * Board positions are raw pixels, matching the `Modifier.offset { IntOffset(...) }` the play
 * area uses, and they are kept more than the 100px combine radius apart so nothing looks like
 * it is mid-merge.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-board", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Board() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = HomeUiState(
                    placedItems = listOf(
                        PlacedItem(1, Offset(120f, 220f), key = 1),
                        PlacedItem(2, Offset(620f, 180f), key = 2),
                        PlacedItem(3, Offset(200f, 700f), key = 3),
                        PlacedItem(4, Offset(700f, 640f), key = 4),
                        PlacedItem(5, Offset(400f, 1120f), key = 5),
                    ),
                    paletteItems = PALETTE,
                    discoveredCount = PALETTE.size,
                    totalCount = CATALOG_SIZE,
                ),
                actions = HomeActions.Noop,
                onOpenCollection = {},
                onOpenGameCenter = {},
                onOpenItemDetails = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-collection", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Collection() {
        DynamicTheme(darkTheme = true) {
            CollectionScreen(
                state = CollectionUiState(
                    discoveredItems = PALETTE,
                    totalCount = CATALOG_SIZE,
                ),
                onBack = {},
                onOpenItemDetails = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-recipes", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Recipes() {
        DynamicTheme(darkTheme = true) {
            ItemDetailsScreen(
                state = ItemDetailsUiState(
                    item = AlchemyItem(5, "Steam", false),
                    recipes = listOf(
                        // Ways to make Steam...
                        AlchemyRecipe(listOf(1, 2), listOf(5)),
                        AlchemyRecipe(listOf(1, 6), listOf(5)),
                        AlchemyRecipe(listOf(1, 600), listOf(5)),
                        // ...and what it goes on to make. Hail (194) is undiscovered, so that
                        // last one collapses into the "locked recipes" card.
                        AlchemyRecipe(listOf(3, 5), listOf(17)),
                        AlchemyRecipe(listOf(5, 7), listOf(17)),
                        AlchemyRecipe(listOf(5, 36), listOf(38)),
                        AlchemyRecipe(listOf(4, 5), listOf(632)),
                        AlchemyRecipe(listOf(5, 148), listOf(194)),
                    ),
                    discoveredIds = setOf(1, 2, 3, 4, 5, 6, 7, 17, 36, 38, 600, 632),
                ),
                onBack = {},
            )
        }
    }
}
