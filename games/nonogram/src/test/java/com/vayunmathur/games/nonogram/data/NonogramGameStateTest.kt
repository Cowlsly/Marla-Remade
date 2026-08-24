package com.vayunmathur.games.nonogram.data

import com.vayunmathur.games.nonogram.domain.puzzle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonogramGameStateTest {

    private val cross = puzzle(
        """
        .#.
        ###
        .#.
        """
    )

    private fun state(filled: Set<Int> = emptySet(), crossed: Set<Int> = emptySet()) =
        NonogramGameState(
            puzzle = cross,
            filled = filled,
            crossed = crossed,
            mode = GameMode.CASUAL,
            level = 1,
        )

    /** Indices of the cells that should be filled in [cross]. */
    private val answer = cross.solution.indices.filter { cross.solution[it] }.toSet()

    @Test
    fun aFreshBoardIsNotWon() {
        assertFalse(state().isWon)
    }

    @Test
    fun fillingExactlyTheSolutionWins() {
        assertTrue(state(filled = answer).isWon)
    }

    @Test
    fun crossesDoNotAffectTheWinCheck() {
        val blanks = cross.solution.indices.filterNot { cross.solution[it] }.toSet()
        assertTrue(state(filled = answer, crossed = blanks).isWon)
        assertTrue(state(filled = answer, crossed = emptySet()).isWon)
    }

    @Test
    fun aMissingCellIsNotAWin() {
        assertFalse(state(filled = answer - answer.first()).isWon)
    }

    @Test
    fun anExtraFilledCellIsNotAWin() {
        // Over-filling must not pass: the sets have to match, not merely cover the solution.
        val extra = cross.solution.indices.first { !cross.solution[it] }
        assertFalse(state(filled = answer + extra).isWon)
    }

    @Test
    fun aWrongFillIsReportedAsAMistake() {
        val wrong = cross.solution.indices.first { !cross.solution[it] }
        val s = state(filled = setOf(wrong))
        assertTrue(s.isMistake(wrong))
        assertTrue(s.hasMistake)
    }

    @Test
    fun aCorrectFillIsNotAMistake() {
        val right = answer.first()
        val s = state(filled = setOf(right))
        assertFalse(s.isMistake(right))
        assertFalse(s.hasMistake)
    }

    @Test
    fun aCrossedBlankIsNeverAMistake() {
        // Crosses are bookkeeping; being wrong about one is not the same as painting a wrong cell.
        val s = state(crossed = answer)
        assertFalse(s.hasMistake)
    }

    @Test
    fun markAtReportsWhatIsStored() {
        val s = state(filled = setOf(1), crossed = setOf(0))
        assertEquals(CellMark.FILLED, s.markAt(1))
        assertEquals(CellMark.CROSSED, s.markAt(0))
        assertEquals(CellMark.BLANK, s.markAt(2))
    }

    @Test
    fun levelSizeRampCoversTheThreeBoardSizes() {
        assertEquals(5, sizeForLevel(1))
        assertEquals(5, sizeForLevel(8))
        assertEquals(10, sizeForLevel(9))
        assertEquals(10, sizeForLevel(20))
        assertEquals(15, sizeForLevel(21))
        assertEquals(15, sizeForLevel(9999))
    }

    @Test
    fun theRampNeverShrinksAsLevelsGoUp() {
        val sizes = (1..60).map { sizeForLevel(it) }
        assertEquals(sizes, sizes.sorted(), "board size should only ever grow")
    }
}
