package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads the shipped packs straight off disk. `LevelPack.init` needs a Context to reach the asset
 * manager, which a JVM unit test has no way to provide.
 */
object PackFixtures {

    private val packsDir = File("src/main/assets/packs")

    fun packNames(): List<String> = packsDir.listFiles()!!.map { it.name }.sorted()

    fun levels(packName: String): List<LevelData> {
        val obj = Json.parseToJsonElement(packsDir.resolve(packName).readText()).jsonObject
        return obj["levels"]!!.jsonArray.map { levelFrom(it.jsonObject) }
    }

    fun allLevels(): List<LevelData> = packNames().flatMap { levels(it) }

    private fun levelFrom(json: kotlinx.serialization.json.JsonObject): LevelData {
        val rows = json["rows"]!!.jsonPrimitive.int
        val cols = json["cols"]!!.jsonPrimitive.int
        val cells = json["cells"]?.jsonArray?.map { cellFrom(it) }?.toSet()
            ?: buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }
        val endpoints = json["endpoints"]!!.jsonArray.mapIndexed { index, element ->
            val ep = element.jsonObject
            EndpointPair(
                ep["color"]?.jsonPrimitive?.intOrNull ?: index,
                ep["cells"]!!.jsonArray.map { cellFrom(it) },
            )
        }
        return LevelData(
            id = json["id"]!!.jsonPrimitive.content,
            rows = rows,
            cols = cols,
            cells = cells,
            adjacency = computeAdjacency(cells),
            renderPositions = null,
            endpoints = endpoints,
            bridges = emptySet(),
            optimalMoves = json["optimalMoves"]!!.jsonPrimitive.int,
        )
    }

    private fun cellFrom(element: kotlinx.serialization.json.JsonElement): CellPos {
        val pair = element.jsonArray
        return CellPos(pair[0].jsonPrimitive.int, pair[1].jsonPrimitive.int)
    }
}
