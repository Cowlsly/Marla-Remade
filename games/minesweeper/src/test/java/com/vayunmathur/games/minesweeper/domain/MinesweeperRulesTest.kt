package com.vayunmathur.games.minesweeper.domain

import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.neighbourIndices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinesweeperRulesTest {

    @Test
    fun revealingAMineLosesAndRecordsWhere() {
        val state = field(
            """
            .*.
            ...
            ...
            """
        )
        val next = MinesweeperRules.reveal(state, 1)
        assertEquals(GameOutcome.LOST, next.outcome)
        assertEquals(1, next.explodedAt)
    }

    @Test
    fun revealingAZeroCascadesToTheNumbersAroundIt() {
        // The single mine is in the far corner, so tapping the opposite corner opens almost all of it.
        val state = field(
            """
            ....
            ....
            ....
            ...*
            """
        )
        val next = MinesweeperRules.reveal(state, 0)
        val hidden = next.revealed.indices.filterNot { next.revealed[it] }
        // Only the mine itself stays covered; every safe cell is reachable through the zeroes.
        assertEquals(listOf(15), hidden)
        assertEquals(GameOutcome.WON, next.outcome)
    }

    @Test
    fun cascadeStopsAtNumberedCells() {
        val state = field(
            """
            ...*
            ....
            ....
            ....
            """
        )
        val next = MinesweeperRules.reveal(state, 12)
        // Cells touching the mine are revealed but do not themselves spread.
        assertTrue(next.revealed[2], "the 1 next to the mine should be uncovered")
        assertFalse(next.revealed[3], "the mine itself stays covered")
    }

    @Test
    fun cascadeDoesNotOpenFlaggedCells() {
        val state = field(
            """
            ....
            ....
            ....
            ...*
            """
        )
        // A flag mid-field is a claim the player made; a cascade must not overrule it.
        val flagged = MinesweeperRules.toggleFlag(state, 5)
        val next = MinesweeperRules.reveal(flagged, 0)
        assertFalse(next.revealed[5], "flagged cell should stay covered")
    }

    @Test
    fun flaggingIsAToggleAndOnlyAppliesToCoveredCells() {
        val state = field(
            """
            .*.
            ...
            ...
            """
        )
        val once = MinesweeperRules.toggleFlag(state, 0)
        assertTrue(once.flagged[0])
        assertEquals(0, MinesweeperRules.toggleFlag(once, 0).flagged.count { it })

        val revealed = MinesweeperRules.reveal(state, 8)
        assertFalse(
            MinesweeperRules.toggleFlag(revealed, 8).flagged[8],
            "a revealed cell cannot be flagged",
        )
    }

    @Test
    fun chordNeedsTheFlagCountToMatch() {
        val state = field(
            """
            *..
            ...
            ...
            """
        )
        // Cell 1 sees exactly one mine. With no flag placed, chording it must do nothing.
        val revealed = MinesweeperRules.reveal(state, 1)
        assertEquals(revealed, MinesweeperRules.chord(revealed, 1))

        val flagged = MinesweeperRules.toggleFlag(revealed, 0)
        val chorded = MinesweeperRules.chord(flagged, 1)
        assertTrue(chorded.revealed[2], "chord should have opened the unflagged neighbours")
    }

    @Test
    fun chordOnAWrongFlagLosesTheGame() {
        val state = field(
            """
            *..
            ...
            ...
            """
        )
        val revealed = MinesweeperRules.reveal(state, 1)
        // Flagging the wrong cell satisfies the count but points at a safe square.
        val misflagged = MinesweeperRules.toggleFlag(revealed, 2)
        val chorded = MinesweeperRules.chord(misflagged, 1)
        assertEquals(GameOutcome.LOST, chorded.outcome)
    }

    @Test
    fun chordDoesNothingOnAZeroOrACoveredCell() {
        val state = field(
            """
            *..
            ...
            ...
            """
        )
        assertEquals(state, MinesweeperRules.chord(state, 1), "covered cell")
        val revealed = MinesweeperRules.reveal(state, 8)
        assertEquals(revealed, MinesweeperRules.chord(revealed, 8), "zero cell")
    }

    @Test
    fun winningFlagsEveryRemainingMine() {
        val state = field(
            """
            *..
            ...
            ...
            """
        )
        val next = MinesweeperRules.reveal(state, 8)
        assertEquals(GameOutcome.WON, next.outcome)
        assertEquals(listOf(0), next.flagged.indices.filter { next.flagged[it] })
        assertEquals(0, next.minesRemaining)
    }

    @Test
    fun aFinishedGameIgnoresFurtherInput() {
        val state = field(
            """
            .*.
            ...
            ...
            """
        )
        val lost = MinesweeperRules.reveal(state, 1)
        assertEquals(lost, MinesweeperRules.reveal(lost, 4))
        assertEquals(lost, MinesweeperRules.toggleFlag(lost, 4))
        assertEquals(lost, MinesweeperRules.chord(lost, 4))
    }

    @Test
    fun neighbourCountsMatchTheLayout() {
        val state = field(
            """
            *.*
            ...
            *.*
            """
        )
        // The centre touches all four corners.
        assertEquals(4, state.neighbourCounts[4])
        // The top middle touches the two mines beside it.
        assertEquals(2, state.neighbourCounts[1])
    }

    @Test
    fun edgeCellsHaveNoNeighboursOffTheBoard() {
        val size = BoardSize.SMALL
        val cols = size.cols
        val rows = size.rows
        assertEquals(3, neighbourIndices(cols, rows, 0).size, "top-left corner")
        assertEquals(
            3,
            neighbourIndices(cols, rows, size.cellCount - 1).size,
            "bottom-right corner",
        )
        assertEquals(5, neighbourIndices(cols, rows, 1).size, "top edge")
        assertEquals(8, neighbourIndices(cols, rows, cols + 1).size, "interior")
    }

    /**
     * Builds a state from an ASCII field where `*` is a mine, so a test reads as the board it means.
     *
     * The grid must be rectangular. Dimensions come from the literal rather than a [BoardSize], since
     * these tests care about layouts far smaller than any the picker offers.
     */
    private fun field(layout: String): MinesweeperGameState {
        val rows = layout.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val cols = rows.first().length
        require(rows.all { it.length == cols }) { "field must be rectangular" }

        val mines = rows.flatMap { row -> row.map { it == '*' } }
        val counts = mines.indices.map { index ->
            if (mines[index]) 0 else neighbourIndices(cols, rows.size, index).count { mines[it] }
        }
        return MinesweeperGameState(
            cols = cols,
            rows = rows.size,
            mineCount = mines.count { it },
            mines = mines,
            neighbourCounts = counts,
            revealed = List(mines.size) { false },
            flagged = List(mines.size) { false },
            started = true,
        )
    }
}
