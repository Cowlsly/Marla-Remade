package com.vayunmathur.games.unblockjam.domain

import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.LevelData

/**
 * Breadth-first search over board configurations.
 *
 * A move here is "slide one block any distance along its axis", which is exactly what the game
 * counts: [UnblockJamViewModel.onBlockMoved] only extends the history when the dragged block
 * changes, so a run of drags on one block collapses into a single move.
 */
object RushHourSolver {

    /**
     * Every configuration reachable from a level's starting layout, with each one's distance from
     * a position the main block can drive out of.
     *
     * Sliding is reversible, so the graph is undirected and one sweep outwards from the winning
     * positions gives the distance for every state at once.
     */
    class Exploration internal constructor(
        private val board: Board,
        private val states: LongArray,
        val distanceToGoal: IntArray,
        val startIndex: Int,
    ) {
        val stateCount: Int get() = states.size

        /** Moves needed to win from [stateIndex], counting the final drive-out. */
        fun movesToWin(stateIndex: Int): Int = distanceToGoal[stateIndex] + 1

        /** Rebuilds a playable level whose starting layout is [stateIndex]. */
        fun levelAt(stateIndex: Int, level: LevelData): LevelData =
            level.copy(blocks = board.blocksFor(states[stateIndex], level.blocks))
    }

    /** Null when the board is malformed, or when the search hits [maxStates]. */
    fun explore(level: LevelData, maxStates: Int = 200_000): Exploration? {
        val board = Board.of(level) ?: return null
        val start = board.startState(level)

        val indexByState = HashMap<Long, Int>()
        var states = LongArray(64)
        var count = 0
        fun add(state: Long): Boolean {
            if (indexByState.putIfAbsent(state, count) != null) return false
            if (count == states.size) states = states.copyOf(count * 2)
            states[count++] = state
            return true
        }
        add(start)

        // Enumerate the component the starting layout belongs to.
        var cursor = 0
        val successors = LongArray(MAX_SUCCESSORS)
        while (cursor < count) {
            if (count > maxStates) return null
            val found = board.successors(states[cursor], successors)
            for (i in 0 until found) add(successors[i])
            cursor++
        }

        states = states.copyOf(count)
        val distance = IntArray(count) { UNREACHABLE }
        val queue = ArrayDeque<Int>()
        for (index in 0 until count) {
            if (board.isGoal(states[index])) {
                distance[index] = 0
                queue.add(index)
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val found = board.successors(states[index], successors)
            for (i in 0 until found) {
                val neighbour = indexByState.getValue(successors[i])
                if (distance[neighbour] == UNREACHABLE) {
                    distance[neighbour] = distance[index] + 1
                    queue.add(neighbour)
                }
            }
        }
        return Exploration(board, states, distance, startIndex = 0)
    }

    /** Fewest moves that free the main block, or null if the board cannot be solved. */
    fun optimalMoves(level: LevelData, maxStates: Int = 200_000): Int? {
        val exploration = explore(level, maxStates) ?: return null
        if (exploration.distanceToGoal[exploration.startIndex] == UNREACHABLE) return null
        return exploration.movesToWin(exploration.startIndex)
    }

    /**
     * A board as bit-packed state plus per-block geometry. Only the coordinate along a block's own
     * axis ever changes, so a state is [BITS_PER_BLOCK] bits per block packed into a Long.
     */
    internal class Board private constructor(
        private val n: Int,
        private val width: Int,
        height: Int,
        private val horizontal: BooleanArray,
        private val fixed: BooleanArray,
        private val length: IntArray,
        private val cross: IntArray,
        private val crossLength: IntArray,
        private val limit: IntArray,
    ) {
        private val occupied = BooleanArray(width * height)

        private fun index(x: Int, y: Int) = y * width + x

        private fun positionOf(state: Long, i: Int) =
            ((state ushr (i * BITS_PER_BLOCK)) and POSITION_MASK).toInt()

        private fun withPosition(state: Long, i: Int, position: Int): Long {
            val shift = i * BITS_PER_BLOCK
            return (state and (POSITION_MASK shl shift).inv()) or (position.toLong() shl shift)
        }

        private fun fill(state: Long, value: Boolean) {
            for (i in 0 until n) {
                val primary = positionOf(state, i)
                for (step in 0 until length[i]) {
                    for (c in 0 until crossLength[i]) {
                        if (horizontal[i]) occupied[index(primary + step, cross[i] + c)] = value
                        else occupied[index(cross[i] + c, primary + step)] = value
                    }
                }
            }
        }

        /** The main block can drive out when nothing sits between it and the right edge. */
        fun isGoal(state: Long): Boolean {
            fill(state, true)
            val clear = (positionOf(state, 0) + length[0] until width)
                .none { occupied[index(it, cross[0])] }
            fill(state, false)
            return clear
        }

        /** Writes every one-move successor into [out], returning how many there are. */
        fun successors(state: Long, out: LongArray): Int {
            fill(state, true)
            var found = 0
            for (i in 0 until n) {
                if (fixed[i]) continue
                for (direction in intArrayOf(-1, 1)) {
                    var pos = positionOf(state, i)
                    while (true) {
                        val entering = if (direction < 0) pos - 1 else pos + length[i]
                        if (entering < 0 || entering >= limit[i]) break
                        var blocked = false
                        for (c in 0 until crossLength[i]) {
                            blocked = if (horizontal[i]) occupied[index(entering, cross[i] + c)]
                            else occupied[index(cross[i] + c, entering)]
                            if (blocked) break
                        }
                        if (blocked) break
                        pos += direction
                        out[found++] = withPosition(state, i, pos)
                    }
                }
            }
            fill(state, false)
            return found
        }

        fun startState(level: LevelData): Long {
            var state = 0L
            for (i in 0 until n) {
                val block = level.blocks[i]
                state = withPosition(
                    state, i, if (horizontal[i]) block.position.x else block.position.y
                )
            }
            return state
        }

        fun blocksFor(state: Long, template: List<Block>): List<Block> = template.mapIndexed { i, block ->
            val primary = positionOf(state, i)
            val position =
                if (horizontal[i]) Coord(primary, cross[i]) else Coord(cross[i], primary)
            block.copy(position = position)
        }

        companion object {
            fun of(level: LevelData): Board? {
                val blocks = level.blocks
                if (blocks.isEmpty()) return null
                val main = blocks[0]
                // The main block always slides out along the exit row; anything else is not a
                // Rush Hour board.
                if (main.dimension.width <= main.dimension.height) return null
                if (main.position.y != level.exit.y) return null
                // Bit-packing bound. The shipped pack tops out at 14 blocks on a 6x6.
                if (blocks.size > MAX_BLOCKS) return null
                if (level.dimension.width > MAX_EXTENT || level.dimension.height > MAX_EXTENT) return null

                val n = blocks.size
                val horizontal = BooleanArray(n) {
                    blocks[it].dimension.width > blocks[it].dimension.height
                }
                return Board(
                    n = n,
                    width = level.dimension.width,
                    height = level.dimension.height,
                    horizontal = horizontal,
                    fixed = BooleanArray(n) { blocks[it].fixed },
                    length = IntArray(n) {
                        if (horizontal[it]) blocks[it].dimension.width else blocks[it].dimension.height
                    },
                    cross = IntArray(n) {
                        if (horizontal[it]) blocks[it].position.y else blocks[it].position.x
                    },
                    crossLength = IntArray(n) {
                        if (horizontal[it]) blocks[it].dimension.height else blocks[it].dimension.width
                    },
                    limit = IntArray(n) {
                        if (horizontal[it]) level.dimension.width else level.dimension.height
                    },
                )
            }
        }
    }

    private const val UNREACHABLE = -1
    private const val BITS_PER_BLOCK = 4
    private const val POSITION_MASK = 0xFL
    private const val MAX_BLOCKS = 16
    private const val MAX_EXTENT = 16

    /** Worst case: every block slides the full width of the board in both directions. */
    private const val MAX_SUCCESSORS = MAX_BLOCKS * MAX_EXTENT
}
