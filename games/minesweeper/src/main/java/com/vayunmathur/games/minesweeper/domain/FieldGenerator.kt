package com.vayunmathur.games.minesweeper.domain

import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.neighbourIndices
import kotlin.random.Random

/**
 * Lays mines after the opening tap.
 *
 * Deferring generation until the first reveal is what makes the opening move safe. It is not only the
 * tapped cell that is kept clear but its eight neighbours too, so the first reveal is always a zero
 * and always cascades into an opening area — a field where the first tap uncovers a lone "5" is
 * technically survivable but gives the player nothing to reason from.
 */
object FieldGenerator {

    /**
     * [state] with mines laid, none of them on or touching [safeIndex].
     *
     * If the mine count leaves no room for a clear neighbourhood the safe zone shrinks to just
     * [safeIndex] — the tap still cannot lose, it simply might not cascade. Difficulty caps density
     * so this only bites on fields far denser than the picker offers.
     */
    fun lay(state: MinesweeperGameState, safeIndex: Int, rng: Random): MinesweeperGameState {
        val cells = state.cellCount
        val fullSafeZone = neighbourIndices(state.cols, state.rows, safeIndex) + safeIndex
        val safeZone =
            if (cells - fullSafeZone.size >= state.mineCount) fullSafeZone.toSet() else setOf(safeIndex)

        val mineSet = (0 until cells)
            .filterNot { it in safeZone }
            .shuffled(rng)
            .take(state.mineCount)
            .toSet()

        val mines = List(cells) { it in mineSet }
        val counts = List(cells) { index ->
            if (mines[index]) 0
            else neighbourIndices(state.cols, state.rows, index).count { mines[it] }
        }
        return state.copy(mines = mines, neighbourCounts = counts, started = true)
    }
}
