package com.vayunmathur.games.unblockjam.data

import com.vayunmathur.games.unblockjam.domain.RushHourSolver
import kotlin.random.Random

/**
 * The five levels of a given day, generated from a seed derived purely from the epoch day, so
 * every player on that date gets exactly the same pack.
 *
 * Placing blocks at random gives an almost always trivial board, so the layout is only the
 * starting point: the whole state space for that set of pieces is explored, and the puzzle
 * actually shipped is the hardest configuration reachable within the day-index's difficulty band.
 * That also yields the exact `optimalMoves` the star threshold needs.
 *
 * Exploring a state space per candidate is not cheap — run this off the main thread.
 */
object DailyLevelGenerator {

    const val LEVELS_PER_DAY = 5

    private const val BOARD_SIZE = 6
    private const val MAIN_LENGTH = 2

    /** Preferred optimal-move band per level in the day, giving a difficulty ramp. */
    private val DIFFICULTY_BANDS = listOf(
        3..5,
        6..8,
        9..11,
        12..13,
        14..60,
    )

    /** How many blocks besides the main one to place, per level in the day. */
    private val BLOCK_COUNTS = listOf(7, 9, 11, 11, 11)

    fun levelId(day: Long, index: Int) = "daily_${day}_$index"

    /** Null only if every layout for a level was unsolvable, which should not happen in practice. */
    fun packFor(day: Long): LevelPack? {
        val levels = mutableListOf<LevelData>()
        for (index in 0 until LEVELS_PER_DAY) {
            val band = DIFFICULTY_BANDS[index]
            // Keep the ramp rising even when the previous level overshot its band.
            val floor = maxOf(band.first, (levels.lastOrNull()?.optimalMoves ?: 0) + 1)
            val level = levelFor(
                baseSeed = SEED_OFFSET + day * 100 + index,
                blockCount = BLOCK_COUNTS[index],
                band = floor..maxOf(band.last, floor),
                id = levelId(day, index)
            ) ?: return null
            levels.add(level)
        }
        return LevelPack(name = "daily_$day", levels = levels)
    }

    /**
     * Tries random piece sets until one can produce a puzzle inside [band]. Falls back to the
     * hardest puzzle seen across all attempts, so a day always gets its pack even when the band
     * turns out to be unreachable for the sets that came up.
     */
    private fun levelFor(baseSeed: Long, blockCount: Int, band: IntRange, id: String): LevelData? {
        var fallback: LevelData? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val best = hardestUpTo(
                random = Random(baseSeed + attempt * SEED_BUMP),
                blockCount = blockCount,
                cap = band.last,
                id = id
            ) ?: continue
            if (best.optimalMoves in band) return best
            if (best.optimalMoves > (fallback?.optimalMoves ?: 1)) fallback = best
        }
        return fallback
    }

    /**
     * Lays out one random set of pieces and returns the hardest arrangement of them needing at
     * most [cap] moves, or null if the set cannot be solved at all.
     */
    private fun hardestUpTo(random: Random, blockCount: Int, cap: Int, id: String): LevelData? {
        val layout = randomLayout(random, blockCount, id) ?: return null
        val exploration = RushHourSolver.explore(layout, MAX_STATES) ?: return null

        var bestIndex = -1
        var bestMoves = 1
        exploration.distanceToGoal.forEachIndexed { index, distance ->
            if (distance < 0) return@forEachIndexed
            val moves = exploration.movesToWin(index)
            // A board solved before the player touches it is not a puzzle.
            if (moves > bestMoves && moves <= cap) {
                bestIndex = index
                bestMoves = moves
            }
        }
        if (bestIndex < 0) return null
        return exploration.levelAt(bestIndex, layout).copy(optimalMoves = bestMoves)
    }

    /** A random legal placement of the main block plus [blockCount] others. */
    private fun randomLayout(random: Random, blockCount: Int, id: String): LevelData? {
        val exitRow = random.nextInt(BOARD_SIZE)
        val blocks = mutableListOf(
            Block(
                position = Coord(random.nextInt(BOARD_SIZE - MAIN_LENGTH), exitRow),
                dimension = Dimension(MAIN_LENGTH, 1),
                fixed = false
            )
        )

        var placed = 0
        var tries = 0
        while (placed < blockCount && tries < PLACEMENT_TRIES) {
            tries++
            val horizontal = random.nextBoolean()
            val size = if (random.nextInt(3) == 0) 3 else 2
            val dim = if (horizontal) Dimension(size, 1) else Dimension(1, size)
            val position = Coord(
                random.nextInt(BOARD_SIZE - dim.width + 1),
                random.nextInt(BOARD_SIZE - dim.height + 1)
            )
            val block = Block(position, dim, fixed = false)
            if (overlapsAny(block, blocks)) continue
            // A horizontal block on the exit row can never be got out of the way, so the board
            // would be unsolvable for reasons that have nothing to do with the puzzle.
            if (horizontal && position.y == exitRow) continue
            blocks.add(block)
            placed++
        }
        if (placed < blockCount) return null

        return LevelData(
            id = id,
            dimension = Dimension(BOARD_SIZE, BOARD_SIZE),
            exit = Coord(BOARD_SIZE, exitRow),
            blocks = blocks,
            optimalMoves = 0
        )
    }

    private fun overlapsAny(block: Block, others: List<Block>): Boolean = others.any { other ->
        block.position.x < other.position.x + other.dimension.width &&
            block.position.x + block.dimension.width > other.position.x &&
            block.position.y < other.position.y + other.dimension.height &&
            block.position.y + block.dimension.height > other.position.y
    }

    private const val SEED_OFFSET = 900_000_000L
    private const val SEED_BUMP = 1_000_000L
    private const val MAX_ATTEMPTS = 40
    private const val PLACEMENT_TRIES = 200

    /** Comfortably above the ~25k states a 6x6 layout explores, so a runaway board is cut off. */
    private const val MAX_STATES = 80_000
}
