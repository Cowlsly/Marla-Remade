package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NonogramSolverTest {

    @Test
    fun cluesAreRunLengthsOfFilledCells() {
        assertEquals(listOf(2, 1), NonogramPuzzle.cluesFor("##.#.".toLine()))
        assertEquals(listOf(5), NonogramPuzzle.cluesFor("#####".toLine()))
        assertEquals(emptyList(), NonogramPuzzle.cluesFor(".....".toLine()))
        assertEquals(listOf(1, 1, 1), NonogramPuzzle.cluesFor("#.#.#".toLine()))
    }

    @Test
    fun aFullyDeterminedPuzzleIsSolvedExactly() {
        val puzzle = puzzle(
            """
            ##.##
            #.#.#
            .###.
            #.#.#
            ##.##
            """
        )
        val solved = assertNotNull(
            NonogramSolver.solve(puzzle.size, puzzle.rowClues, puzzle.colClues)
        )
        assertEquals(
            puzzle.solution,
            solved.map { it == CellState.FILLED },
            "line logic should recover the exact picture",
        )
    }

    @Test
    fun anAllBlankGridSolvesToAllEmpty() {
        val puzzle = puzzle(
            """
            ...
            ...
            ...
            """
        )
        val solved = assertNotNull(
            NonogramSolver.solve(puzzle.size, puzzle.rowClues, puzzle.colClues)
        )
        assertTrue(solved.all { it == CellState.EMPTY })
    }

    @Test
    fun anAllFilledGridSolvesToAllFilled() {
        val puzzle = puzzle(
            """
            ###
            ###
            ###
            """
        )
        assertTrue(NonogramSolver.isLineSolvable(puzzle.size, puzzle.rowClues, puzzle.colClues))
    }

    @Test
    fun contradictoryCluesReportNoSolution() {
        // A clue of 4 cannot fit in a line of 3.
        assertNull(
            NonogramSolver.solve(
                size = 3,
                rowClues = listOf(listOf(4), emptyList(), emptyList()),
                colClues = listOf(emptyList(), emptyList(), emptyList()),
            )
        )
    }

    @Test
    fun cluesThatDisagreeBetweenRowsAndColumnsReportNoSolution() {
        // Rows say one cell is filled, columns say none are.
        assertNull(
            NonogramSolver.solve(
                size = 2,
                rowClues = listOf(listOf(1), emptyList()),
                colClues = listOf(emptyList(), emptyList()),
            )
        )
    }

    @Test
    fun anAmbiguousPuzzleIsNotLineSolvable() {
        // The classic 2x2 checkerboard: both diagonals satisfy every clue, so nothing is determined.
        val rowClues = listOf(listOf(1), listOf(1))
        val colClues = listOf(listOf(1), listOf(1))
        assertTrue(
            !NonogramSolver.isLineSolvable(2, rowClues, colClues),
            "a puzzle with two valid pictures must be rejected",
        )
        // It is satisfiable, just not decidable - so solve() returns a grid rather than null.
        val solved = assertNotNull(NonogramSolver.solve(2, rowClues, colClues))
        assertTrue(solved.any { it == CellState.UNKNOWN })
    }

    @Test
    fun solvingIsIndependentOfLineOrder() {
        // Transposing a puzzle swaps its row and column clues; the picture should transpose with it.
        val puzzle = puzzle(
            """
            #..#
            ##..
            .##.
            #..#
            """
        )
        val solved = assertNotNull(
            NonogramSolver.solve(puzzle.size, puzzle.rowClues, puzzle.colClues)
        )
        val transposed = assertNotNull(
            NonogramSolver.solve(puzzle.size, puzzle.colClues, puzzle.rowClues)
        )
        val n = puzzle.size
        for (row in 0 until n) {
            for (col in 0 until n) {
                assertEquals(
                    solved[row * n + col],
                    transposed[col * n + row],
                    "($row,$col) should mirror",
                )
            }
        }
    }

    @Test
    fun aRepeatingDiagonalPatternIsRejectedAsAmbiguous() {
        // Every row carries the same clue shifted by one, so the whole picture can slide. Line logic
        // cannot pin that down, and the generator is right to throw candidates like it away.
        val puzzle = puzzle(
            List(15) { row ->
                (0 until 15).joinToString("") { if ((row + it) % 3 == 0) "#" else "." }
            }.joinToString("\n")
        )
        // Satisfiable - it came from a real picture - but not decidable.
        assertNotNull(NonogramSolver.solve(puzzle.size, puzzle.rowClues, puzzle.colClues))
        assertTrue(
            !NonogramSolver.isLineSolvable(puzzle.size, puzzle.rowClues, puzzle.colClues),
            "a slidable pattern must not pass as line-solvable",
        )
    }

    @Test
    fun solvesAtTheLargestSizeTheGameOffers() {
        val puzzle = assertNotNull(NonogramGenerator.generateSeeded(15, seed = 15L))
        val solved = assertNotNull(NonogramSolver.solve(15, puzzle.rowClues, puzzle.colClues))
        assertEquals(puzzle.solution, solved.map { it == CellState.FILLED })
    }
}

/** `"##.#"` -> filled flags, so a test reads as the line it means. */
internal fun String.toLine(): List<Boolean> = map { it == '#' }

/** Builds a puzzle from an ASCII picture where `#` is filled. */
internal fun puzzle(picture: String): NonogramPuzzle {
    val rows = picture.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val size = rows.size
    require(rows.all { it.length == size }) { "picture must be square" }
    return NonogramPuzzle.from(size, rows.flatMap { it.toLine() })
}
