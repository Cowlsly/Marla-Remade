package com.vayunmathur.games.sudoku.data

import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SudokuGameStateTest {

    private val puzzle = SudokuGenerator.generate(BoardSize.FOUR, Difficulty.EASY, Random(1))
    private val fresh = SudokuGameState.from(puzzle)

    /** A cell the player is allowed to fill in. */
    private val blank = puzzle.givens.indices.first { puzzle.givens[it] == 0 }

    /** A digit that does not belong in [blank]. */
    private val wrongDigit =
        (1..puzzle.size.side).first { it != puzzle.solution[blank] }

    @Test
    fun theCorrectDigitIsAccepted() {
        assertTrue(fresh.accepts(blank, puzzle.solution[blank]))
    }

    @Test
    fun aWrongDigitIsRefused() {
        // The whole point: the grid never holds a digit that disagrees with the solution.
        assertFalse(fresh.accepts(blank, wrongDigit))
    }

    @Test
    fun givenCellsRefuseEverything() {
        val given = puzzle.givens.indices.first { puzzle.givens[it] != 0 }
        for (digit in 1..puzzle.size.side) {
            assertFalse(fresh.accepts(given, digit), "digit $digit into a given")
        }
    }

    @Test
    fun outOfRangeCellsAreRefused() {
        assertFalse(fresh.accepts(-1, 1))
        assertFalse(fresh.accepts(puzzle.size.cellCount, 1))
    }

    @Test
    fun aFinishedGridRefusesFurtherWrites() {
        val solved = fresh.copy(
            entries = List(puzzle.size.cellCount) { if (fresh.isGiven(it)) 0 else puzzle.solution[it] },
            isWon = true,
        )
        assertTrue(solved.isComplete)
        assertFalse(solved.accepts(blank, puzzle.solution[blank]))
    }

    @Test
    fun blankIndicesAreExactlyTheEmptyCells() {
        assertEquals(
            puzzle.givens.indices.filter { puzzle.givens[it] == 0 },
            fresh.blankIndices(),
        )
        // Filling one removes it from the list, which is what stops a hint reusing that cell.
        val filled = fresh.copy(entries = fresh.entries.toMutableList().also {
            it[blank] = puzzle.solution[blank]
        })
        assertFalse(blank in filled.blankIndices())
    }

    @Test
    fun placedCountOnlyCountsWhatIsOnTheBoard() {
        val digit = puzzle.solution[blank]
        val before = fresh.placedCount(digit)
        val after = fresh.copy(entries = fresh.entries.toMutableList().also {
            it[blank] = digit
        }).placedCount(digit)
        assertEquals(before + 1, after)
    }

    @Test
    fun aGridIsCompleteOnlyWhenEveryCellMatchesTheSolution() {
        assertFalse(fresh.isComplete)
        val solved = fresh.copy(
            entries = List(puzzle.size.cellCount) { if (fresh.isGiven(it)) 0 else puzzle.solution[it] }
        )
        assertTrue(solved.isComplete)
    }

    @Test
    fun clueFractionsOnlyEverGetHarder() {
        val targets = Difficulty.entries.map { it.targetClues(BoardSize.NINE) }
        assertEquals(targets, targets.sortedDescending(), "clue targets should fall: $targets")
    }

    @Test
    fun boxGeometryCoversEveryCellExactlyOnce() {
        for (size in BoardSize.entries) {
            val counts = IntArray(size.side)
            for (index in 0 until size.cellCount) counts[size.boxOf(index)]++
            // Every box must hold exactly as many cells as a row does, or the box constraint would
            // be weaker or stronger than the row constraint.
            assertTrue(
                counts.all { it == size.side },
                "${size.name} box sizes were ${counts.toList()}",
            )
        }
    }
}
