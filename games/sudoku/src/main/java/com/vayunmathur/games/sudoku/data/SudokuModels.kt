package com.vayunmathur.games.sudoku.data

import kotlinx.serialization.Serializable

/**
 * The three board shapes on offer, each with the box geometry that goes with it.
 *
 * [boxRows] x [boxCols] must equal [side], because a Sudoku box has to hold exactly as many cells as a
 * row does — that is what makes "every digit once per box" the same strength of constraint as "every
 * digit once per row". Only 9x9 has square boxes; 6x6 uses 2x3 and 12x12 uses 3x4, which is why the box
 * dimensions are stored rather than derived from a square root.
 */
@Serializable
enum class BoardSize(val side: Int, val boxRows: Int, val boxCols: Int) {
    SIX(6, 2, 3),
    NINE(9, 3, 3),
    TWELVE(12, 3, 4);

    val cellCount: Int get() = side * side

    /** Index of the box containing [index], counting left-to-right then top-to-bottom. */
    fun boxOf(index: Int): Int {
        val row = index / side
        val col = index % side
        return (row / boxRows) * (side / boxCols) + (col / boxCols)
    }
}

/**
 * The character shown for [digit], which is 1-based.
 *
 * Past nine the symbols become letters. A 12x12 cell has to fit a pencil-mark grid of twelve
 * candidates, and "10", "11", "12" at that size are illegible; one character each keeps both the
 * entered digit and the marks readable. The solver and generator only ever deal in the numbers.
 */
fun sudokuSymbol(digit: Int): String =
    if (digit <= 9) digit.toString() else ('A' + digit - 10).toString()

/**
 * How many cells the generator leaves filled in.
 *
 * The clue counts are fractions of the board rather than absolute numbers so one difficulty means
 * roughly the same amount of deduction on a 6x6 as on a 12x12. [clueFraction] is a target, not a
 * guarantee: digging stops early if removing any remaining clue would leave the puzzle with more than
 * one solution, so a small board often lands well above its target simply because it runs out of
 * removable cells.
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
 * solution alongside the clues is what lets a hint fill or correct one cell without re-running the
 * solver, and what the completion check compares against.
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
     * True when [index] holds a digit that disagrees with the solution.
     *
     * Nothing in the UI draws this. A wrong digit goes in silently and stays there; the player finds
     * out because the puzzle refuses to complete, and a hint will fix it. It exists so [wrongIndices]
     * can find those cells.
     */
    fun isWrong(index: Int): Boolean {
        val value = valueAt(index)
        return value != 0 && value != solution[index]
    }

    /** Filled cells that disagree with the solution, which a hint fixes before filling anything new. */
    fun wrongIndices(): List<Int> = (0 until size.cellCount).filter { isWrong(it) }

    /** Cells with nothing in them yet. */
    fun blankIndices(): List<Int> = (0 until size.cellCount).filter { valueAt(it) == 0 }

    /**
     * Complete only when every cell matches the solution.
     *
     * A full grid is not enough: since wrong digits are accepted without comment, this is the only
     * thing that tells the player they have something wrong somewhere.
     */
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
