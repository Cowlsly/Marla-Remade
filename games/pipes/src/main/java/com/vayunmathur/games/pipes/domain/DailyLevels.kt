package com.vayunmathur.games.pipes.data

/**
 * The five levels of a given day, generated from a seed derived purely from the epoch day, so
 * every player on that date gets exactly the same pack without any server or shipped asset.
 *
 * Unlike the shipped packs these are not verified to have a unique solution — the ZDD solver the
 * asset pipeline uses has no Kotlin equivalent — so some dailies admit more than one layout.
 */
object DailyLevels {

    const val LEVELS_PER_DAY = 5

    /**
     * Board size and early-stop probability per level. A lower early stop yields fewer, longer
     * flows, which is the harder puzzle — so difficulty ramps on board size and flow length
     * together. The flow count itself falls out of the carve.
     */
    private val RAMP = listOf(
        6 to 0.10f,
        7 to 0.05f,
        8 to 0.0f,
        9 to 0.0f,
        10 to 0.0f,
    )

    fun levelId(day: Long, index: Int) = "daily_${day}_$index"

    /** Null only if generation somehow fails for a level, in which case the day has no daily. */
    fun packFor(day: Long): LevelPack? {
        val levels = RAMP.mapIndexedNotNull { index, (size, earlyStop) ->
            val cells = LevelGenerator.rectangularCells(size, size)
            val adjacency = computeAdjacency(cells)
            val baseSeed = SEED_OFFSET + day * 100 + index
            // generateLevel can fail outright for a given seed; bump it until a board comes back.
            (0 until MAX_SEED_BUMPS).firstNotNullOfOrNull { bump ->
                LevelGenerator.generateLevel(
                    cells = cells,
                    adjacency = adjacency,
                    maxFlows = LevelGenerator.flowCeiling(cells),
                    seed = baseSeed + bump * SEED_BUMP,
                    id = levelId(day, index),
                    earlyStopProb = earlyStop
                )
            }
        }
        return if (levels.size == RAMP.size) {
            LevelPack(name = "daily_$day", shape = "square", levels = levels)
        } else null
    }

    private const val SEED_OFFSET = 500_000_000L
    private const val SEED_BUMP = 1_000_000L
    private const val MAX_SEED_BUMPS = 40
}
