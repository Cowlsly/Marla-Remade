package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.data.parseMaxspeed
import com.vayunmathur.maps.ui.theme.MapTokens
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.maplibre.compose.camera.CameraProjection
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/** The drawn road surface. */
const val ROADS_LAYER_ID = "roads-lines"
private const val ROADS_CASING_LAYER_ID = "roads-casing"
private const val ROADS_PROBE_LAYER_ID = "roads-probe"

/**
 * The base style layer our road surfaces are anchored below.
 *
 * Composition order puts a composable layer above every layer in the style, and the base's road
 * labels, shields and oneway arrows are near the end of it — deliberately left uncapped by
 * `StylePatcher`, since the `roads` overlay carries no `name`. An opaque road surface drawn above
 * them would paint over the labels it exists to keep. `roads_labels_minor` is the first label
 * layer after the base's road surfaces, so anchoring below its z0-7 variant puts us above every
 * base road and below every base label. `StylePatcherTest` pins that this id survives the patch.
 */
private const val ROADS_ANCHOR_BELOW = "roads_labels_minor_base"

/**
 * Our baked road layer, replacing the separate `maxspeed` layer.
 *
 * `maxspeed` existed to answer one question — what is the limit under the puck — and carried a
 * copy of every road's geometry to hang the answer on. This layer carries that geometry once and
 * also has what it takes to draw the road: `class`, `lanes`, `turn_lanes_*`, `width`, `oneway`
 * and the `bridge`/`tunnel`/`layer` flags. See `scripts/maps/build_roads_layer.sh` for the full
 * schema.
 */
object RoadsSource {
    const val SOURCE_LAYER: String = "roads"

    /** The OSM tag verbatim. `maxspeed_kmh` beside it is the parsed number, when there is one. */
    const val PROP_MAXSPEED: String = "maxspeed"

    /** `tags::get_hw_id`'s road type, 1-15. Also the routing graph's own numbering. */
    const val PROP_CLASS: String = "class"
}

/**
 * `class` values, in the four buckets a width and a fill can tell apart. The numbers are
 * `osm_ingest::tags::get_hw_id`'s, which the routing graph uses for the same roads. A `*_link`
 * ramp carries its parent's class, so it is drawn as the road it joins.
 */
private val MOTORWAY_CLASSES = listOf(1, 2)
private val MAJOR_CLASSES = listOf(3, 4, 5)
private val MINOR_CLASSES = listOf(6, 7, 8, 9, 10)

/**
 * The zoom each road class starts drawing at, and therefore the zoom the base style stops drawing
 * it at.
 *
 * Two values, not fifteen, and they are the ones `StylePatcher`'s `BASE_ROADS_HANDOVER_ZOOM` uses:
 * the base groups its road layers into the same two families, so a third threshold here would
 * open a zoom range where neither layer draws that class.
 *
 * The staggering itself is the per-feature zoom gating the tiler cannot do — it drops features by
 * density, blind to class, so at z11 a long suburban collector can outrank a motorway stretch.
 * Holding the minor streets back is what keeps z11-12 a picture of the through network. Same
 * pattern as [MaPoisLayer] and [TransitStopsLayer].
 */
private const val THROUGH_MIN_ZOOM = 11
private const val MINOR_MIN_ZOOM = 13

/** Our layer's floor: no `roads` tiles exist below this. */
private const val ROADS_MIN_ZOOM = THROUGH_MIN_ZOOM

/**
 * Draw the `roads` source-layer: a casing, the surface on top of it, and an invisible probe the
 * posted-limit query hit-tests. Takes the shared overlay [VectorSource] the admin, POI and
 * transit overlays also read, since they are all layers of the same archive.
 *
 * The two drawn layers are anchored below the base's road labels ([ROADS_ANCHOR_BELOW]); the
 * probe is not drawn at all, so its position does not matter.
 */
@Composable
@MaplibreComposable
fun RoadsLayer(source: VectorSource, tokens: MapTokens) {
    // Per-class min-zoom, compared against `zoom()` (a NumberValue), so the Int threshold is
    // cast to the same value type for `gte` to resolve to the numeric overload.
    val minZoomForClass = switch(
        feature[RoadsSource.PROP_CLASS].cast<IntValue>(),
        *(MOTORWAY_CLASSES + MAJOR_CLASSES)
            .map { case(it, const(THROUGH_MIN_ZOOM)) }.toTypedArray(),
        fallback = const(MINOR_MIN_ZOOM),
    )
    val drawn = zoom() gte minZoomForClass.cast<NumberValue<Number>>()

    Anchor.Below(ROADS_ANCHOR_BELOW) {
        LineLayer(
            ROADS_CASING_LAYER_ID,
            source,
            sourceLayer = RoadsSource.SOURCE_LAYER,
            minZoom = ROADS_MIN_ZOOM.toFloat(),
            filter = drawn,
            color = const(tokens.roads.casing),
            width = roadWidth(casing = true),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )

        LineLayer(
            ROADS_LAYER_ID,
            source,
            sourceLayer = RoadsSource.SOURCE_LAYER,
            minZoom = ROADS_MIN_ZOOM.toFloat(),
            filter = drawn,
            color = switch(
                feature[RoadsSource.PROP_CLASS].cast<IntValue>(),
                *MOTORWAY_CLASSES.map { case(it, const(tokens.roads.motorway)) }.toTypedArray(),
                *MAJOR_CLASSES.map { case(it, const(tokens.roads.major)) }.toTypedArray(),
                *MINOR_CLASSES.map { case(it, const(tokens.roads.minor)) }.toTypedArray(),
                fallback = const(tokens.roads.path),
            ),
            width = roadWidth(casing = false),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
    }

    // Deliberately NOT filtered by [drawn], and deliberately a fixed width: the posted limit
    // under the puck is a fact about the road the user is on, whether or not the style has
    // decided to draw that road at this zoom, and it must not change with the render width.
    LineLayer(
        ROADS_PROBE_LAYER_ID,
        source,
        sourceLayer = RoadsSource.SOURCE_LAYER,
        opacity = const(0f),
        width = const(2.dp),
    )
}

/**
 * Road width, ramped over zoom and stepped by class.
 *
 * MapLibre allows an `interpolate` over `zoom()` whose outputs are themselves property
 * expressions, which is what lets one layer hold the whole ramp for all four buckets instead of
 * four layers holding a quarter of it each. The casing is the same ramp, wider, so a road and
 * its outline cannot drift apart.
 */
private fun roadWidth(casing: Boolean): Expression<DpValue> {
    val extra = if (casing) 1.4f else 0f
    fun byClass(motorway: Float, major: Float, minor: Float, path: Float): Expression<DpValue> =
        switch(
            feature[RoadsSource.PROP_CLASS].cast<IntValue>(),
            *MOTORWAY_CLASSES.map { case(it, const((motorway + extra).dp)) }.toTypedArray(),
            *MAJOR_CLASSES.map { case(it, const((major + extra).dp)) }.toTypedArray(),
            *MINOR_CLASSES.map { case(it, const((minor + extra).dp)) }.toTypedArray(),
            // Paths and tracks get no casing: an outline would be wider than the path.
            fallback = const(path.dp),
        )
    return interpolate(
        linear(), zoom(),
        11 to byClass(1.2f, 0.9f, 0.5f, 0.4f),
        14 to byClass(3f, 2.2f, 1.4f, 0.8f),
        16 to byClass(6f, 4.5f, 3f, 1.2f),
        18 to byClass(12f, 9f, 6.5f, 2f),
    )
}

/**
 * Query the posted speed limit at [puck] (the snapped nav position) by projecting it to screen
 * and hit-testing the roads probe layer. Returns `null` when there is no road there, or no road
 * there carries a limit worth showing.
 *
 * Reads the RAW `maxspeed` string, not `maxspeed_kmh`. The number is the layer's styling
 * attribute and is absent for every value that is not a plain speed; the string is the only
 * thing that still carries `DE:urban` (which is 50 km/h) and the only record of whether the
 * limit was authored in mph, which decides the unit the badge renders in. [parseMaxspeed] owns
 * both of those judgements.
 */
fun queryPostedLimit(projection: CameraProjection, puck: Position): PostedLimit? {
    val offset = projection.screenLocationFromPosition(puck)
    val features = projection.queryRenderedFeatures(offset, setOf(ROADS_PROBE_LAYER_ID))
    // The MOST MAJOR road wins, not the first hit. `roads` is every road, where the layer it
    // replaced held only ways with a posted limit — so a service road or a cycleway running
    // alongside the carriageway is now also under the puck, and its 10 km/h is not the limit
    // the driver is subject to.
    return features
        .filter { it.maxspeed() != null }
        .minByOrNull { it.roadClass() ?: Int.MAX_VALUE }
        ?.let { parseMaxspeed(it.maxspeed()) }
}

private fun Feature1.maxspeed(): String? =
    (properties?.get(RoadsSource.PROP_MAXSPEED) as? JsonPrimitive)?.content?.ifBlank { null }

private fun Feature1.roadClass(): Int? =
    (properties?.get(RoadsSource.PROP_CLASS) as? JsonPrimitive)?.intOrNull
