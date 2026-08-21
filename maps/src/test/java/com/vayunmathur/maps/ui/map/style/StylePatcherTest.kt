package com.vayunmathur.maps.ui.map.style

import com.vayunmathur.maps.ui.theme.BasemapPalette
import com.vayunmathur.maps.ui.theme.toStyleHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The style patch, which runs on a 3544-line asset on every theme flip and is the one piece of
 * this app that can make the basemap disappear.
 *
 * The property that matters most is that patching twice does not double the layers. It splits
 * every layer into a `_base` and a `_hybrid` copy, so a second pass would produce four and a
 * third eight — and the symptom is just a map that gets slower, which points nowhere near here.
 */
class StylePatcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A miniature style with one of each layer shape the real one contains. */
    private val style = """
        {
          "version": 8,
          "name": "test",
          "sources": { "old": { "type": "vector", "url": "gone" } },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#fff" } },
            { "id": "water", "type": "fill", "paint": { "fill-color": "#aaf" } },
            { "id": "roads_highway", "type": "line", "paint": { "line-color": "#888", "line-width": 3 } },
            { "id": "places_locality", "type": "symbol",
              "paint": { "text-color": "#111", "text-halo-color": "#fff", "text-halo-width": 2 } },
            { "id": "roads_oneway", "type": "symbol", "layout": { "icon-image": "arrow" } },
            { "id": "pois", "type": "symbol", "paint": { "text-color": "#222" } }
          ]
        }
    """.trimIndent()

    private fun patch(dark: Boolean = false, input: String = style): JsonObject =
        json.parseToJsonElement(
            patchStyleForHybrid(input, "pmtiles://base", "pmtiles://hybrid", dark)
        ).jsonObject

    private fun layerIds(root: JsonObject): List<String> =
        root["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun layer(root: JsonObject, id: String): JsonObject? =
        root["layers"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == id }

    @Test
    fun `every non-background layer is split into a base and a hybrid variant`() {
        val ids = layerIds(patch())
        // background stays single; pois is dropped; the other four split in two.
        assertEquals(listOf("background") + listOf(
            "water_base", "water_hybrid",
            "roads_highway_base", "roads_highway_hybrid",
            "places_locality_base", "places_locality_hybrid",
            "roads_oneway_base", "roads_oneway_hybrid",
        ), ids)
    }

    /** The whole reason the marker exists. */
    @Test
    fun `patching twice yields the same layer count`() {
        val once = patchStyleForHybrid(style, "pmtiles://base", "pmtiles://hybrid", false)
        val twice = patchStyleForHybrid(once, "pmtiles://base", "pmtiles://hybrid", false)

        assertEquals(
            layerIds(json.parseToJsonElement(once).jsonObject),
            layerIds(json.parseToJsonElement(twice).jsonObject),
        )
    }

    @Test
    fun `patching twice is byte-identical, so it is safe to call defensively`() {
        val once = patchStyleForHybrid(style, "pmtiles://base", "pmtiles://hybrid", false)
        assertEquals(once, patchStyleForHybrid(once, "pmtiles://base", "pmtiles://hybrid", false))
    }

    /** A theme flip re-patches the ORIGINAL asset, and must produce dark output. */
    @Test
    fun `re-patching the original asset dark still recolours it`() {
        val dark = patch(dark = true)
        val water = layer(dark, "water_base")!!["paint"]!!.jsonObject
        assertEquals(
            BasemapPalette.fill(BasemapPalette.Fill.Water).toStyleHex(),
            water["fill-color"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the two zoom variants are wired to their own sources and zoom ranges`() {
        val root = patch()
        val base = layer(root, "water_base")!!
        val hybrid = layer(root, "water_hybrid")!!

        assertEquals("protomaps_base", base["source"]!!.jsonPrimitive.content)
        assertEquals(7, base["maxzoom"]!!.jsonPrimitive.content.toInt())
        assertEquals("protomaps_hybrid", hybrid["source"]!!.jsonPrimitive.content)
        assertEquals(7, hybrid["minzoom"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the original sources are replaced, not merged`() {
        val sources = patch()["sources"]!!.jsonObject
        assertEquals(setOf("protomaps_base", "protomaps_hybrid"), sources.keys)
        // Both capped at 15: the base data stops there while the merged archive advertises 16,
        // so without the cap base layers vanish at max zoom instead of overzooming.
        for (name in sources.keys) {
            assertEquals(15, sources[name]!!.jsonObject["maxzoom"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `suppressed layers are dropped entirely, including their zoom variants`() {
        val ids = layerIds(patch())
        assertTrue(ids.none { it.startsWith("pois") }, "pois survived: $ids")
    }

    @Test
    fun `light mode leaves paint untouched`() {
        val water = layer(patch(dark = false), "water_base")!!["paint"]!!.jsonObject
        assertEquals("#aaf", water["fill-color"]!!.jsonPrimitive.content)
    }

    /** Only colour keys are swapped; widths and other expressions have to survive. */
    @Test
    fun `dark mode preserves non-colour paint properties`() {
        val road = layer(patch(dark = true), "roads_highway_base")!!["paint"]!!.jsonObject
        assertEquals(3, road["line-width"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            BasemapPalette.fill(BasemapPalette.Fill.RoadMajor).toStyleHex(),
            road["line-color"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `dark mode recolours label text and halo separately`() {
        val label = layer(patch(dark = true), "places_locality_base")!!["paint"]!!.jsonObject
        val expected = BasemapPalette.label(BasemapPalette.Label.Locality)
        assertEquals(expected.text.toStyleHex(), label["text-color"]!!.jsonPrimitive.content)
        assertEquals(expected.halo.toStyleHex(), label["text-halo-color"]!!.jsonPrimitive.content)
        assertEquals(2, label["text-halo-width"]!!.jsonPrimitive.content.toInt())
    }

    /** An icon-only layer has no colour key, so it must come back unchanged rather than broken. */
    @Test
    fun `a layer with no paint survives dark mode`() {
        val oneway = layer(patch(dark = true), "roads_oneway_base")
        assertNotNull(oneway)
        assertNull(oneway["paint"])
        assertEquals("arrow", oneway["layout"]!!.jsonObject["icon-image"]!!.jsonPrimitive.content)
    }

    @Test
    fun `top-level style keys other than sources and layers are preserved`() {
        val root = patch()
        assertEquals(8, root["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("test", root["name"]!!.jsonPrimitive.content)
    }

    /**
     * Every id in the real bundled style resolves to a role. The fill roles fall through to
     * `Other` by design, so what this actually guards is that resolution never throws and never
     * returns something outside the enum.
     */
    @Test
    fun `every layer id in the miniature style resolves to a role`() {
        for (id in listOf(
            "background", "water", "roads_highway", "places_locality", "roads_oneway", "pois",
        )) {
            assertTrue(BasemapPalette.fillRole(id) in BasemapPalette.Fill.entries)
            assertTrue(BasemapPalette.labelRole(id) in BasemapPalette.Label.entries)
        }
    }
}
