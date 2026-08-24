package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.data.availabilityCapFor
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
 * On top of that, two properties are steered for rather than left to chance, because random boards give
 * neither: every mirror lies on some arrow's route, and only a handful of arrows can leave at the start.
 * See [chooseCandidate].
 *
 * A generated board can still have many solutions; nothing here tries to make the order unique.
 */
object ArrowsGenerator {

    /**
     * A clearable board, or null if [attempts] rounds never reached [minPieces].
     *
     * Mirrors are placed first and permanently: arrows may not sit on them, and they bend any route
     * that crosses them, so every subsequent exit check already accounts for them.
     *
     * @param openSlack added to the cap from [availabilityCapFor], for callers that would rather have a
     *   slightly loose board than none at all. Zero asks for the strict cap.
     */
    fun generate(
        cols: Int,
        rows: Int,
        targetPieces: Int,
        mirrorCount: Int,
        rng: Random,
        minPieces: Int = (targetPieces * 2) / 3,
        attempts: Int = DEFAULT_ATTEMPTS,
        openSlack: Int = 0,
    ): ArrowsPuzzle? {
        repeat(attempts) {
            build(cols, rows, targetPieces, mirrorCount, rng, openSlack)
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
     *
     * Later rounds ask for less. Both board properties are hard rejections, and on some shapes the strict
     * pair is simply unreachable — measured across 120 levels, a fixed strict budget left about 4% of them
     * with no board at all, which is a dead level the player cannot pass. Conceding difficulty is far
     * better than that, so the concessions are ordered by how little they cost:
     *
     *  1. allow one or two more arrows to be launchable at the start;
     *  2. only then place fewer mirrors.
     *
     * Mirrors are never allowed to go *unused*, whatever the round — that was the whole point of the
     * exercise. Fewer redirectors that all matter beats four where one is scenery.
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
            val concession = CONCESSIONS[round.coerceAtMost(CONCESSIONS.lastIndex)]
            generate(
                cols = cols,
                rows = rows,
                targetPieces = targetPieces,
                mirrorCount = (mirrorCount - concession.fewerMirrors).coerceAtLeast(0),
                rng = Random(seed + round * SEED_STRIDE),
                openSlack = concession.openSlack,
            )?.let { return it }
        }
        return null
    }

    /** The most this will ever concede on the opening, once every round has been tried. */
    val MAX_OPEN_SLACK: Int get() = CONCESSIONS.maxOf { it.openSlack }

    /** How many mirrors it may drop rather than leave one unused. */
    val MAX_MIRRORS_DROPPED: Int get() = CONCESSIONS.maxOf { it.fewerMirrors }

    /** How much a given round is willing to give up. See [generateSeeded]. */
    private class Concession(val openSlack: Int, val fewerMirrors: Int)

    private val CONCESSIONS = listOf(
        // Four strict rounds before conceding anything: the cap is reachable on most shapes given enough
        // tries, and spending them here is what keeps the great majority of the ladder tight.
        Concession(openSlack = 0, fewerMirrors = 0),
        Concession(openSlack = 0, fewerMirrors = 0),
        Concession(openSlack = 0, fewerMirrors = 0),
        Concession(openSlack = 0, fewerMirrors = 0),
        Concession(openSlack = 1, fewerMirrors = 0),
        Concession(openSlack = 1, fewerMirrors = 0),
        Concession(openSlack = 2, fewerMirrors = 0),
        Concession(openSlack = 2, fewerMirrors = 1),
        Concession(openSlack = 3, fewerMirrors = 1),
        Concession(openSlack = 4, fewerMirrors = 2),
    )

    private fun build(
        cols: Int,
        rows: Int,
        targetPieces: Int,
        mirrorCount: Int,
        rng: Random,
        openSlack: Int,
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

        // Two different notions of "occupied", and conflating them is what made mirrors decorative: an
        // arrow may not be *drawn* on a mirror, but a mirror does not *block* a route - it bends it.
        // Treating mirror cells as blockers rejected every candidate whose path reached one, and since a
        // bend happens *on* the mirror cell, that was every candidate that would have used it.
        val undrawable = mirrors.keys.toMutableSet()
        val blocking = mutableSetOf<Int>()

        val pieces = mutableListOf<ArrowPiece>()
        // Escape paths depend only on the board's geometry, never on which arrows are present, so each
        // one can be kept from when its arrow was placed instead of being recomputed.
        val paths = mutableListOf<Set<Int>>()
        val unusedMirrors = mirrors.keys.toMutableSet()
        var failures = 0

        while (pieces.size < targetPieces && failures < MAX_CONSECUTIVE_FAILURES) {
            val geometry = ArrowsPuzzle(cols, rows, pieces, mirrors)
            val chosen = chooseCandidate(
                geometry = geometry,
                undrawable = undrawable,
                blocking = blocking,
                paths = paths,
                unusedMirrors = unusedMirrors,
                id = pieces.size,
                rng = rng,
            )
            if (chosen == null) {
                failures++
                continue
            }
            pieces += chosen.piece
            paths += chosen.path
            undrawable += chosen.piece.cells
            blocking += chosen.piece.cells
            unusedMirrors -= chosen.path
            failures = 0
        }

        if (pieces.isEmpty()) return null
        // A mirror nothing routes through is scenery. Throwing the board away is cheap next to shipping a
        // level whose one distinguishing feature does nothing.
        if (unusedMirrors.isNotEmpty()) return null
        if (openCount(pieces, paths) > availabilityCapFor(pieces.size) + openSlack) return null
        return ArrowsPuzzle(cols, rows, pieces, mirrors)
    }

    private class Candidate(val piece: ArrowPiece, val path: Set<Int>)

    /**
     * The most useful of several random candidates, or null if none was placeable.
     *
     * Sampling a handful and picking rather than taking the first legal one is what makes the two board
     * constraints reachable. Left to chance, mirrors are almost never on any arrow's route and most arrows
     * end up unobstructed, so nearly every board would be rejected and generation would stall.
     *
     * Scoring, in order of weight:
     *  - routing through a mirror nothing uses yet, since a board with one left over is discarded outright;
     *  - crossing existing escape paths, which is what pins other arrows and holds the opening down.
     *
     * Deliberately *not* scored: pinning only those arrows that can currently leave. It sounds like the
     * sharper objective, and measurably is not — chasing it concentrates arrows into the same region, which
     * starves later placements and leaves boards short of the arrow floor more often than it tightens them.
     */
    private fun chooseCandidate(
        geometry: ArrowsPuzzle,
        undrawable: Set<Int>,
        blocking: Set<Int>,
        paths: List<Set<Int>>,
        unusedMirrors: Set<Int>,
        id: Int,
        rng: Random,
    ): Candidate? {
        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        repeat(CANDIDATES_PER_PIECE) {
            val piece = randomPiece(geometry.cols, geometry.rows, undrawable, id, rng)
                ?: return@repeat
            val path = ArrowsRules.exitPath(geometry, piece.head, piece.direction) ?: return@repeat
            // Must still be able to leave past everything already placed - that is what makes the
            // insertion order a solution - and must not be turned back into itself, which a mirror can
            // do. An arrow blocked by its own tail can never leave, at which point the board is unwinnable.
            if (path.any { it in blocking || it in piece.cells }) return@repeat

            var score = 0
            if (path.any { it in unusedMirrors }) score += MIRROR_SCORE
            score += paths.count { existing -> piece.cells.any { it in existing } }
            if (score > bestScore) {
                bestScore = score
                best = Candidate(piece, path.toSet())
            }
        }
        return best
    }

    /**
     * How many arrows could leave a finished board immediately.
     *
     * Mirrors [ArrowsRules.travel]: an arrow is stopped by any arrow cell on its route, its own included,
     * so the test is whether its path avoids every cell any arrow sits on.
     */
    private fun openCount(pieces: List<ArrowPiece>, paths: List<Set<Int>>): Int {
        val arrowCells = pieces.flatMapTo(mutableSetOf()) { it.cells }
        return pieces.indices.count { index -> paths[index].none { it in arrowCells } }
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

    /**
     * Random candidates weighed up per arrow.
     *
     * The single biggest lever on how tangled a board comes out: more choice per arrow means more chance
     * one of them crosses an existing route. Costs a linear amount of work per arrow, which is cheap next
     * to discarding a whole board and starting again.
     */
    private const val CANDIDATES_PER_PIECE = 20

    /** Outweighs any amount of blocking: a board with an unused mirror is discarded outright. */
    private const val MIRROR_SCORE = 100

    /** Give up on a board once this many placement attempts in a row have failed. */
    private const val MAX_CONSECUTIVE_FAILURES = 200

    private const val DEFAULT_ATTEMPTS = 24
    private const val DEFAULT_ROUNDS = 12

    /** Keeps per-round seeds far apart so consecutive rounds do not rebuild near-identical boards. */
    private const val SEED_STRIDE = 1_000_003L
}
