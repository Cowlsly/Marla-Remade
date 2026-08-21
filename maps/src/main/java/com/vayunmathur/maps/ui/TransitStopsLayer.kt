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
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.IntValue
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
 * the `transit_stops` source-layer in the v5 basemap PMTiles, written by
 * `scripts/maps/build_transit_stops_layer.sh`.
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
 *  saved pins (blue) and search results (red). */
private val TRANSIT_STOP_COLOR = Color(0xFF00897B)

/** Rail-like modes get a distinct colour from buses, so a subway entrance is
 *  visually separable from the bus stop outside it. GTFS route types: 0 tram,
 *  1 subway, 2 rail, 3 bus, 4 ferry. */
private val TRANSIT_RAIL_COLOR = Color(0xFF3949AB)
private val TRANSIT_FERRY_COLOR = Color(0xFF0277BD)

/**
 * Draw the `transit_stops` source-layer: a [CircleLayer] dot with a [SymbolLayer]
 * glyph on top, from z11 up. Takes the SAME shared [VectorSource] the admin and
 * POI overlays use — a second source on the same PMTiles triggers a directory
 * parse error (see the note at [MaPoisLayer]).
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
        minZoom = 11f,
        color = dotColor,
        radius = interpolate(
            linear(), zoom(),
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
        minZoom = 11f,
        iconImage = image(marker),
        iconSize = interpolate(
            linear(), zoom(),
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
