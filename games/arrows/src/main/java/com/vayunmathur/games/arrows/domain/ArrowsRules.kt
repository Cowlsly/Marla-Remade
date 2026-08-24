package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.TapOutcome

/**
 * The rules, as pure functions on a state.
 *
 * Kept out of the ViewModel so the travel simulation — the one thing that decides whether a tap is a
 * clean exit or a lost heart — can be unit tested without an Android dependency.
 */
object ArrowsRules {

    /**
     * The cells an arrow's head visits after leaving [from], heading [direction], until it is off the
     * board.
     *
     * Mirrors bend the route as the head enters them. Returns null if the head never gets out, which a
     * ring of mirrors can arrange; the caller treats that as impassable rather than looping forever.
     * The starting cell is not included, because the head is already there.
     */
    fun exitPath(puzzle: ArrowsPuzzle, from: Int, direction: Direction): List<Int>? {
        val path = mutableListOf<Int>()
        var row = from / puzzle.cols
        var col = from % puzzle.cols
        var heading = direction

        repeat(maxSteps(puzzle)) {
            row += heading.dRow
            col += heading.dCol
            if (!puzzle.contains(row, col)) return path

            val cell = row * puzzle.cols + col
            path.add(cell)
            puzzle.mirrors[cell]?.let { heading = it.reflect(heading) }
        }
        return null
    }

    /**
     * Whether [piece] can leave [state] right now.
     *
     * The body follows the head, so the only thing that can stop it is an occupied cell on the head's
     * route. The piece's own cells count as occupied too: normally the route leads away from its body
     * so this never comes up, but a mirror can turn an arrow back into itself, and letting it pass
     * through its own tail would be indefensible to a player watching it happen.
     */
    fun isBlocked(state: ArrowsGameState, piece: ArrowPiece): Boolean {
        val path = exitPath(state.puzzle, piece.head, piece.direction) ?: return true
        val occupied = state.occupancy
        return path.any { occupied.containsKey(it) }
    }

    /**
     * Applies a tap on the arrow with [pieceId].
     *
     * A clean exit removes it; a blocked one costs a heart and flags the arrow so the board can show
     * what went wrong. Running out of hearts is not handled here — [resetAfterFailure] is a separate
     * step, so the UI can show the failure before the board is rebuilt.
     */
    fun tap(state: ArrowsGameState, pieceId: Int): Pair<ArrowsGameState, TapOutcome> {
        if (state.isOver) return state to TapOutcome.IGNORED
        val piece = state.puzzle.pieces.firstOrNull { it.id == pieceId }
            ?: return state to TapOutcome.IGNORED
        if (piece.id in state.removed) return state to TapOutcome.IGNORED

        return if (isBlocked(state, piece)) {
            state.copy(hearts = state.hearts - 1, blockedId = piece.id) to TapOutcome.BLOCKED
        } else {
            state.copy(removed = state.removed + piece.id, blockedId = -1) to TapOutcome.CLEARED
        }
    }

    /** The same puzzle, back to a full board and full hearts. */
    fun resetAfterFailure(state: ArrowsGameState): ArrowsGameState =
        state.copy(removed = emptySet(), hearts = STARTING_HEARTS, blockedId = -1)

    /**
     * Every arrow that could leave right now.
     *
     * Used by the generator to confirm a board is still solvable, and by the hint in the UI.
     */
    fun clearableNow(state: ArrowsGameState): List<ArrowPiece> =
        state.remaining.filterNot { isBlocked(state, it) }

    /**
     * A removal order that empties the board, or null if none exists.
     *
     * Greedy and deliberately so: at every step it takes whichever arrow can currently leave. That is
     * sound here because removing an arrow only ever frees cells, so clearing one can never make
     * another impossible — no choice made along the way can be regretted, and no backtracking is
     * needed.
     */
    fun solve(puzzle: ArrowsPuzzle): List<Int>? {
        var state = ArrowsGameState(
            puzzle = puzzle,
            removed = emptySet(),
            hearts = STARTING_HEARTS,
            level = 0,
            mode = GameMode.CASUAL,
        )
        val order = mutableListOf<Int>()
        while (state.removed.size < puzzle.pieces.size) {
            val next = clearableNow(state).firstOrNull() ?: return null
            order.add(next.id)
            state = state.copy(removed = state.removed + next.id)
        }
        return order
    }

    /**
     * Step cap for [exitPath].
     *
     * A route that has taken more turns than there are cells must be circling, since a straight run
     * crosses the board in at most one dimension's worth of steps.
     */
    private fun maxSteps(puzzle: ArrowsPuzzle): Int = puzzle.cellCount * 4 + 8
}
