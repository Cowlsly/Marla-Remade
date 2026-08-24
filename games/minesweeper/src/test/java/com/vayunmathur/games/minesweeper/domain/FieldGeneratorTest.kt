package com.vayunmathur.games.minesweeper.domain

import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.neighbourIndices
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldGeneratorTest {

    @Test
    fun laysExactlyTheRequestedNumberOfMines() {
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val blank = MinesweeperGameState.empty(GameConfig(size, difficulty))
                val laid = FieldGenerator.lay(blank, safeIndex = 0, rng = Random(4))
                assertEquals(
                    difficulty.mineCount(size),
                    laid.mines.count { it },
                    "${size.name}/${difficulty.name}",
                )
            }
        }
    }

    @Test
    fun theFirstTapAndItsNeighboursAreAlwaysClear() {
        // This is the fairness guarantee: the opening move can never lose and always cascades.
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val blank = MinesweeperGameState.empty(GameConfig(size, difficulty))
                val safeIndex = interiorIndex(size)
                repeat(20) { seed ->
                    val laid = FieldGenerator.lay(blank, safeIndex, Random(seed))
                    assertFalse(laid.mines[safeIndex], "${size.name}/${difficulty.name} seed $seed")
                    for (n in neighbourIndices(size.cols, size.rows, safeIndex)) {
                        assertFalse(laid.mines[n], "neighbour $n, ${size.name} seed $seed")
                    }
                }
            }
        }
    }

    @Test
    fun theFirstRevealNeverLosesAndAlwaysOpensAnArea() {
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val blank = MinesweeperGameState.empty(GameConfig(size, difficulty))
                val safeIndex = interiorIndex(size)
                val laid = FieldGenerator.lay(blank, safeIndex, Random(99))
                val opened = MinesweeperRules.reveal(laid, safeIndex)
                assertTrue(
                    opened.outcome != GameOutcome.LOST,
                    "${size.name}/${difficulty.name} lost on the opening tap",
                )
                // An interior tap has eight neighbours and is guaranteed to be a zero, so the
                // cascade opens at least the full 3x3 around it.
                assertTrue(
                    opened.revealed.count { it } >= 9,
                    "${size.name}/${difficulty.name} opened only " +
                        "${opened.revealed.count { it }} cells",
                )
            }
        }
    }

    @Test
    fun neighbourCountsAgreeWithTheMinesLaid() {
        val blank = MinesweeperGameState.empty(GameConfig(BoardSize.MEDIUM, Difficulty.HARD))
        val laid = FieldGenerator.lay(blank, safeIndex = 0, rng = Random(17))
        for (index in 0 until laid.cellCount) {
            val expected =
                if (laid.mines[index]) 0
                else neighbourIndices(laid.cols, laid.rows, index).count { laid.mines[it] }
            assertEquals(expected, laid.neighbourCounts[index], "cell $index")
        }
    }

    @Test
    fun sameSeedLaysTheSameField() {
        val blank = MinesweeperGameState.empty(GameConfig(BoardSize.SMALL, Difficulty.MEDIUM))
        val first = FieldGenerator.lay(blank, safeIndex = 12, rng = Random(2026))
        val second = FieldGenerator.lay(blank, safeIndex = 12, rng = Random(2026))
        assertEquals(first.mines, second.mines)
    }

    @Test
    fun layingMarksTheFieldStarted() {
        val blank = MinesweeperGameState.empty(GameConfig())
        assertFalse(blank.started)
        assertTrue(FieldGenerator.lay(blank, 0, Random(1)).started)
    }

    @Test
    fun densityAlwaysLeavesRoomForTheOpeningArea() {
        // mineCount is clamped so a field can never be so dense that the safe zone cannot fit.
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                assertTrue(
                    difficulty.mineCount(size) <= size.cellCount - 9,
                    "${size.name}/${difficulty.name}",
                )
            }
        }
    }

    /**
     * A cell with all eight neighbours on the board.
     *
     * `cellCount / 2` is not it: with an even column count that lands on column 0, an edge cell with
     * only five neighbours, which quietly weakens every assertion about the opening area.
     */
    private fun interiorIndex(size: BoardSize): Int = (size.rows / 2) * size.cols + size.cols / 2
}
