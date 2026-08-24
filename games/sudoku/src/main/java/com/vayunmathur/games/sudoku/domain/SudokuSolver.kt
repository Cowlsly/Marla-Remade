package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import kotlin.random.Random

/**
 * Backtracking search over one board shape, used both to fill a grid and to prove a dug puzzle
 * still has exactly one answer.
 *
 * Candidates are held as bitmasks per row, column and box, so testing whether a digit fits is three
 * AND operations rather than a scan of 3 x [BoardSize.side] cells. The search always expands the
 * blank cell with the fewest candidates first (minimum remaining values); on a 9x9 that is the
 * difference between a generator that finishes in milliseconds and one that visibly stalls the
 * board while [countSolutions] runs once per dug cell.
 *
 * An instance owns mutable scratch state, so it is not safe to share across threads and not
 * reentrant. Create one per generation pass and keep it on a background dispatcher.
 */
class SudokuSolver(private val size: BoardSize) {
    private val side = size.side
    private val cells = size.cellCount
    private val full = (1 shl side) - 1
    private val boxOf = IntArray(cells) { size.boxOf(it) }

    private val grid = IntArray(cells)
    private val rowMask = IntArray(side)
    private val colMask = IntArray(side)
    private val boxMask = IntArray(side)

    /**
     * Number of ways [initial] can be completed, stopping once [limit] have been found.
     *
     * The result is "at least this many", not an exact total: subtree counts are added whole, so a
     * generous [limit] can be overshot. Callers checking uniqueness want to know "one, or more than
     * one" and never the true total, which for a heavily dug grid can run to millions. The default
     * [limit] of 2 is exactly that question — a return of 1 means unique.
     */
    fun countSolutions(initial: List<Int>, limit: Int = 2): Int {
        if (!load(initial)) return 0
        return count(limit)
    }

    /**
     * One completion of [initial], or null if it has none.
     *
     * Passing an [rng] shuffles the digit order at every branch, which is what turns a blank grid
     * into an arbitrary filled board instead of the same canonical one every time.
     */
    fun solve(initial: List<Int>, rng: Random? = null): List<Int>? {
        if (!load(initial)) return null
        return if (search(rng)) grid.toList() else null
    }

    /** Seeds the masks from [initial]. False if [initial] already breaks a constraint. */
    private fun load(initial: List<Int>): Boolean {
        grid.fill(0)
        rowMask.fill(0)
        colMask.fill(0)
        boxMask.fill(0)
        for (index in 0 until cells) {
            val digit = initial[index]
            if (digit != 0 && !place(index, digit)) return false
        }
        return true
    }

    private fun count(limit: Int): Int {
        val index = bestCell()
        if (index == -1) return 1
        var mask = candidates(index)
        var total = 0
        while (mask != 0) {
            val bit = mask and (-mask)
            mask = mask xor bit
            place(index, bitToDigit(bit))
            total += count(limit)
            unplace(index)
            if (total >= limit) return total
        }
        return total
    }

    private fun search(rng: Random?): Boolean {
        val index = bestCell()
        if (index == -1) return true
        val digits = digitsOf(candidates(index))
        for (digit in if (rng == null) digits else digits.shuffled(rng)) {
            place(index, digit)
            if (search(rng)) return true
            unplace(index)
        }
        return false
    }

    /**
     * The blank cell with the fewest candidates, or -1 when the grid is full.
     *
     * A cell with no candidates at all is returned immediately: it is a dead end, and reporting it
     * lets the caller fail on the spot rather than branching on the rest of the board first.
     */
    private fun bestCell(): Int {
        var best = -1
        var bestCount = side + 1
        for (index in 0 until cells) {
            if (grid[index] != 0) continue
            val count = Integer.bitCount(candidates(index))
            if (count < bestCount) {
                bestCount = count
                best = index
                if (count <= 1) break
            }
        }
        return best
    }

    private fun candidates(index: Int): Int =
        full and (rowMask[index / side] or colMask[index % side] or boxMask[boxOf[index]]).inv()

    /** Assumes [digit] is a candidate at [index]; the search only ever offers candidates. */
    private fun place(index: Int, digit: Int): Boolean {
        val bit = 1 shl (digit - 1)
        val row = index / side
        val col = index % side
        val box = boxOf[index]
        if ((rowMask[row] or colMask[col] or boxMask[box]) and bit != 0) return false
        grid[index] = digit
        rowMask[row] = rowMask[row] or bit
        colMask[col] = colMask[col] or bit
        boxMask[box] = boxMask[box] or bit
        return true
    }

    private fun unplace(index: Int) {
        val digit = grid[index]
        if (digit == 0) return
        val bit = (1 shl (digit - 1)).inv()
        grid[index] = 0
        rowMask[index / side] = rowMask[index / side] and bit
        colMask[index % side] = colMask[index % side] and bit
        boxMask[boxOf[index]] = boxMask[boxOf[index]] and bit
    }

    private fun digitsOf(mask: Int): List<Int> {
        val out = ArrayList<Int>(side)
        var remaining = mask
        while (remaining != 0) {
            val bit = remaining and (-remaining)
            remaining = remaining xor bit
            out.add(bitToDigit(bit))
        }
        return out
    }

    private fun bitToDigit(bit: Int): Int = Integer.numberOfTrailingZeros(bit) + 1
}
