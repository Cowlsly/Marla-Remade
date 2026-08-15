package com.vayunmathur.games.unblockjam

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.data.PackColorScheme
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.games.unblockjam.platform.GameActions
import com.vayunmathur.games.unblockjam.platform.GameUiState
import com.vayunmathur.library.util.LevelStats

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** The palette shipped in `original_pack.json`, which is what every puzzle renders with. */
private val PACK_COLORS = PackColorScheme(
    primary = 0xFF3D3021,
    secondary = 0xFF4A3B2A,
    tertiary = 0xFFFF0000,
    background = 0xFF4A3B2A,
    surface = 0xFF3D3021,
    primaryContainer = 0xFFFFA500,
    secondaryContainer = 0xFF6F5E55,
)

/**
 * A 6x6 board a few moves into the puzzle. Positions are in board cells with y running down,
 * the way [LevelData] stores them after parsing; block 0 is the red one that has to reach the
 * exit, so its row must match [LevelData.exit]'s.
 */
private val BOARD = LevelData(
    id = "60",
    dimension = Dimension(6, 6),
    exit = Coord(6, 2),
    blocks = listOf(
        Block(Coord(0, 2), Dimension(2, 1), false),
        Block(Coord(2, 0), Dimension(1, 3), false),
        Block(Coord(4, 1), Dimension(1, 2), false),
        Block(Coord(2, 3), Dimension(3, 1), false),
        Block(Coord(3, 0), Dimension(2, 1), false),
        Block(Coord(5, 2), Dimension(1, 2), false),
        Block(Coord(3, 1), Dimension(1, 2), false),
        Block(Coord(2, 4), Dimension(3, 1), false),
        Block(Coord(0, 5), Dimension(3, 1), false),
    ),
    optimalMoves = 10,
)

/** The puzzle grid only reads each level's id and optimal move count, so reuse one layout. */
private val PACK = LevelPack(
    name = "Original Pack",
    levels = List(24) { index -> BOARD.copy(id = index.toString(), optimalMoves = 4 + index % 9) },
    colorScheme = PACK_COLORS,
)

/** Solved puzzles: a star when the player matched the optimum, a tick when they beat it out. */
private val LEVEL_STATS = mapOf(
    "0" to LevelStats(4),
    "1" to LevelStats(6),
    "2" to LevelStats(6),
    "3" to LevelStats(9),
    "4" to LevelStats(8),
    "5" to LevelStats(9),
    "6" to LevelStats(13),
    "7" to LevelStats(11),
    "8" to LevelStats(15),
    "9" to LevelStats(4),
    "10" to LevelStats(7),
)

/**
 * Store listing images for `:games:unblockjam`. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :games:unblockjam:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/games-unblockjam/`, where `release.sh` picks them up.
 *
 * Order comes from the function names — the generated PNG filenames embed them, so
 * `Preview1Board`/`Preview2Puzzles` sort into listing order. Renumber if you reorder.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions, or the engine silently discovers no tests.
 *
 * These wrap [UnblockJamTheme] rather than the shared `DynamicTheme` that most of the other
 * apps preview with, because that is the app's real theme — the board's brown-and-orange
 * palette comes from the pack, not from Material You.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-board", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Board() {
        UnblockJamTheme(pack = PACK) {
            GameScreen(
                state = GameUiState(
                    levelData = BOARD,
                    levelIndex = 11,
                    maxLevelIndex = PACK.levels.lastIndex,
                    moves = 7,
                    bestScore = 12,
                    isCompleted = true,
                    canUndo = true,
                ),
                actions = GameActions.Noop,
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-puzzles", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Puzzles() {
        UnblockJamTheme(pack = PACK) {
            LevelScreen(
                pack = PACK,
                levelStats = LEVEL_STATS,
                onOpenLevel = {},
            )
        }
    }
}
