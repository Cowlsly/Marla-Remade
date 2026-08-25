package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.data.transit.TransitStop
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.NumberValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Point

/** Symbol layer id — hit-tested in MapPage.onMapClick so a tapped transit stop
 *  opens its departure board. */
const val TRANSIT_STOP_LAYER_ID = "transit-stop-pins"
private const val TRANSIT_STOP_DOT_LAYER_ID = "transit-stop-dots"

/**
 * Baked GTFS stop layer. Placement, name, `motis_id` and `route_type` come from
 * the `transit_stops` source-layer in the overlay PMTiles archive, written by
 * `scripts/maps/build_transit_stops_layer.sh`.
 *
 * Every mode is drawn, but not from the same zoom: see [TRANSIT_STOP_MIN_ZOOM].
 *
 * This replaced a per-viewport `GET /api/v1/map/stops` fetch. Stops are static
 * data, so a network round-trip on every camera idle bought nothing, and the baked
 * `motis_id` means the realtime `/stoptimes` overlay still has an id to ask about
 * without that endpoint.
 */
object TransitStopsSource {
    const val SOURCE_LAYER: String = "transit_stops"
}

/** Transit teal so stops read distinctly from Google POIs (category colours),
 *  saved pins (blue) and search results (red). Reached by the modes that are
 *  neither rail-like nor ferries: cable tram, aerial lift, funicular, monorail,
 *  bus and trolleybus. */
private val TRANSIT_STOP_COLOR = Color(0xFF00897B)

/** Rail-like modes get a distinct colour from buses, so a subway entrance is
 *  visually separable from the bus stop outside it. GTFS route types: 0 tram,
 *  1 subway, 2 rail, 3 bus, 4 ferry. */
private val TRANSIT_RAIL_COLOR = Color(0xFF3949AB)
private val TRANSIT_FERRY_COLOR = Color(0xFF0277BD)

/**
 * The zoom each GTFS mode starts drawing at, listed in the same order as
 * `mode_rank` in `scripts/maps/gtfs_ingest/src/bin/transit_stops.rs` — that
 * function is already the project's statement of which modes are prominent, and it
 * is what decides a stop's `route_type` when several modes call there.
 *
 * Buses are the overwhelming majority of a planetary stops layer, millions of
 * them, and drawn from the archive floor they read as a field of dots with the rail
 * network lost inside it. So the prominent half starts at the floor and buses wait
 * until the map is zoomed in far enough for them to be individually useful.
 */
private val TRANSIT_STOP_MIN_ZOOM = listOf(
    1 to 10,  // subway / metro
    2 to 10,  // rail
    0 to 10,  // tram / light rail
    12 to 10, // monorail
    4 to 12,  // ferry
    6 to 12,  // aerial lift
    7 to 12,  // funicular
    5 to 12,  // cable tram
    3 to 15,  // bus
    11 to 15, // trolleybus
)

/** `normalize_route_type` folds every unrecognised GTFS route type onto 3, so an
 *  unknown value is already a bus by the time it reaches the tile and the fallback
 *  only has to agree with that. */
private const val TRANSIT_STOP_DEFAULT_MIN_ZOOM = 15

/**
 * Draw the `transit_stops` source-layer: a [CircleLayer] dot with a [SymbolLayer]
 * glyph on top, from z10 up, each mode appearing at its own zoom
 * ([TRANSIT_STOP_MIN_ZOOM]). Takes the shared overlay [VectorSource] the admin and
 * POI overlays also read, since they are all layers of the same archive.
 *
 * The marker is a runtime Canvas bitmap rather than a sprite asset, so it renders
 * even while the style's remote glyphs 404; [MaPoisLayer] does the same.
 *
 * Mounted only when the Transit layer toggle is on (see [MyMapLayers]); a tap →
 * the [DeparturesSheet] via [toTransitStop].
 */
@Composable
@MaplibreComposable
fun TransitStopsLayer(source: VectorSource) {
    val marker = remember { generateTransitMarkerBitmap() }

    // Per-mode min-zoom, the same shape as MaPoisLayer's per-category one. Compare
    // against `zoom()` (a NumberValue), so the Int threshold is cast to the same
    // value type for `gte` to resolve to the numeric overload.
    //
    // ONE filter for both layers below: a glyph without its dot still draws, and is
    // still a tap target that would open a departure board for a stop the map is
    // not showing.
    val minZoomForMode = switch(
        feature["route_type"].cast<IntValue>(),
        *TRANSIT_STOP_MIN_ZOOM.map { (mode, minZoom) -> case(mode, const(minZoom)) }.toTypedArray(),
        fallback = const(TRANSIT_STOP_DEFAULT_MIN_ZOOM),
    )
    val stopFilter = zoom() gte minZoomForMode.cast<NumberValue<Number>>()

    // Colour by mode. `switch` over the numeric route_type, as MyMapLayers does
    // for its own data-driven paint.
    val dotColor = switch(
        feature["route_type"].cast<IntValue>(),
        case(0, const(TRANSIT_RAIL_COLOR)),
        case(1, const(TRANSIT_RAIL_COLOR)),
        case(2, const(TRANSIT_RAIL_COLOR)),
        case(4, const(TRANSIT_FERRY_COLOR)),
        fallback = const(TRANSIT_STOP_COLOR),
    )

    CircleLayer(
        TRANSIT_STOP_DOT_LAYER_ID,
        source,
        sourceLayer = TransitStopsSource.SOURCE_LAYER,
        minZoom = 10f,
        filter = stopFilter,
        color = dotColor,
        radius = interpolate(
            linear(), zoom(),
            // z10 is the archive floor; without a stop here the ramp clamps to its
            // z12 value and the dots read far too heavy two zooms out.
            10 to const(2.5.dp),
            12 to const(4.dp),
            15 to const(6.dp),
            18 to const(8.dp),
        ),
        strokeColor = const(Color.White),
        strokeWidth = const(1.5.dp),
    )

    SymbolLayer(
        TRANSIT_STOP_LAYER_ID,
        source,
        sourceLayer = TransitStopsSource.SOURCE_LAYER,
        minZoom = 10f,
        filter = stopFilter,
        iconImage = image(marker),
        iconSize = interpolate(
            linear(), zoom(),
            10 to const(0.22f),
            12 to const(0.35f),
            15 to const(0.5f),
            18 to const(0.65f),
        ),
    )
}

/**
 * Convert a hit-tested transit-stop feature back into a [TransitStop] so the tap
 * handler can open the departure board.
 *
 * Coordinates come from the point geometry, not from properties: a baked tile
 * carries no `lat`/`lng` fields, only the geometry the tiler wrote. [TransitStop.id]
 * takes `motis_id`, which is what the realtime board queries; a stop from a feed
 * whose Transitous source name the build did not know has none, and falls back to
 * its name so the offline board still opens.
 */
fun Feature1.toTransitStop(): TransitStop? {
    val props = properties ?: return null
    val position = (geometry as? Point)?.coordinates ?: return null
    val name = props.string("name")?.ifBlank { null }
    val motisId = props.string("motis_id")?.ifBlank { null }
    val id = motisId ?: name ?: return null
    return TransitStop(
        id = id,
        name = name ?: id,
        lat = position.latitude,
        lon = position.longitude,
    )
}

/** Draw the transit marker once: a white centre dot on the teal base circle so
 *  it reads as a transit stop at pin size. */
private fun generateTransitMarkerBitmap(): ImageBitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, size / 4f, paint)
    return bmp.asImageBitmap()
}
