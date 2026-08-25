package com.vayunmathur.maps.ui.map.style

import com.vayunmathur.maps.ui.theme.BasemapPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Native basemap layers suppressed at runtime.
 *
 * The base archive still carries Protomaps' own `pois`, but we draw POIs ourselves
 * from the baked `ma_pois` overlay (see MaPoisLayer), so leaving the base layer on
 * would double every pin. Keeping this in code (vs editing style.json) makes it
 * OTA-swappable per Decision D1.
 */
private val SUPPRESSED_LAYERS = setOf("pois")

/**
 * The zoom at which the base style stops drawing each road family and our own `roads` overlay
 * takes over. Must match `RoadsLayer`'s per-class table.
 *
 * A cap rather than an outright suppression, because the two layers cover different ranges: the
 * baked `roads` archive starts at z11 (a planet-wide all-classes roads layer below that is not
 * something the tiler can build -- see scripts/maps/README.md), so lower down the base is the
 * only thing that has roads at all. MapLibre renders a layer for `minzoom <= z < maxzoom`, so
 * each handover is exact and nothing draws twice.
 *
 * Two zooms, not one, because `RoadsLayer` holds the minor streets back: capping the base's
 * minor layers at the through-network zoom would leave z11-12 with nobody drawing a residential
 * street.
 */
private val BASE_ROADS_HANDOVER_ZOOM = mapOf(
    BasemapPalette.RoadFamily.Through to 11,
    BasemapPalette.RoadFamily.Minor to 13,
)

/**
 * Marks a style as already patched.
 *
 * Present because [patchStyleForHybrid] is not idempotent by construction: it splits every
 * layer into a `_base` and a `_hybrid` copy, so running it twice would produce four, and eight
 * on the third pass. Cheaper and more honest to record that it has run than to try to detect
 * it from the layer names.
 */
private const val PATCHED_MARKER = "ma:patched"

/**
 * Rewrite the bundled Protomaps style for our merged archive, and optionally recolour it dark.
 *
 * Two things happen here. Every layer is duplicated into a `_base` variant capped at z7 and a
 * `_hybrid` variant from z7 up, pointed at two sources — which is what lets a low-zoom world
 * view and a high-zoom local view come from the same archive. And in dark mode each layer's
 * colour keys are swapped for [BasemapPalette] values, so we do not carry a second copy of a
 * 3544-line style file.
 *
 * The base's road surfaces are additionally capped at their family's handover zoom, because
 * above it `RoadsLayer` draws them from our own `roads` overlay instead. See
 * [BASE_ROADS_HANDOVER_ZOOM].
 *
 * Neither source declares a `maxzoom`. It used to: the merged v5 archive unioned its inputs'
 * zoom ranges, so it advertised the overlays' z16 while its base tiles stopped at z15, and base
 * layers vanished at max zoom. The overlays now live in their own archive, so the base
 * advertises its own true maxzoom and MapLibre overzooms past it on its own.
 *
 * Returns [jsonString] unchanged if it has already been patched. A theme flip re-patches from
 * the *original* asset, not from the previous output.
 */
fun patchStyleForHybrid(
    jsonString: String,
    baseLocalUrl: String,
    hybridUrl: String,
    dark: Boolean = false,
): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(jsonString).jsonObject

    // Guard rather than trust the caller. Patching twice quietly doubles the layer count, and
    // the symptom (a slow map) points nowhere near the cause.
    if (root[PATCHED_MARKER] != null) return jsonString

    val newSources = buildJsonObject {
        putJsonObject("protomaps_base") {
            put("type", "vector")
            put("url", baseLocalUrl)
        }
        putJsonObject("protomaps_hybrid") {
            put("type", "vector")
            put("url", hybridUrl)
        }
    }

    val oldLayers = root["layers"]?.jsonArray ?: buildJsonArray {}
    val newLayers = buildJsonArray {
        oldLayers.forEach { layerElement ->
            val layer = layerElement.jsonObject
            val id = layer["id"]?.jsonPrimitive?.content ?: ""
            val type = layer["type"]?.jsonPrimitive?.content ?: ""

            // Suppress the base archive's own POIs at runtime (Decision D1): we draw
            // them from the baked `ma_pois` overlay instead, so keeping both would
            // double every pin. Dropping the source layer here (rather than editing
            // style.json) keeps it OTA-swappable. Also drops the would-be
            // _base/_hybrid variants.
            if (id in SUPPRESSED_LAYERS) return@forEach

            // Above its family's handover zoom the roads come from our own `roads`
            // overlay, which carries lanes, width and speed the base layer has no
            // attributes for. Capping the base copy is what stops every street being
            // drawn twice.
            val roadHandoverMaxZoom = BasemapPalette.roadSurfaceFamilies[id]
                ?.let { family -> BASE_ROADS_HANDOVER_ZOOM.getValue(family) }
                ?.let { handover ->
                    // Never RAISE an existing cap: a layer the style already stops earlier
                    // was meant to stop there.
                    minOf(layer["maxzoom"]?.jsonPrimitive?.intOrNull ?: handover, handover)
                }

            // Dark palette (P14): recolor the base Protomaps paint at runtime so
            // we don't duplicate the 3544-line style.json. Only colour keys are
            // swapped; width/opacity/dasharray expressions are preserved.
            val darkPaint = if (dark) darkenPaint(id, layer["paint"] as? JsonObject) else null

            if (type == "background") {
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                })
            } else {
                // Zoom 0-7: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 7)
                })
                // Zoom 7+: Hybrid (Local Only)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 7)
                    if (roadHandoverMaxZoom != null) put("maxzoom", roadHandoverMaxZoom)
                })
            }
        }
    }

    return buildJsonObject {
        root.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
        put("sources", newSources)
        put("layers", newLayers)
        put(PATCHED_MARKER, true)
    }.toString()
}

/**
 * Rebuild a layer's `paint` for the dark palette (P14): copy every property
 * verbatim and only swap the colour keys, so zoom-driven width/opacity/dasharray
 * expressions keep working. `text-halo-width` etc. are left untouched. Layers
 * without a colour key (e.g. the icon-only `roads_oneway`) come back unchanged.
 */
private fun darkenPaint(id: String, paint: JsonObject?): JsonObject? {
    if (paint == null) return null
    val base = BasemapPalette.darkFillHex(id)
    val (text, halo) = BasemapPalette.darkLabelHex(id)
    return buildJsonObject {
        paint.forEach { (k, v) ->
            when (k) {
                "background-color", "fill-color", "line-color" -> put(k, base)
                "text-color" -> put(k, text)
                "text-halo-color" -> put(k, halo)
                else -> put(k, v)
            }
        }
    }
}
