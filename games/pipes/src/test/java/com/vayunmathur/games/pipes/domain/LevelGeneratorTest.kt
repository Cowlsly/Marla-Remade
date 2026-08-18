package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LevelGeneratorTest {

    @Test
    fun everyGeneratedLevelForcesAFullBoard() {
        // The whole point of the uniqueness gate: an emitted level must not be solvable while
        // leaving cells empty, or PipesViewModel.checkWin will never fire. See issue #552.
        for (size in listOf(5, 6)) {
            val cells = LevelGenerator.rectangularCells(size, size)
            val adjacency = computeAdjacency(cells)
            var emitted = 0
            for (seed in 0 until 8) {
                val level = LevelGenerator.generateLevel(
                    cells = cells,
                    adjacency = adjacency,
                    maxFlows = LevelGenerator.flowCeiling(cells),
                    seed = 4_000L + seed * 97L,
                    id = "test_${size}_$seed",
                    earlyStopProb = 0.15f,
                ) ?: continue
                emitted++
                assertEquals(
                    NumberlinkSolver.Verdict.UNIQUE,
                    NumberlinkSolver.classify(level.cells, level.endpoints),
                    level.id,
                )
                assertEquals(level.endpoints.size, level.optimalMoves, level.id)
            }
            assertTrue(emitted > 0, "generated nothing at ${size}x$size")
        }
    }

    @Test
    fun generatedEndpointsAreDistinctCellsOnTheBoard() {
        val cells = LevelGenerator.rectangularCells(6, 6)
        val level = assertNotNull(
            LevelGenerator.generateLevel(
                cells = cells,
                adjacency = computeAdjacency(cells),
                maxFlows = LevelGenerator.flowCeiling(cells),
                seed = 4_000L,
                id = "test",
                earlyStopProb = 0.15f,
            )
        )
        val endpointCells = level.endpoints.flatMap { it.cells }
        assertEquals(endpointCells.size, endpointCells.toSet().size, "reuses a cell")
        assertTrue(endpointCells.all { it in level.cells }, "off-board endpoint")
        assertTrue(level.endpoints.all { it.cells.size == 2 }, "malformed pair")
    }
}
