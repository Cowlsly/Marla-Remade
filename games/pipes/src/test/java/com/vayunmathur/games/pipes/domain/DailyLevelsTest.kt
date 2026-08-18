package com.vayunmathur.games.pipes.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyLevelsTest {

    private val day = 20_300L
    private val pool = PackFixtures.allLevels()

    @Test
    fun sameDayGeneratesIdenticalPack() {
        assertEquals(DailyLevels.packFor(day, pool), DailyLevels.packFor(day, pool))
    }

    @Test
    fun consecutiveDaysDiffer() {
        val today = assertNotNull(DailyLevels.packFor(day, pool))
        val tomorrow = assertNotNull(DailyLevels.packFor(day + 1, pool))
        assertTrue(today.levels.map { it.endpoints } != tomorrow.levels.map { it.endpoints })
    }

    @Test
    fun packHasFiveLevelsOfRisingSize() {
        val pack = assertNotNull(DailyLevels.packFor(day, pool))
        assertEquals(DailyLevels.LEVELS_PER_DAY, pack.levels.size)
        var previousCells = 0
        pack.levels.forEachIndexed { index, level ->
            assertEquals(DailyLevels.levelId(day, index), level.id)
            assertTrue(level.cells.size >= previousCells, "level $index is smaller than the last")
            previousCells = level.cells.size
        }
    }

    @Test
    fun optimalMovesIsTheEndpointCount() {
        val pack = assertNotNull(DailyLevels.packFor(day, pool))
        // cells.size here would make `bestScore <= optimalMoves` trivially true and hand out a
        // star plus the optimal_win achievement for every daily win.
        for (level in pack.levels) {
            assertEquals(level.endpoints.size, level.optimalMoves, level.id)
        }
    }

    @Test
    fun endpointsAreDistinctCellsOnTheBoard() {
        val pack = assertNotNull(DailyLevels.packFor(day, pool))
        for (level in pack.levels) {
            val endpointCells = level.endpoints.flatMap { it.cells }
            assertEquals(endpointCells.size, endpointCells.toSet().size, "${level.id} reuses a cell")
            assertTrue(endpointCells.all { it in level.cells }, "${level.id} has an off-board endpoint")
            assertTrue(level.endpoints.all { it.cells.size == 2 }, "${level.id} has a malformed pair")
        }
    }

    @Test
    fun everyLevelForcesAFullBoard() {
        // PipesViewModel.checkWin only fires on a full board, so a level that can be "connected"
        // any other way is a dead end for the player. See issue #552.
        for (offset in 0 until 30) {
            val pack = assertNotNull(DailyLevels.packFor(day + offset, pool), "day ${day + offset}")
            for (level in pack.levels) {
                assertTrue(
                    NumberlinkSolver.classify(level.cells, level.endpoints)
                        != NumberlinkSolver.Verdict.MULTIPLE,
                    "${level.id} can be solved without filling the board",
                )
            }
        }
    }

    @Test
    fun noPackBeforeTheShippedLevelsAreLoaded() {
        // LevelPack.PACKS is empty until MainActivity calls LevelPack.init, and a day with four
        // levels in it would break the progress bookkeeping in PipesViewModel.
        assertNull(DailyLevels.packFor(day, pool.take(DailyLevels.LEVELS_PER_DAY - 1)))
    }
}
