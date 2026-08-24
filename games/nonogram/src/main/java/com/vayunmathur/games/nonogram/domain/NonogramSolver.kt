package com.vayunmathur.games.nonogram.domain

/** What is known about a cell part-way through solving. */
enum class CellState { UNKNOWN, FILLED, EMPTY }

/**
 * Line-by-line constraint propagation — the deduction a human actually does.
 *
 * For each row and column it enumerates every arrangement of that line's clues that is consistent
 * with what is already known, then keeps only the cells every arrangement agrees on. Repeating that
 * over all lines until nothing changes solves any puzzle that can be solved without guessing.
 *
 * This is a deliberately *weaker* test than "has exactly one solution". A puzzle can be unique and
 * still require a chain of hypothetical moves to crack, which is miserable on a phone. Requiring full
 * determination by line logic is what makes a generated puzzle fair, so it is the bar the generator
 * holds candidates to.
 */
object NonogramSolver {

    /**
     * Solves [rowClues] / [colClues] as far as line logic reaches.
     *
     * Returns the grid — which may still hold [CellState.UNKNOWN] cells if the puzzle needs guessing
     * — or null if the clues contradict each other and no arrangement exists.
     */
    fun solve(size: Int, rowClues: List<List<Int>>, colClues: List<List<Int>>): List<CellState>? {
        val grid = MutableList(size * size) { CellState.UNKNOWN }

        var changed = true
        while (changed) {
            changed = false
            for (row in 0 until size) {
                val indices = IntArray(size) { row * size + it }
                val progressed = refineLine(grid, indices, rowClues[row]) ?: return null
                if (progressed) changed = true
            }
            for (col in 0 until size) {
                val indices = IntArray(size) { it * size + col }
                val progressed = refineLine(grid, indices, colClues[col]) ?: return null
                if (progressed) changed = true
            }
        }
        return grid
    }

    /** True when line logic alone pins down every cell. */
    fun isLineSolvable(size: Int, rowClues: List<List<Int>>, colClues: List<List<Int>>): Boolean {
        val solved = solve(size, rowClues, colClues) ?: return false
        return solved.none { it == CellState.UNKNOWN }
    }

    /**
     * Narrows one line to the cells every legal arrangement agrees on.
     *
     * Returns whether anything new was learned, or null if the line has no legal arrangement at all.
     */
    private fun refineLine(
        grid: MutableList<CellState>,
        indices: IntArray,
        clues: List<Int>,
    ): Boolean? {
        val n = indices.size
        val known = Array(n) { grid[indices[it]] }

        // Space each clue suffix needs, including one gap between neighbours, so a placement loop
        // can stop early instead of recursing into arrangements that cannot fit.
        val minLengths = IntArray(clues.size + 1)
        for (i in clues.indices.reversed()) {
            minLengths[i] = clues[i] + if (i + 1 < clues.size) 1 + minLengths[i + 1] else 0
        }

        // Bits set in every arrangement must be filled; bits set in none must be empty.
        var andMask = -1
        var orMask = 0
        var found = false

        fun place(clueIndex: Int, from: Int, mask: Int) {
            if (clueIndex == clues.size) {
                // Everything left over has to be blank, so a known-filled cell rules this out.
                for (i in from until n) if (known[i] == CellState.FILLED) return
                found = true
                andMask = andMask and mask
                orMask = orMask or mask
                return
            }

            val len = clues[clueIndex]
            val lastStart = n - minLengths[clueIndex]
            var start = from
            while (start <= lastStart) {
                val fits = (start until start + len).none { known[it] == CellState.EMPTY }
                if (fits) {
                    val after = start + len
                    // The cell just past a block must be blank, or the block would be longer.
                    if (after >= n || known[after] != CellState.FILLED) {
                        place(clueIndex + 1, after + 1, mask or (((1 shl len) - 1) shl start))
                    }
                }
                // Sliding further would leave this cell in a gap, which a filled cell cannot be.
                if (known[start] == CellState.FILLED) break
                start++
            }
        }
        place(0, 0, 0)

        if (!found) return null

        var changed = false
        for (i in 0 until n) {
            val bit = 1 shl i
            val deduced = when {
                andMask and bit != 0 -> CellState.FILLED
                orMask and bit == 0 -> CellState.EMPTY
                else -> null
            } ?: continue
            if (grid[indices[i]] == CellState.UNKNOWN) {
                grid[indices[i]] = deduced
                changed = true
            }
        }
        return changed
    }
}
