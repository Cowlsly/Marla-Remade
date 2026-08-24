package com.vayunmathur.games.sudoku.data

import kotlinx.serialization.Serializable

/**
 * The three board shapes on offer, each with the box geometry that goes with it.
 *
 * [boxRows] x [boxCols] must equal [side], because a Sudoku box has to hold exactly as many cells
 * as a row does — that is what makes "every digit once per box" the same strength of constraint as
 * "every digit once per row". 6x6 is the only asymmetric one (2 rows of 3), which is why the box
 * dimensions are stored rather than derived from a square root.
 */
@Serializable
enum class BoardSize(val side: Int, val boxRows: Int, val boxCols: Int) {
    FOUR(4, 2, 2),
    SIX(6, 2, 3),
    NINE(9, 3, 3);

    val cellCount: Int get() = side * side

    /** Index of the box containing [index], counting left-to-right then top-to-bottom. */
    fun boxOf(index: Int): Int {
        val row = index / side
        val col = index % side
        return (row / boxRows) * (side / boxCols) + (col / boxCols)
    }
}

/**
 * How many cells the generator leaves filled in.
 *
 * The clue counts are fractions of the board rather than absolute numbers so one difficulty means
 * roughly the same amount of deduction on a 4x4 as on a 9x9. [clueFraction] is a target, not a
 * guarantee: digging stops early if removing any remaining clue would leave the puzzle with more
 * than one solution, so an EXPERT 4x4 often lands well above its target simply because tiny grids
 * run out of removable cells.
 */
@Serializable
enum class Difficulty(val clueFraction: Double) {
    EASY(0.60),
    MEDIUM(0.46),
    HARD(0.36),
    EXPERT(0.28);

    /** Target number of givens for [size]. Never below the point where digging is pointless. */
    fun targetClues(size: BoardSize): Int =
        maxOf((size.cellCount * clueFraction).toInt(), size.side + 1)
}

/** The options chosen on the home screen, carried into puzzle generation. */
@Serializable
data class GameConfig(
    val size: BoardSize = BoardSize.NINE,
    val difficulty: Difficulty = Difficulty.MEDIUM,
) {
    /** Stats key component, so records are tracked per board shape and difficulty. */
    val variant: String get() = "${size.name}_${difficulty.name}"
}

/**
 * A generated puzzle: the clues the player starts with and the unique grid they lead to.
 *
 * Both lists are [BoardSize.cellCount] long and use 0 for "blank"; digits are 1-based. Keeping the
 * solution alongside the clues is what lets a hint fill one correct cell without re-running the
 * solver, and lets a digit that does not belong be refused as it is entered.
 */
data class Puzzle(
    val size: BoardSize,
    val difficulty: Difficulty,
    val givens: List<Int>,
    val solution: List<Int>,
)

/**
 * Everything about a game in progress.
 *
 * [entries] and [notes] are parallel to [Puzzle.givens] and are only ever read where the given is
 * 0 — a cell the puzzle supplied can't be typed over. [notes] holds pencil marks as a bitmask
 * (bit `d - 1` set means digit `d` is pencilled in) so the whole board is a flat `List<Int>` and
 * therefore cheap to snapshot onto the undo stack.
 */
data class SudokuGameState(
    val size: BoardSize,
    val difficulty: Difficulty,
    val givens: List<Int>,
    val solution: List<Int>,
    val entries: List<Int>,
    val notes: List<Int>,
    val selected: Int = -1,
    val notesMode: Boolean = false,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val hintsUsed: Int = 0,
    val isWon: Boolean = false,
    val usedUndo: Boolean = false,
) {
    val variant: String get() = "${size.name}_${difficulty.name}"

    /** The digit showing in [index], given or entered, or 0 if the cell is blank. */
    fun valueAt(index: Int): Int = givens[index].takeIf { it != 0 } ?: entries[index]

    fun isGiven(index: Int): Boolean = givens[index] != 0

    /**
     * Whether [digit] may be written into [index].
     *
     * A digit that disagrees with the solution is refused outright rather than written and flagged, so
     * the grid only ever holds correct entries. That is why there is no "wrong cell" state to render:
     * it cannot occur.
     *
     * Clearing a cell is not a write and does not go through here.
     */
    fun accepts(index: Int, digit: Int): Boolean =
        !isWon &&
            index in 0 until size.cellCount &&
            !isGiven(index) &&
            digit == solution[index]

    /** How many of [digit] are already placed, so the number pad can retire finished digits. */
    fun placedCount(digit: Int): Int = (0 until size.cellCount).count { valueAt(it) == digit }

    /** Cells with nothing in them yet, which is what a hint picks from. */
    fun blankIndices(): List<Int> = (0 until size.cellCount).filter { valueAt(it) == 0 }

    val isComplete: Boolean get() = (0 until size.cellCount).all { valueAt(it) == solution[it] }

    companion object {
        /** A fresh game from [puzzle], with every non-given cell blank. */
        fun from(puzzle: Puzzle): SudokuGameState = SudokuGameState(
            size = puzzle.size,
            difficulty = puzzle.difficulty,
            givens = puzzle.givens,
            solution = puzzle.solution,
            entries = List(puzzle.size.cellCount) { 0 },
            notes = List(puzzle.size.cellCount) { 0 },
        )
    }
}

/** The one snapshot the undo stack stores per move. */
data class SudokuSnapshot(
    val entries: List<Int>,
    val notes: List<Int>,
)
