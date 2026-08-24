package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.Puzzle
import kotlin.random.Random

/**
 * Fill a grid at random, then remove clues for as long as the answer stays unique.
 *
 * Generating this way rather than dealing from a shipped set means the puzzle is always new, and
 * the uniqueness check is what makes it fair: a player is never asked to guess between two valid
 * completions. The cost is one [SudokuSolver.countSolutions] call per removal attempt, which is why
 * the solver is bitmasked and MRV-ordered and why callers run this off the main thread.
 */
object SudokuGenerator {

    /**
     * A puzzle of [size] whose clue count approaches [Difficulty.targetClues].
     *
     * Digging walks the cells in a random order and keeps a removal only when the grid still has
     * exactly one solution, so the result is always solvable by deduction alone. It stops at the
     * target, or earlier if every remaining clue turns out to be load-bearing — small boards run out
     * of slack long before EXPERT's target, so the returned puzzle can hold more clues than asked
     * for. It never holds fewer.
     */
    fun generate(size: BoardSize, difficulty: Difficulty, rng: Random): Puzzle {
        val solver = SudokuSolver(size)
        val solution = solver.solve(List(size.cellCount) { 0 }, rng)
            ?: error("every empty ${size.side}x${size.side} grid has a completion")

        val working = solution.toMutableList()
        var clues = size.cellCount
        val target = difficulty.targetClues(size)

        for (index in (0 until size.cellCount).shuffled(rng)) {
            if (clues <= target) break
            val removed = working[index]
            working[index] = 0
            if (solver.countSolutions(working) == 1) {
                clues--
            } else {
                working[index] = removed
            }
        }

        return Puzzle(
            size = size,
            difficulty = difficulty,
            givens = working.toList(),
            solution = solution,
        )
    }
}
