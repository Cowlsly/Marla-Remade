package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NonogramGeneratorTest {

    @Test
    fun everyGeneratedPuzzleIsSolvableWithoutGuessing() {
        // The whole promise of the generator.
        for (size in listOf(5, 10, 15)) {
            val puzzle = assertNotNull(
                NonogramGenerator.generateSeeded(size, seed = 31L),
                "no ${size}x$size puzzle found",
            )
            assertTrue(
                NonogramSolver.isLineSolvable(size, puzzle.rowClues, puzzle.colClues),
                "${size}x$size needed guesswork",
            )
        }
    }

    @Test
    fun cluesMatchTheSolution() {
        val puzzle = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 7L))
        val expected = NonogramPuzzle.from(puzzle.size, puzzle.solution)
        assertEquals(expected.rowClues, puzzle.rowClues)
        assertEquals(expected.colClues, puzzle.colClues)
    }

    @Test
    fun theSolvedGridEqualsTheIntendedPicture() {
        // Full determination implies uniqueness, so the recovered picture must be the generated one.
        val puzzle = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 123L))
        val solved = assertNotNull(
            NonogramSolver.solve(puzzle.size, puzzle.rowClues, puzzle.colClues)
        )
        assertEquals(puzzle.solution, solved.map { it == CellState.FILLED })
    }

    @Test
    fun sameSeedGivesTheSamePuzzle() {
        // Saved progress is keyed by level number, so a level must never repaint itself.
        val first = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 4242L))
        val second = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 4242L))
        assertEquals(first.solution, second.solution)
    }

    @Test
    fun differentSeedsGiveDifferentPuzzles() {
        val first = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 1L))
        val second = assertNotNull(NonogramGenerator.generateSeeded(10, seed = 2L))
        assertTrue(first.solution != second.solution)
    }

    @Test
    fun noPuzzleIsCompletelyBlank() {
        for (seed in 1L..25L) {
            val puzzle = assertNotNull(NonogramGenerator.generateSeeded(5, seed = seed))
            assertTrue(puzzle.filledCount > 0, "seed $seed produced an empty picture")
        }
    }

    @Test
    fun everySeedInAWideRangeYieldsAPuzzle() {
        // If generation failed even occasionally, a player would hit a level that cannot be loaded.
        for (level in 1L..40L) {
            val size = sizeFor(level.toInt())
            assertNotNull(
                NonogramGenerator.generateSeeded(size, seed = level),
                "level $level (${size}x$size) produced nothing",
            )
        }
    }

    @Test
    fun generateReturnsNullRatherThanLoopingWhenGivenNoAttempts() {
        assertEquals(null, NonogramGenerator.generate(10, Random(1), attempts = 0))
    }

    /** Mirrors the production level-to-size ramp closely enough to exercise all three sizes. */
    private fun sizeFor(level: Int): Int = when {
        level <= 8 -> 5
        level <= 20 -> 10
        else -> 15
    }
}
