package com.vayunmathur.games.arrows

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.DAILY_ARROWS
import com.vayunmathur.games.arrows.data.DAILY_BOARD
import com.vayunmathur.games.arrows.data.DAILY_MIRRORS
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.arrowCountForLevel
import com.vayunmathur.games.arrows.data.BIGGEST_BOARD_LEVEL
import com.vayunmathur.games.arrows.data.MAX_BOARD
import com.vayunmathur.games.arrows.data.boardSizeForLevel
import com.vayunmathur.games.arrows.data.mirrorCountForLevel
import com.vayunmathur.games.arrows.domain.ArrowsGenerator
import com.vayunmathur.games.arrows.domain.ArrowsRules
import com.vayunmathur.games.arrows.platform.ArrowsGameActions
import com.vayunmathur.games.arrows.platform.ArrowsUiState
import com.vayunmathur.games.arrows.ui.ArrowsGameScreen
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * The store listing images.
 *
 * Previews must be members of a class and carry both `@PreviewTest` and `@Preview`, or the screenshot
 * engine discovers no tests. The `PreviewN` names set the order they appear in the listing, because
 * the collector sorts by filename. Run `./gradlew :games:arrows:metadata`.
 *
 * Boards come from [ArrowsGenerator] with the same seeds the real levels use, so an image is a genuine
 * level rather than a hand-drawn approximation, and is reproducible from a clean checkout. Positions
 * part-way through are reached by replaying real taps from [ArrowsRules.solve], so no image shows a
 * state the rules could not produce. [ArrowsGameScreen] has no polling `LaunchedEffect`, so a preview
 * settles on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-level", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Level() {
        // A few arrows already flown, and one tapped that had nowhere to go - so the shot shows the
        // blocked arrow in red with the route it tried to take dotted out ahead of it.
        val puzzle = level(7)
        Board(game(puzzle, level = 7, cleared = 3, blocked = true, hearts = STARTING_HEARTS - 1))
    }

    @PreviewTest
    @Preview(name = "2-mirrors", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Mirrors() {
        // Past the level that introduces redirectors, so the diagonals show up.
        val puzzle = level(18)
        Board(game(puzzle, level = 18, cleared = 4))
    }

    @PreviewTest
    @Preview(name = "3-daily", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Daily() {
        val (cols, rows) = DAILY_BOARD
        val puzzle = requireNotNull(
            ArrowsGenerator.generateSeeded(cols, rows, DAILY_ARROWS, DAILY_MIRRORS, seed = 20260824L)
        )
        Board(
            game(puzzle, level = 1, cleared = 5, mode = GameMode.DAILY, hearts = 2),
            streak = 4,
        )
    }

    @PreviewTest
    @Preview(name = "4-fresh", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Fresh() {
        Board(game(level(2), level = 2, cleared = 0))
    }

    /**
     * The largest board the ladder ever deals.
     *
     * Here to guard the layout: at [MAX_BOARD] a phone-width board is twelve rows deep, which is taller
     * than the content area, so this is what shows whether the screen still holds together at the extreme
     * rather than only at the tutorial sizes.
     */
    @PreviewTest
    @Preview(name = "5-largest", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview5Largest() {
        Board(game(level(BIGGEST_BOARD_LEVEL), level = BIGGEST_BOARD_LEVEL, cleared = 2))
    }

    @Composable
    private fun Board(game: ArrowsGameState, streak: Long = 0) {
        // Layoutlib has no wallpaper to sample, so Material You falls back to the default palette.
        DynamicTheme(darkTheme = true) {
            ArrowsGameScreen(
                state = ArrowsUiState(
                    game = game,
                    mode = game.mode,
                    level = game.level,
                    dailyStreak = streak,
                ),
                actions = ArrowsGameActions.Noop,
                onOpenSettings = {},
                onOpenGameCenter = {},
            )
        }
    }

    /** The real board for [level], so the preview shows one the game would actually deal. */
    private fun level(level: Int): ArrowsPuzzle {
        val (cols, rows) = boardSizeForLevel(level)
        return requireNotNull(
            ArrowsGenerator.generateSeeded(
                cols = cols,
                rows = rows,
                targetPieces = arrowCountForLevel(level),
                mirrorCount = mirrorCountForLevel(level),
                seed = level.toLong(),
            )
        ) { "no board for level $level" }
    }

    /**
     * A board with the first [cleared] arrows of a real solution already flown.
     *
     * Using the solver's order rather than an arbitrary subset means the remaining arrows are in a
     * position that could genuinely arise mid-game.
     */
    private fun game(
        puzzle: ArrowsPuzzle,
        level: Int,
        cleared: Int,
        mode: GameMode = GameMode.CASUAL,
        hearts: Int = STARTING_HEARTS,
        blocked: Boolean = false,
    ): ArrowsGameState {
        val order = ArrowsRules.solve(puzzle).orEmpty()
        val state = ArrowsGameState(
            puzzle = puzzle,
            removed = order.take(cleared).toSet(),
            hearts = hearts,
            level = level,
            mode = mode,
        )
        if (!blocked) return state
        // Flag an arrow that genuinely cannot move, so the red highlight and the dotted route are
        // showing something real rather than a state the rules would never produce.
        val stuck = state.remaining.firstOrNull { ArrowsRules.isBlocked(state, it) } ?: return state
        return state.copy(blockedId = stuck.id)
    }
}
