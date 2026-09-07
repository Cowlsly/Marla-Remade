package com.vayunmathur.games.unblockjam.domain

import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
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

    private fun loadPack(filename: String): LevelPack {
        val candidates = listOf(
            "src/main/assets/$filename",
            "games/unblockjam/src/main/assets/$filename",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull(file, "$filename not found from ${File(".").absolutePath}")
        return packFromJson(file.readText())
    }

    private val levels: List<LevelData> by lazy { loadPack("original_pack.json").levels }

    private val wallPacks: List<LevelPack> by lazy {
        listOf("walls_1_pack.json", "walls_2_pack.json", "walls_3_pack.json").map(::loadPack)
    }

    private val sizePacks: List<LevelPack> by lazy {
        listOf("size_7_pack.json", "size_8_pack.json", "size_9_pack.json").map(::loadPack)
    }

    @Test
    fun matchesShippedOptimalMoves() {
        // Every 7th level covers the whole difficulty range without solving all of them, plus
        // every level holding a wall: fixed blocks are new to the pack, so nothing else exercises
        // the solver's handling of them.
        val sample = levels.filterIndexed { index, level ->
            index % 7 == 0 || level.blocks.any { it.fixed }
        }
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

    @Test
    fun matchesWallPackOptimalMoves() {
        // The wall packs are machine-generated, and "c" is the star threshold, so every single
        // level is confirmed rather than sampled.
        for (pack in wallPacks) {
            assertEquals(100, pack.levels.size, "${pack.name} level count")
            for (level in pack.levels) {
                assertTrue(level.blocks.any { it.fixed }, "${pack.name} level ${level.id} has no wall")
                assertEquals(
                    level.optimalMoves,
                    RushHourSolver.optimalMoves(level),
                    "${pack.name} level ${level.id}"
                )
            }
        }
    }

    @Test
    fun matchesSizePackOptimalMoves() {
        // These are the only packs on boards other than 6x6, and the only ones whose exit is not
        // on the middle row, so the board shape and the main block's row are pinned down here as
        // well as the move count. Board.of returns null when the two disagree.
        for ((pack, side) in sizePacks.zip(listOf(7, 8, 9))) {
            assertEquals(100, pack.levels.size, "${pack.name} level count")
            for (level in pack.levels) {
                assertEquals(side, level.dimension.width, "${pack.name} level ${level.id} width")
                assertEquals(side, level.dimension.height, "${pack.name} level ${level.id} height")
                assertEquals(
                    level.exit.y,
                    level.blocks[0].position.y,
                    "${pack.name} level ${level.id} main block is off the exit row"
                )
                assertEquals(
                    level.optimalMoves,
                    RushHourSolver.optimalMoves(level),
                    "${pack.name} level ${level.id}"
                )
            }
        }
    }

    @Test
    fun levelIdsAreUniqueAcrossPacks() {
        // Ids key the persisted LevelStats map, so a collision across packs would silently share
        // one player's progress between two different levels.
        val ids = (listOf(loadPack("original_pack.json")) + wallPacks + sizePacks)
            .flatMap { pack -> pack.levels.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "duplicate level ids across packs")
    }
}
