package com.vayunmathur.games.pipes.data

import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.Locale

object LevelGenerator {

    fun rectangularCells(rows: Int, cols: Int): Set<CellPos> {
        return buildSet {
            for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
        }
    }

    /**
     * Carves [cells] into pipe paths seeded by [seed], returning null if no attempt produced a
     * board with at most [maxFlows] flows that survives [shortPairViolation].
     *
     * [earlyStopProb] controls flow length: higher means more, shorter flows.
     */
    fun generateLevel(
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        maxFlows: Int,
        seed: Long,
        id: String,
        earlyStopProb: Float = 0.25f
    ): LevelData? {
        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.col } + 1

        for (attempt in 0 until 50) {
            val paths = carveCover(cells, adjacency, Random(seed + attempt), earlyStopProb) ?: continue
            if (paths.size > maxFlows) continue
            if (shortPairViolation(paths)) continue
            val endpoints = paths.mapIndexed { index, path ->
                EndpointPair(index, listOf(path.first(), path.last()))
            }
            return LevelData(
                id = id,
                rows = rows,
                cols = cols,
                cells = cells,
                adjacency = adjacency,
                renderPositions = null,
                endpoints = endpoints,
                bridges = emptySet(),
                optimalMoves = endpoints.size
            )
        }
        return null
    }

    /**
     * Path-cover with a dynamic number of flows: keep carving connectivity-preserving paths until
     * every cell is used. A port of `carve_cover` in `scripts/pipes/generate_levels.py`, which is
     * what produced the shipped packs.
     *
     * The flow count has to be an output rather than an input. Fixing it up front forces the final
     * path to be a Hamiltonian walk over whatever is left, which almost never exists above a 7x7
     * board.
     */
    private fun carveCover(
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        random: Random,
        earlyStopProb: Float
    ): List<List<CellPos>>? {
        var unmarked = cells.toSet()
        val paths = mutableListOf<List<CellPos>>()

        while (unmarked.isNotEmpty()) {
            var chosen: List<CellPos>? = null
            // A start cell can strand a region no matter how the walk goes; retry a few times
            // before declaring the whole carve a failure.
            for (retry in 0 until 8) {
                val remaining = unmarked.toMutableSet()
                val start = pickStart(remaining, adjacency, random)
                val path = mutableListOf(start)
                remaining.remove(start)

                while (remaining.isNotEmpty()) {
                    val neighbors = (adjacency[path.last()] ?: emptyList())
                        .filter { it in remaining }
                        .shuffled(random)
                    val next = neighbors.firstOrNull { candidate ->
                        isStillConnected(remaining - candidate, adjacency)
                    } ?: break
                    path.add(next)
                    remaining.remove(next)
                    if (path.size >= 3 && random.nextFloat() < earlyStopProb) break
                }

                if (path.size >= 2) {
                    chosen = path
                    unmarked = remaining
                    break
                }
            }
            paths.add(chosen ?: return null)
        }
        return paths
    }

    /** Prefers a start whose removal keeps the rest connected, so a flow never strands a region. */
    private fun pickStart(
        unmarked: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        random: Random
    ): CellPos {
        val candidates = unmarked.sortedWith(compareBy({ it.row }, { it.col })).shuffled(random)
        return candidates.firstOrNull { isStillConnected(unmarked - it, adjacency) }
            ?: candidates.first()
    }

    /**
     * Rejects boards whose pairs sit so close together that the puzzle solves itself. Mirrors
     * `short_pair_violation` in `scripts/pipes/generate_levels.py`: no touching endpoints, at most
     * one pair with a single cell between, and at most two pairs with two or fewer.
     */
    private fun shortPairViolation(paths: List<List<CellPos>>): Boolean {
        if (paths.any { it.size <= 2 }) return true
        if (paths.count { it.size == 3 } > 1) return true
        if (paths.count { it.size <= 4 } > 2) return true
        return false
    }

    private fun isStillConnected(cells: Set<CellPos>, adjacency: Map<CellPos, List<CellPos>>): Boolean {
        if (cells.size <= 1) return true
        val start = cells.first()
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<CellPos>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in adjacency[current] ?: emptyList()) {
                if (neighbor in cells && neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }
        return visited.size == cells.size
    }

    /** Max flows for a board of this size, keeping puzzles sparse with longer flows. */
    fun flowCeiling(cells: Set<CellPos>): Int = maxOf(4, (cells.size / 5f).roundToInt())

    fun generatePack(
        name: String,
        shape: String,
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        levelCount: Int,
        maxFlows: Int,
        seed: Long
    ): List<LevelData> = (0 until levelCount).mapNotNull { i ->
        val currentSeed = seed + i * 100
        val id = "${name.replace("×", "x").replace(" ", "_")}_${String.format(Locale.ROOT, "%03d", i + 1)}"
        generateLevel(cells, adjacency, maxFlows, currentSeed, id)
    }
}
