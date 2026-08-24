package com.vayunmathur.games.minesweeper.domain

import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState

/**
 * The rules, as pure functions on a state.
 *
 * Kept out of the ViewModel so the cascade and chord logic — the two things most likely to be
 * subtly wrong — can be unit tested without an Android dependency.
 */
object MinesweeperRules {

    /**
     * Reveals [index] and, when it sees no mines, everything reachable from it.
     *
     * Flagged cells are skipped: the player has asserted a mine there, and opening it from a cascade
     * would throw away that judgement (and usually end the game). Revealing a mine loses.
     */
    fun reveal(state: MinesweeperGameState, index: Int): MinesweeperGameState {
        if (state.isOver || state.revealed[index] || state.flagged[index]) return state

        if (state.mines[index]) {
            return state.copy(
                revealed = state.revealed.replacing(index, true),
                outcome = GameOutcome.LOST,
                explodedAt = index,
            )
        }

        val revealed = state.revealed.toMutableList()
        // Iterative flood fill; a recursive one overflows the stack on a large open field.
        val queue = ArrayDeque<Int>()
        queue.addLast(index)
        revealed[index] = true
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (state.neighbourCounts[current] != 0) continue
            for (neighbour in state.neighbours(current)) {
                if (revealed[neighbour] || state.flagged[neighbour]) continue
                revealed[neighbour] = true
                if (state.neighbourCounts[neighbour] == 0) queue.addLast(neighbour)
            }
        }

        return state.copy(revealed = revealed).settleIfCleared()
    }

    /**
     * Cycles the flag on [index].
     *
     * Only unrevealed cells can be flagged; a revealed cell's state is already known, so flagging it
     * would be meaningless and would corrupt the remaining-mines count.
     */
    fun toggleFlag(state: MinesweeperGameState, index: Int): MinesweeperGameState {
        if (state.isOver || state.revealed[index]) return state
        return state.copy(flagged = state.flagged.replacing(index, !state.flagged[index]))
    }

    /**
     * Opens every unflagged neighbour of an already-revealed number, but only once exactly that many
     * flags surround it.
     *
     * This is the standard chord. It deliberately trusts the player's flags: if one is wrong the
     * chord uncovers a mine and the game is lost, which is the accepted risk that makes it a
     * meaningful shortcut rather than a free action.
     */
    fun chord(state: MinesweeperGameState, index: Int): MinesweeperGameState {
        if (state.isOver || !state.revealed[index]) return state
        val count = state.neighbourCounts[index]
        if (count == 0) return state

        val neighbours = state.neighbours(index)
        if (neighbours.count { state.flagged[it] } != count) return state

        var next = state
        for (neighbour in neighbours) {
            if (next.isOver) break
            if (!next.revealed[neighbour] && !next.flagged[neighbour]) {
                next = reveal(next, neighbour)
            }
        }
        return next.settleIfCleared()
    }

    /**
     * Marks a win once every safe cell is open.
     *
     * Remaining mines are flagged for the player rather than left blank, so the finished board reads
     * as complete and the flag counter lands on zero.
     */
    private fun MinesweeperGameState.settleIfCleared(): MinesweeperGameState {
        if (isOver || !isCleared) return this
        return copy(
            outcome = GameOutcome.WON,
            flagged = List(cellCount) { mines[it] },
        )
    }
}

/** [this] with [index] replaced by [value]. */
internal fun <T> List<T>.replacing(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }
