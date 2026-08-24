package com.vayunmathur.games.nonogram.data

import kotlinx.serialization.Serializable

/**
 * Which puzzle the player is looking at.
 *
 * There is no level picker: [CASUAL] is a single "current level" counter that only ever moves
 * forward, and [DAILY] is one puzzle per calendar day shared by everyone on the same date.
 */
@Serializable
enum class GameMode { CASUAL, DAILY }

/**
 * What the player has put in a cell.
 *
 * [CROSSED] is the "definitely blank" mark. It is bookkeeping only — the win check looks at [FILLED]
 * alone — but it is how a nonogram is actually solved, so it has to persist alongside the fills.
 */
enum class CellMark { BLANK, FILLED, CROSSED }

/**
 * A puzzle plus the player's work on it.
 *
 * [filled] and [crossed] are disjoint sets of cell indices: marking a cell one way clears the other,
 * because a cell cannot be both. Storing them as sets rather than a per-cell list keeps the DataStore
 * write small — only the cells actually touched are persisted.
 */
data class NonogramGameState(
    val puzzle: NonogramPuzzle,
    val filled: Set<Int>,
    val crossed: Set<Int>,
    val mode: GameMode,
    val level: Int,
    val elapsedSeconds: Int = 0,
) {
    val size: Int get() = puzzle.size

    fun markAt(index: Int): CellMark = when (index) {
        in filled -> CellMark.FILLED
        in crossed -> CellMark.CROSSED
        else -> CellMark.BLANK
    }

    /**
     * Won when the filled cells are exactly the solution's.
     *
     * Crosses are ignored, and an over-filled grid does not count — the sets must match, not merely
     * cover.
     */
    val isWon: Boolean
        get() = filled.size == puzzle.filledCount &&
            filled.all { puzzle.solution[it] }

    /** Cells the player filled that should be blank, so the board can point out a wrong guess. */
    fun isMistake(index: Int): Boolean = index in filled && !puzzle.solution[index]

    val hasMistake: Boolean get() = filled.any { !puzzle.solution[it] }
}

/**
 * How big level [level] is.
 *
 * The ramp exists so the first few levels teach the mechanic on a grid small enough to hold in your
 * head, and so a new player is not handed a 15x15 as their introduction.
 */
fun sizeForLevel(level: Int): Int = when {
    level <= 8 -> 5
    level <= 20 -> 10
    else -> 15
}

/** Daily puzzles are a fixed size, so the challenge is comparable from one day to the next. */
const val DAILY_SIZE = 10
