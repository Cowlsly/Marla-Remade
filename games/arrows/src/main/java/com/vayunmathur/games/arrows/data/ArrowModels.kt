package com.vayunmathur.games.arrows.data

import kotlinx.serialization.Serializable

/** Which puzzle the player is looking at. Mirrors the nonogram split: a ladder, plus one a day. */
@Serializable
enum class GameMode { CASUAL, DAILY }

/** The four ways an arrow can travel. */
enum class Direction(val dRow: Int, val dCol: Int) {
    UP(-1, 0),
    RIGHT(0, 1),
    DOWN(1, 0),
    LEFT(0, -1);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            RIGHT -> LEFT
            DOWN -> UP
            LEFT -> RIGHT
        }
}

/**
 * A static tile that turns any arrow passing through it.
 *
 * Named for the diagonal they look like. Both are their own inverse, so an arrow entering a mirror
 * and one leaving it agree about which way the tile faces.
 */
enum class Mirror {
    /** `/` — swaps a horizontal heading for the upward one, and vice versa. */
    FORWARD,

    /** `\` — swaps a horizontal heading for the downward one, and vice versa. */
    BACK;

    fun reflect(direction: Direction): Direction = when (this) {
        FORWARD -> when (direction) {
            Direction.RIGHT -> Direction.UP
            Direction.UP -> Direction.RIGHT
            Direction.LEFT -> Direction.DOWN
            Direction.DOWN -> Direction.LEFT
        }

        BACK -> when (direction) {
            Direction.RIGHT -> Direction.DOWN
            Direction.DOWN -> Direction.RIGHT
            Direction.LEFT -> Direction.UP
            Direction.UP -> Direction.LEFT
        }
    }
}

/**
 * One arrow: a rigid multi-cell piece that occupies every cell it is drawn on.
 *
 * [cells] runs tail to head, and consecutive entries are orthogonally adjacent, so the piece is a
 * connected polyline. [direction] is where the arrowhead points, which is the heading the last
 * segment already has — that is why the head is `cells.last()` rather than a separate field.
 *
 * When tapped the piece travels head-first, the body following along the head's route, so the whole
 * arrow leaves by the path its head takes.
 */
data class ArrowPiece(
    val id: Int,
    val cells: List<Int>,
    val direction: Direction,
) {
    val head: Int get() = cells.last()
    val length: Int get() = cells.size
}

/**
 * A generated board.
 *
 * Guaranteed clearable: the generator builds it by adding arrows in the reverse of a working removal
 * order, so at least one sequence of taps empties it — see `ArrowsGenerator`.
 */
data class ArrowsPuzzle(
    val cols: Int,
    val rows: Int,
    val pieces: List<ArrowPiece>,
    val mirrors: Map<Int, Mirror> = emptyMap(),
) {
    val cellCount: Int get() = cols * rows

    fun contains(row: Int, col: Int): Boolean = row in 0 until rows && col in 0 until cols
}

/** How a tap turned out, so the ViewModel knows whether to spend a heart. */
enum class TapOutcome {
    /** The arrow flew off the board. */
    CLEARED,

    /** Something was in the way; the arrow stayed put. */
    BLOCKED,

    /** The tap did nothing at all — an already-cleared arrow, or a finished board. */
    IGNORED,
}

/**
 * A puzzle plus the player's progress on it.
 *
 * [removed] holds the ids of arrows already flown. [hearts] is the remaining margin for error;
 * reaching zero fails the level, which resets both.
 */
data class ArrowsGameState(
    val puzzle: ArrowsPuzzle,
    val removed: Set<Int>,
    val hearts: Int,
    val level: Int,
    val mode: GameMode,
    /** The arrow that was last blocked, so the board can flash it. -1 when nothing is flagged. */
    val blockedId: Int = -1,
) {
    val remaining: List<ArrowPiece> get() = puzzle.pieces.filterNot { it.id in removed }

    val isWon: Boolean get() = removed.size == puzzle.pieces.size

    val isFailed: Boolean get() = hearts <= 0 && !isWon

    val isOver: Boolean get() = isWon || isFailed

    /** Cell to arrow id, for every arrow still on the board. */
    val occupancy: Map<Int, Int>
        get() = buildMap {
            for (piece in remaining) for (cell in piece.cells) put(cell, piece.id)
        }

    fun pieceAt(cell: Int): ArrowPiece? = remaining.firstOrNull { cell in it.cells }
}

/** Hearts a level starts with. Three wrong taps and it resets. */
const val STARTING_HEARTS = 3

/**
 * Board width and height for [level].
 *
 * Grows in steps rather than continuously so a run of levels shares a shape and the player can build an
 * intuition for it before the board changes under them. Each step adds a row or a column but not both:
 * gaining one of each at once would jump the area by a third and read as a difficulty spike.
 *
 * Stops at [MAX_BOARD], where a phone-width board is already down to roughly 46dp cells.
 */
fun boardSizeForLevel(level: Int): Pair<Int, Int> = when {
    level <= 3 -> 5 to 6
    level <= 8 -> 5 to 7
    level <= 14 -> 6 to 8
    level <= 21 -> 6 to 9
    level <= 29 -> 7 to 9
    level <= 38 -> 7 to 10
    level <= 48 -> 8 to 10
    level <= 60 -> 8 to 11
    else -> MAX_BOARD
}

/** The largest board the ladder reaches, from [BIGGEST_BOARD_LEVEL] on. */
val MAX_BOARD: Pair<Int, Int> = 9 to 12

/** First level played on [MAX_BOARD]. */
const val BIGGEST_BOARD_LEVEL = 61

/**
 * Most arrows that may be launchable at once on a fresh board.
 *
 * A board where everything can go straight away is not a puzzle: the player taps at random and it comes
 * apart. Holding the opening to about a quarter of the arrows means most are pinned by something, so the
 * first move has to be found rather than guessed.
 *
 * Scaled to the arrow count rather than a flat number, because two of six is as tight as five of
 * nineteen — one flat cap would leave a small board trivial and a large one barely generatable.
 */
fun availabilityCapFor(arrowCount: Int): Int =
    ((arrowCount + AVAILABILITY_DIVISOR - 1) / AVAILABILITY_DIVISOR).coerceAtLeast(MIN_AVAILABLE)

/** Rounded up, so six arrows allow two and nineteen allow five. */
private const val AVAILABILITY_DIVISOR = 4

/** Below two there is only ever one legal move, which is a sequence to memorise rather than a puzzle. */
private const val MIN_AVAILABLE = 2

/** How many arrows [level] should hold, scaled to the board it sits on. */
fun arrowCountForLevel(level: Int): Int {
    val (cols, rows) = boardSizeForLevel(level)
    val capacity = cols * rows
    // Roughly a fifth of the cells, since the average arrow is three or four cells long.
    return (capacity / 5 + level / 4).coerceAtMost(capacity / 4)
}

/**
 * Mirrors on [level].
 *
 * None before [MIRROR_FIRST_LEVEL]: the base mechanic — reading which arrow is boxed in by which — is
 * enough to learn on its own, and redirection only makes sense once that is second nature.
 */
fun mirrorCountForLevel(level: Int): Int =
    if (level < MIRROR_FIRST_LEVEL) 0 else ((level - MIRROR_FIRST_LEVEL) / 6 + 1).coerceAtMost(4)

/** First level that introduces redirectors. */
const val MIRROR_FIRST_LEVEL = 11

/** Daily boards are a fixed shape, so the challenge is comparable day to day. */
val DAILY_BOARD: Pair<Int, Int> = 7 to 9

/** Daily arrow count, matched to [DAILY_BOARD]. */
const val DAILY_ARROWS = 14

/** Dailies always include redirectors — the player opting into one is past the tutorial levels. */
const val DAILY_MIRRORS = 2
