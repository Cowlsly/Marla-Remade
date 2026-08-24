package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the cost of generation.
 *
 * Puzzles are dug on device, one uniqueness check per removed clue, and the board shows a spinner until
 * that finishes. 12x12 Expert is the worst case by a wide margin: 144 cells, the loosest clue target,
 * and non-square boxes. If this ever creeps into seconds the fix is to raise that difficulty's clue
 * fraction, not to let players stare at a spinner.
 */
class SudokuGenerationCostTest {

    @Test
    fun theWorstCaseBoardGeneratesQuickly() {
        val elapsed = measureTimeMillis {
            SudokuGenerator.generate(BoardSize.TWELVE, Difficulty.EXPERT, Random(1))
        }
        assertTrue(elapsed < BUDGET_MILLIS, "12x12 Expert took ${elapsed}ms, budget ${BUDGET_MILLIS}ms")
    }

    @Test
    fun everySizeAndDifficultyStaysWithinBudget() {
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val elapsed = measureTimeMillis {
                    SudokuGenerator.generate(size, difficulty, Random(7))
                }
                assertTrue(
                    elapsed < BUDGET_MILLIS,
                    "${size.name}/${difficulty.name} took ${elapsed}ms, budget ${BUDGET_MILLIS}ms",
                )
            }
        }
    }

    private companion object {
        /**
         * Generous enough not to flake on a loaded CI machine, tight enough that a real regression -
         * the kind that makes the largest board unplayable - still trips it.
         */
        const val BUDGET_MILLIS = 4_000L
    }
}
