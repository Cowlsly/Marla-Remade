package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.Mirror
import kotlin.random.Random

/**
 * Builds a board by adding arrows in the reverse of a working removal order.
 *
 * The key idea: when a new arrow is placed, it is only kept if it could fly out past the arrows
 * already on the board. Do that repeatedly and the insertion order, reversed, *is* a solution — the
 * last arrow added can always leave first, then the one before it, and so on. Solvability is
 * therefore structural rather than something to test for and hope about, which matters because the
 * alternative (paint a board, then search for an order) fails for the overwhelming majority of random
 * layouts.
 *
 * A generated board can still have many solutions; nothing here tries to make the order unique.
 */
object ArrowsGenerator {

    /**
     * A clearable board, or null if [attempts] rounds never reached [minPieces].
     *
     * Mirrors are placed first and permanently: arrows may not sit on them, and they bend any route
     * that crosses them, so every subsequent exit check already accounts for them.
     */
    fun generate(
        cols: Int,
        rows: Int,
        targetPieces: Int,
        mirrorCount: Int,
        rng: Random,
        minPieces: Int = (targetPieces * 2) / 3,
        attempts: Int = DEFAULT_ATTEMPTS,
    ): ArrowsPuzzle? {
        repeat(attempts) {
            build(cols, rows, targetPieces, mirrorCount, rng)
                ?.takeIf { it.pieces.size >= minPieces }
                ?.let { return it }
        }
        return null
    }

    /**
     * A board for a given [seed], retrying with derived seeds until one is found.
     *
     * Every seed is a pure function of [seed], so the same level number always yields the same board
     * however many rounds it takes — that is what lets saved progress stay valid.
     */
    fun generateSeeded(
        cols: Int,
        rows: Int,
        targetPieces: Int,
        mirrorCount: Int,
        seed: Long,
        rounds: Int = DEFAULT_ROUNDS,
    ): ArrowsPuzzle? {
        for (round in 0 until rounds) {
            generate(
                cols = cols,
                rows = rows,
                targetPieces = targetPieces,
                mirrorCount = mirrorCount,
                rng = Random(seed + round * SEED_STRIDE),
            )?.let { return it }
        }
        return null
    }

    private fun build(
        cols: Int,
        rows: Int,
        targetPieces: Int,
        mirrorCount: Int,
        rng: Random,
    ): ArrowsPuzzle? {
        val cellCount = cols * rows
        // Mirrors are kept off the border: one on an edge cell mostly deflects arrows straight back
        // out again, which reads as a decoration rather than an obstacle.
        val interior = (0 until cellCount).filter {
            val row = it / cols
            val col = it % cols
            row in 1 until rows - 1 && col in 1 until cols - 1
        }
        val mirrors = interior.shuffled(rng)
            .take(mirrorCount.coerceAtMost(interior.size))
            .associateWith { if (rng.nextBoolean()) Mirror.FORWARD else Mirror.BACK }

        val occupied = mirrors.keys.toMutableSet()
        val pieces = mutableListOf<ArrowPiece>()
        var failures = 0

        while (pieces.size < targetPieces && failures < MAX_CONSECUTIVE_FAILURES) {
            val candidate = randomPiece(cols, rows, occupied, pieces.size, rng)
            if (candidate == null) {
                failures++
                continue
            }
            // The board as it stands, which is exactly what this arrow must be able to escape past.
            val soFar = ArrowsPuzzle(cols, rows, pieces, mirrors)
            val path = ArrowsRules.exitPath(soFar, candidate.head, candidate.direction)
            val blockers = occupied
            if (path == null || path.any { it in blockers }) {
                failures++
                continue
            }
            pieces += candidate
            occupied += candidate.cells
            failures = 0
        }

        if (pieces.isEmpty()) return null
        return ArrowsPuzzle(cols, rows, pieces, mirrors)
    }

    /**
     * A random polyline of two to [MAX_LENGTH] cells that avoids [occupied] and itself.
     *
     * Walks from a random free cell, turning at random, and gives up rather than backtracking — a
     * failed walk is cheap and the caller simply tries again.
     */
    private fun randomPiece(
        cols: Int,
        rows: Int,
        occupied: Set<Int>,
        id: Int,
        rng: Random,
    ): ArrowPiece? {
        val free = (0 until cols * rows).filterNot { it in occupied }
        if (free.isEmpty()) return null

        val start = free.random(rng)
        val cells = mutableListOf(start)
        var heading = Direction.entries.random(rng)
        val targetLength = rng.nextInt(2, MAX_LENGTH + 1)

        while (cells.size < targetLength) {
            // Never immediately double back: that would put two cells of the piece on top of
            // each other, and the "polyline" would stop being one.
            val options = Direction.entries.filter { it != heading.opposite }.shuffled(rng)
            val step = options.firstNotNullOfOrNull { direction ->
                val row = cells.last() / cols + direction.dRow
                val col = cells.last() % cols + direction.dCol
                if (row !in 0 until rows || col !in 0 until cols) return@firstNotNullOfOrNull null
                val cell = row * cols + col
                if (cell in occupied || cell in cells) null else direction to cell
            } ?: break

            heading = step.first
            cells += step.second
        }

        // A single cell has no last segment, so there would be nothing for the arrowhead to follow.
        if (cells.size < 2) return null
        return ArrowPiece(id = id, cells = cells, direction = heading)
    }

    /** Longest arrow the generator will draw. Beyond this a piece dominates a small board. */
    private const val MAX_LENGTH = 5

    /** Give up on a board once this many placement attempts in a row have failed. */
    private const val MAX_CONSECUTIVE_FAILURES = 200

    private const val DEFAULT_ATTEMPTS = 24
    private const val DEFAULT_ROUNDS = 8

    /** Keeps per-round seeds far apart so consecutive rounds do not rebuild near-identical boards. */
    private const val SEED_STRIDE = 1_000_003L
}
