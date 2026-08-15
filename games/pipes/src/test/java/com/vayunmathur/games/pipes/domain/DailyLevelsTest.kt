package com.vayunmathur.games.pipes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyLevelsTest {

    private val day = 20_300L

    @Test
    fun sameDayGeneratesIdenticalPack() {
        val first = assertNotNull(DailyLevels.packFor(day))
        val second = assertNotNull(DailyLevels.packFor(day))
        assertEquals(first, second)
    }

    @Test
    fun consecutiveDaysDiffer() {
        val today = assertNotNull(DailyLevels.packFor(day))
        val tomorrow = assertNotNull(DailyLevels.packFor(day + 1))
        assertTrue(today.levels.map { it.endpoints } != tomorrow.levels.map { it.endpoints })
    }

    @Test
    fun packHasFiveLevelsOfRisingSize() {
        val pack = assertNotNull(DailyLevels.packFor(day))
        assertEquals(DailyLevels.LEVELS_PER_DAY, pack.levels.size)
        var previousCells = 0
        pack.levels.forEachIndexed { index, level ->
            assertEquals(DailyLevels.levelId(day, index), level.id)
            assertTrue(level.cells.size > previousCells, "level $index is not bigger than the last")
            previousCells = level.cells.size
        }
    }

    @Test
    fun optimalMovesIsTheEndpointCount() {
        val pack = assertNotNull(DailyLevels.packFor(day))
        // cells.size here would make `bestScore <= optimalMoves` trivially true and hand out a
        // star plus the optimal_win achievement for every daily win.
        for (level in pack.levels) {
            assertEquals(level.endpoints.size, level.optimalMoves, level.id)
        }
    }

    @Test
    fun endpointsAreDistinctCellsOnTheBoard() {
        val pack = assertNotNull(DailyLevels.packFor(day))
        for (level in pack.levels) {
            val endpointCells = level.endpoints.flatMap { it.cells }
            assertEquals(endpointCells.size, endpointCells.toSet().size, "${level.id} reuses a cell")
            assertTrue(endpointCells.all { it in level.cells }, "${level.id} has an off-board endpoint")
            assertTrue(level.endpoints.all { it.cells.size == 2 }, "${level.id} has a malformed pair")
        }
    }

    @Test
    fun flowsAreLongEnoughToBeInteresting() {
        // The short-pair filter and the flow ceiling both exist to stop a board becoming a swarm
        // of tiny pairs. Note endpoints can still land next to each other on a U-shaped flow —
        // the shipped packs contain a handful of those too.
        val pack = assertNotNull(DailyLevels.packFor(day))
        for (level in pack.levels) {
            assertTrue(
                level.endpoints.size <= LevelGenerator.flowCeiling(level.cells),
                "${level.id} has ${level.endpoints.size} flows, over the ceiling"
            )
            val averageFlowLength = level.cells.size.toFloat() / level.endpoints.size
            assertTrue(averageFlowLength >= 4f, "${level.id} averages $averageFlowLength cells per flow")
        }
    }
}
