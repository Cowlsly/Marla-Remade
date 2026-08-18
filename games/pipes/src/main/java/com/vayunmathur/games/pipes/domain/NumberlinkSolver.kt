package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair

/**
 * Counts the ways a numberlink board can be solved.
 *
 * A Kotlin port of the ZDD counter in `scripts/pipes/numberlink_solver.cpp` (Kentaro Imajo, 2011)
 * that the offline asset pipeline runs, so runtime-generated levels clear the same bar as the
 * shipped packs. A solution is a set of grid edges where every cell has degree at most two, every
 * endpoint cell has degree exactly one, there are no cycles, and no two different colours are
 * joined — which leaves blank cells free to stay untouched. So a board whose pairs can be joined
 * without covering it counts as more than one solution.
 *
 * That is what makes [Verdict.UNIQUE] the property the generator wants: its carve already covers
 * every cell, so if that is the only solution then joining every pair necessarily fills the board,
 * which is what [com.vayunmathur.games.pipes.platform.PipesViewModel] demands for a win.
 *
 * Cells are walked along diagonals from the top-left, so the set of half-finished paths crossing the
 * diagonal — the "frontier" — stays about one row wide. Boards that differ before the frontier but
 * agree on it have the same number of completions, so memoising on the frontier collapses the search
 * from exponential in the cell count to exponential in the board width.
 */
internal object NumberlinkSolver {

    enum class Verdict {
        /** Exactly one solution, so a covering carve is the only way to join the pairs. */
        UNIQUE,

        /** At least two solutions, so at least one of them may leave cells empty. */
        MULTIPLE,

        /** No solution at all. Only reachable from a malformed board. */
        NONE,

        /** Gave up before deciding. Callers generating levels should treat this as a rejection. */
        UNDECIDED,
    }

    /**
     * Assumes the four-neighbour grid adjacency over [cells] that `computeAdjacency` produces;
     * boards with bridges or any other hand-written adjacency are out of scope.
     *
     * [stateBudget] caps the number of memoised frontier states, bounding both time and memory on a
     * board too wide for the frontier to stay small.
     */
    fun classify(
        cells: Set<CellPos>,
        endpoints: List<EndpointPair>,
        stateBudget: Int = DEFAULT_STATE_BUDGET,
    ): Verdict {
        if (cells.isEmpty() || endpoints.isEmpty()) return Verdict.NONE
        if (endpoints.any { pair -> pair.cells.size != 2 || pair.cells.any { it !in cells } }) {
            return Verdict.NONE
        }
        if (endpoints.flatMap { it.cells }.toSet().size != endpoints.size * 2) return Verdict.NONE

        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.col } + 1
        val numbers = IntArray(rows * cols) { HOLE }
        for (cell in cells) numbers[cell.row * cols + cell.col] = BLANK
        endpoints.forEachIndexed { index, pair ->
            // Colours are numbered from one because zero means "blank".
            for (cell in pair.cells) numbers[cell.row * cols + cell.col] = index + 1
        }

        return Solver(rows, cols, numbers, stateBudget).classify()
    }

    const val DEFAULT_STATE_BUDGET = 2_000_000

    private const val HOLE = -1
    private const val BLANK = 0

    /** Mate value marking a cell that already has two incident edges and can take no more. */
    private const val INTERIOR = -1

    private class Solver(
        private val rows: Int,
        private val cols: Int,
        numbersByPosition: IntArray,
        private val stateBudget: Int,
    ) {
        private val size = rows * cols

        /** Diagonal walk order: [keys] maps a coordinate to its position in the order. */
        private val keys = IntArray(size)
        private val cellX = IntArray(size)
        private val cellY = IntArray(size)

        /** What is written in each cell, indexed by walk order: [HOLE], [BLANK] or a colour. */
        private val table = IntArray(size)

        /** Where the frontier for each step begins: the first cell not yet settled. */
        private val start = IntArray(size + 1)

        /**
         * Per cell: itself when untouched, the far end of its half-finished path when it has one
         * edge, or [INTERIOR] when it has two and is done.
         */
        private val mates = IntArray(size) { it }

        private val undoCell = IntArray(8 * size + 64)
        private val undoValue = IntArray(8 * size + 64)
        private var undoTop = 0

        /** Solution count per frontier state, saturated at two — nothing needs a finer answer. */
        private val memo = Array(size) { HashMap<String, Int>() }
        private var states = 0
        private var gaveUp = false

        init {
            var x = 0
            var y = 0
            var cellKey = 0
            while (true) {
                cellX[cellKey] = x
                cellY[cellKey] = y
                keys[y * cols + x] = cellKey
                cellKey++
                if (cellKey == size) break
                do {
                    x--
                    y++
                    if (x < 0) {
                        x = y
                        y = 0
                    }
                } while (x < 0 || x >= cols || y < 0 || y >= rows)
            }
            for (i in 0 until size) {
                val cx = cellX[i]
                val cy = cellY[i]
                // numbersByPosition is in row-major order; everything below indexes by walk order.
                table[i] = numbersByPosition[cy * cols + cx]
                start[i] = if (cy > 0) key(cx, cy - 1) else if (cx > 0) key(cx - 1, cy) else 0
            }
            start[size] = size
        }

        private fun key(x: Int, y: Int) = keys[y * cols + x]

        fun classify(): Verdict {
            for (k in 0 until size) if (table[k] == HOLE) mates[k] = INTERIOR
            val count = solve(0)
            return when {
                gaveUp -> Verdict.UNDECIDED
                count == 0 -> Verdict.NONE
                count == 1 -> Verdict.UNIQUE
                else -> Verdict.MULTIPLE
            }
        }

        /** Completions of the board from [cellKey] on, given the current frontier. */
        private fun solve(cellKey: Int): Int {
            if (cellKey > 0) {
                // Cells that just fell behind the frontier can never gain another edge, so their
                // degree is now final: a blank must be untouched or interior, a numbered cell must
                // have picked up its one line.
                for (settled in start[cellKey - 1] until start[cellKey]) {
                    when (table[settled]) {
                        HOLE -> continue
                        BLANK -> if (mates[settled] != INTERIOR && mates[settled] != settled) return 0
                        else -> if (mates[settled] == settled) return 0
                    }
                }
            }
            if (cellKey == size) return 1

            val frontier = frontierKey(cellKey)
            memo[cellKey][frontier]?.let { return it }
            if (states >= stateBudget) {
                gaveUp = true
                return 0
            }
            states++
            val count = connect(cellKey)
            memo[cellKey][frontier] = count
            return count
        }

        private fun frontierKey(cellKey: Int): String {
            val from = start[cellKey]
            val chars = CharArray(cellKey - from)
            for (i in chars.indices) chars[i] = (mates[from + i] + 1).toChar()
            return String(chars)
        }

        /** Sums the completions over the four ways this cell can join its left and upper neighbour. */
        private fun connect(cellKey: Int): Int {
            if (table[cellKey] == HOLE) return solve(cellKey + 1)

            val x = cellX[cellKey]
            val y = cellY[cellKey]
            var left = if (x > 0) key(x - 1, y) else -1
            var up = if (y > 0) key(x, y - 1) else -1
            if (left >= 0 && table[left] == HOLE) left = -1
            if (up >= 0 && table[up] == HOLE) up = -1

            val undoMark = undoTop
            var count = solve(cellKey + 1)
            if (up >= 0) {
                if (unite(cellKey, up)) count = saturate(count + solve(cellKey + 1))
                undo(undoMark)
            }
            if (left >= 0) {
                if (unite(cellKey, left)) {
                    count = saturate(count + solve(cellKey + 1))
                    if (up >= 0 && unite(cellKey, up)) {
                        count = saturate(count + solve(cellKey + 1))
                    }
                }
                undo(undoMark)
            }
            return count
        }

        /**
         * Joins the two cells with an edge, or returns false if that breaks a rule. Either way the
         * caller must [undo] back to its mark, because a rejected join still edited [mates].
         */
        private fun unite(cell1: Int, cell2: Int): Boolean {
            val end1 = mates[cell1]
            val end2 = mates[cell2]
            // A cell with two edges already cannot take a third.
            if (end1 == INTERIOR || end2 == INTERIOR) return false
            // Joining the two ends of one path would close a cycle.
            if (cell1 == end2 && cell2 == end1) return false

            setMate(cell1, INTERIOR)
            setMate(cell2, INTERIOR)
            setMate(end1, end2)
            setMate(end2, end1)

            // An endpoint cell carries exactly one line, so it must not have become interior.
            if (mates[cell1] == INTERIOR && table[cell1] > 0) return false
            if (mates[cell2] == INTERIOR && table[cell2] > 0) return false
            // The joined path must not run between two different colours.
            if (table[end1] > 0 && table[end2] > 0 && table[end1] != table[end2]) return false
            return true
        }

        private fun setMate(cell: Int, value: Int) {
            val previous = mates[cell]
            if (previous == value) return
            undoCell[undoTop] = cell
            undoValue[undoTop] = previous
            undoTop++
            mates[cell] = value
        }

        private fun undo(mark: Int) {
            while (undoTop > mark) {
                undoTop--
                mates[undoCell[undoTop]] = undoValue[undoTop]
            }
        }

        private fun saturate(count: Int) = if (count > 2) 2 else count
    }
}
