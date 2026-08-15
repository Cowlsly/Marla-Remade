package com.vayunmathur.games.unblockjam.domain

import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.packFromJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The solver decides the star threshold for every generated daily, so it is checked against the
 * shipped pack, whose optimal move counts were computed independently by the original authoring
 * pipeline.
 */
class RushHourSolverTest {

    private val levels: List<LevelData> by lazy {
        val candidates = listOf(
            "src/main/assets/original_pack.json",
            "games/unblockjam/src/main/assets/original_pack.json",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull(file, "original_pack.json not found from ${File(".").absolutePath}")
        packFromJson(file.readText()).levels
    }

    @Test
    fun matchesShippedOptimalMoves() {
        // Every 7th level, so the test covers the whole difficulty range without solving all 250.
        val sample = levels.filterIndexed { index, _ -> index % 7 == 0 }
        assertTrue(sample.size > 20, "expected a meaningful sample, got ${sample.size}")
        for (level in sample) {
            assertEquals(
                level.optimalMoves,
                RushHourSolver.optimalMoves(level),
                "level ${level.id}"
            )
        }
    }

    @Test
    fun everySampledLevelIsSolvable() {
        for (level in levels.take(25)) {
            assertNotNull(RushHourSolver.optimalMoves(level), "level ${level.id} unsolvable")
        }
    }
}
