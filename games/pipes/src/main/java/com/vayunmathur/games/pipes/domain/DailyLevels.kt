package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import kotlin.random.Random

/**
 * The five levels of a given day, drawn from the shipped packs by a seed derived purely from the
 * epoch day, so every player on that date gets exactly the same pack without any server call.
 *
 * The levels are shipped rather than generated on the fly because the win condition needs every
 * cell owned, so a level whose pairs can be joined without filling the board is a dead end for the
 * player (issue #552). Ruling that out means proving the board has exactly one solution, which
 * [NumberlinkSolver] can do but which nothing can cheaply *generate* — the offline asset pipeline
 * throws away hundreds of candidates per keeper. The shipped packs already went through that, and
 * `ShippedPacksTest` holds them to it.
 */
object DailyLevels {

    const val LEVELS_PER_DAY = 5

    fun levelId(day: Long, index: Int) = "daily_${day}_$index"

    /**
     * Null only when the shipped packs have not been loaded yet, or hold too few levels to fill a
     * day. Levels come back smallest first, so the day ramps up in difficulty.
     */
    fun packFor(day: Long, pool: List<LevelData> = defaultPool()): LevelPack? {
        if (pool.size < LEVELS_PER_DAY) return null
        val chosen = pool
            .shuffled(Random(SEED_OFFSET + day))
            .take(LEVELS_PER_DAY)
            .sortedBy { it.cells.size }
            .mapIndexed { index, level -> level.copy(id = levelId(day, index)) }
        return LevelPack(name = "daily_$day", shape = "square", levels = chosen)
    }

    private fun defaultPool(): List<LevelData> = LevelPack.PACKS.flatMap { it.levels }

    private const val SEED_OFFSET = 500_000_000L
}
