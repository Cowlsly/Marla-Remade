package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.data.transit.TransitStop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Symbol layer id — hit-tested in MapPage.onMapClick so a tapped transit stop
 *  opens its departure board. */
const val TRANSIT_STOP_LAYER_ID = "transit-stop-pins"
private const val TRANSIT_STOP_DOT_LAYER_ID = "transit-stop-dots"
private const val TRANSIT_STOP_SOURCE_ID = "transit-stop-geojson"

/** Transit teal so stops read distinctly from Google POIs (category colours),
 *  saved pins (blue) and search results (red). */
private val TRANSIT_STOP_COLOR = Color(0xFF00897B)

/**
 * Nearby-transit-stops overlay (P10): a [GeoJsonSource] fed from the viewport
 * Transitous fetch plus a [CircleLayer] dot and a [SymbolLayer] glyph on top,
 * ported to maplibre-compose declarative layers exactly like [GooglePoiLayer] /
 * [SavedPlacesLayer]. Mounted only when the Transit layer toggle is on (see
 * [MyMapLayers]); a tap → the [DeparturesSheet] via [toTransitStop].
 */
@Composable
@MaplibreComposable
fun TransitStopsLayer(stops: List<TransitStop>) {
    val marker = remember { generateTransitMarkerBitmap() }
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            TRANSIT_STOP_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(stops) {
            src.setData(GeoJsonData.Features(FeatureCollection(stops.map { it.toFeature() })))
        }

        CircleLayer(
            TRANSIT_STOP_DOT_LAYER_ID,
            src,
            color = const(TRANSIT_STOP_COLOR),
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
            src,
            iconImage = image(marker),
            iconSize = interpolate(
                linear(), zoom(),
                12 to const(0.35f),
                15 to const(0.5f),
                18 to const(0.65f),
            ),
        )
    }
}

/** Rebuild a stop as a GeoJSON point feature; id/name/lat/lon ride along so
 *  onMapClick can open the board for it. */
private fun TransitStop.toFeature(): Feature1 = Feature1(
    Point(Position(lon, lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lon),
        )
    ),
)

/** Convert a hit-tested transit-stop feature back into a [TransitStop] so the
 *  tap handler can open the departure board. */
fun Feature1.toTransitStop(): TransitStop? {
    val props = properties ?: return null
    val id = props.string("id")?.ifBlank { null } ?: return null
    val name = props.string("name")?.ifBlank { null } ?: id
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return TransitStop(id, name, lat, lng)
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
