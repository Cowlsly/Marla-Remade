package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SudokuGeneratorTest {

    @Test
    fun everyPuzzleHasExactlyOneSolution() {
        // The whole promise of the generator: the player never has to guess.
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val puzzle = SudokuGenerator.generate(size, difficulty, Random(11))
                assertEquals(
                    1,
                    SudokuSolver(size).countSolutions(puzzle.givens),
                    "${size.name}/${difficulty.name}",
                )
            }
        }
    }

    @Test
    fun solutionIsLegalAndAgreesWithTheClues() {
        for (size in BoardSize.entries) {
            val puzzle = SudokuGenerator.generate(size, Difficulty.MEDIUM, Random(5))
            assertLegal(size, puzzle.solution)
            puzzle.givens.forEachIndexed { index, given ->
                if (given != 0) {
                    assertEquals(puzzle.solution[index], given, "clue at $index")
                }
            }
        }
    }

    @Test
    fun clueCountNeverFallsBelowTheTarget() {
        // Digging stops early when a clue turns out to be load-bearing, so the count can only be
        // at or above the target - never below it.
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val puzzle = SudokuGenerator.generate(size, difficulty, Random(3))
                val clues = puzzle.givens.count { it != 0 }
                assertTrue(
                    clues >= difficulty.targetClues(size),
                    "${size.name}/${difficulty.name} had $clues clues, " +
                        "target ${difficulty.targetClues(size)}",
                )
            }
        }
    }

    @Test
    fun harderDifficultiesGiveNoMoreClues() {
        val size = BoardSize.NINE
        val counts = Difficulty.entries.map { difficulty ->
            SudokuGenerator.generate(size, difficulty, Random(9)).givens.count { it != 0 }
        }
        assertEquals(counts, counts.sortedDescending(), "clue counts should not increase: $counts")
    }

    @Test
    fun sameSeedGivesSamePuzzle() {
        val first = SudokuGenerator.generate(BoardSize.NINE, Difficulty.HARD, Random(77))
        val second = SudokuGenerator.generate(BoardSize.NINE, Difficulty.HARD, Random(77))
        assertEquals(first.givens, second.givens)
        assertEquals(first.solution, second.solution)
    }

    @Test
    fun everyPuzzleLeavesSomethingToDo() {
        for (size in BoardSize.entries) {
            for (difficulty in Difficulty.entries) {
                val puzzle = SudokuGenerator.generate(size, difficulty, Random(21))
                assertTrue(
                    puzzle.givens.any { it == 0 },
                    "${size.name}/${difficulty.name} was handed over already complete",
                )
            }
        }
    }
}
