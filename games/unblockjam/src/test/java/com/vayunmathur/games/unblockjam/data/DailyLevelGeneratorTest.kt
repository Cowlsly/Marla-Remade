package com.vayunmathur.games.unblockjam.data

import com.vayunmathur.games.unblockjam.domain.RushHourSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyLevelGeneratorTest {

    private val day = 20_300L

    @Test
    fun sameDayGeneratesIdenticalPack() {
        val first = assertNotNull(DailyLevelGenerator.packFor(day))
        val second = assertNotNull(DailyLevelGenerator.packFor(day))
        assertEquals(first, second)
    }

    @Test
    fun consecutiveDaysDiffer() {
        val today = assertNotNull(DailyLevelGenerator.packFor(day))
        val tomorrow = assertNotNull(DailyLevelGenerator.packFor(day + 1))
        assertTrue(today.levels.map { it.blocks } != tomorrow.levels.map { it.blocks })
    }

    @Test
    fun storedOptimalMovesMatchesTheSolver() {
        val pack = assertNotNull(DailyLevelGenerator.packFor(day))
        assertEquals(DailyLevelGenerator.LEVELS_PER_DAY, pack.levels.size)
        pack.levels.forEachIndexed { index, level ->
            assertEquals(DailyLevelGenerator.levelId(day, index), level.id)
            // The stored optimal is what the star badge compares against, so it has to be the
            // solver's answer for the board as shipped, not a placeholder.
            assertEquals(level.optimalMoves, RushHourSolver.optimalMoves(level), "level $index")
            assertTrue(level.optimalMoves > 1, "level $index is solved before the first move")
        }
    }

    @Test
    fun difficultyRises() {
        // Levels are picked from difficulty bands with a rising floor. The last level falls back
        // to the hardest layout found when its band is unreachable, so compare ends rather than
        // demanding every step is strictly harder.
        val pack = assertNotNull(DailyLevelGenerator.packFor(day))
        val moves = pack.levels.map { it.optimalMoves }
        assertTrue(moves.last() > moves.first(), "no ramp across $moves")
        assertTrue(moves[1] > moves[0] && moves[2] > moves[1], "no ramp across $moves")
    }

    @Test
    fun mainBlockSitsOnTheExitRow() {
        val pack = assertNotNull(DailyLevelGenerator.packFor(day))
        for (level in pack.levels) {
            assertEquals(level.exit.y, level.blocks[0].position.y)
            assertEquals(level.dimension.width, level.exit.x)
        }
    }
}
