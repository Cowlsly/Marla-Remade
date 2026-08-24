package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SudokuSolverTest {

    @Test
    fun emptyGridIsFilledLegally() {
        for (size in BoardSize.entries) {
            val solved = assertNotNull(
                SudokuSolver(size).solve(blank(size)),
                "${size.name} should be solvable from empty",
            )
            assertLegal(size, solved)
        }
    }

    @Test
    fun seededSolveIsReproducible() {
        val size = BoardSize.NINE
        val first = SudokuSolver(size).solve(blank(size), Random(42))
        val second = SudokuSolver(size).solve(blank(size), Random(42))
        assertEquals(first, second)
    }

    @Test
    fun differentSeedsGiveDifferentGrids() {
        val size = BoardSize.NINE
        val first = SudokuSolver(size).solve(blank(size), Random(1))
        val second = SudokuSolver(size).solve(blank(size), Random(2))
        assertTrue(first != second, "two seeds should not agree on a whole 9x9")
    }

    @Test
    fun contradictoryGridHasNoSolution() {
        val size = BoardSize.SIX
        // Two 1s in the same row cannot both be right.
        val grid = blank(size).toMutableList().also {
            it[0] = 1
            it[1] = 1
        }
        assertNull(SudokuSolver(size).solve(grid))
        assertEquals(0, SudokuSolver(size).countSolutions(grid))
    }

    @Test
    fun emptyGridHasManySolutions() {
        val size = BoardSize.SIX
        // Capped at 2: the point is only that it is not unique.
        assertEquals(2, SudokuSolver(size).countSolutions(blank(size)))
    }

    @Test
    fun completeGridCountsAsExactlyOne() {
        val size = BoardSize.SIX
        val solved = assertNotNull(SudokuSolver(size).solve(blank(size), Random(7)))
        assertEquals(1, SudokuSolver(size).countSolutions(solved))
    }

    @Test
    fun countStopsAtTheGivenLimit() {
        val size = BoardSize.SIX
        assertEquals(1, SudokuSolver(size).countSolutions(blank(size), limit = 1))
        // Subtree totals are added whole, so the result can overshoot the limit - it is a
        // "found at least this many" answer, which is all the uniqueness check needs.
        val capped = SudokuSolver(size).countSolutions(blank(size), limit = 5)
        assertTrue(capped >= 5, "expected at least 5, got $capped")
        // A 6x6 has 28,800 completions, so stopping early has to bite well short of that.
        assertTrue(capped < 1_000, "should have stopped early, got $capped")
    }

    @Test
    fun solverInstanceCanBeReused() {
        // The generator calls countSolutions repeatedly on one instance, so the masks must reset.
        val size = BoardSize.SIX
        val solver = SudokuSolver(size)
        val solved = assertNotNull(solver.solve(blank(size), Random(3)))
        repeat(3) {
            assertEquals(1, solver.countSolutions(solved), "reuse #$it")
        }
    }
}

internal fun blank(size: BoardSize) = List(size.cellCount) { 0 }

/** Fails unless every row, column and box of [grid] holds each digit exactly once. */
internal fun assertLegal(size: BoardSize, grid: List<Int>) {
    val side = size.side
    val expected = (1..side).toSet()
    for (i in 0 until side) {
        assertEquals(
            expected,
            (0 until side).map { grid[i * side + it] }.toSet(),
            "row $i of ${size.name}",
        )
        assertEquals(
            expected,
            (0 until side).map { grid[it * side + i] }.toSet(),
            "column $i of ${size.name}",
        )
        assertEquals(
            expected,
            grid.indices.filter { size.boxOf(it) == i }.map { grid[it] }.toSet(),
            "box $i of ${size.name}",
        )
    }
}
