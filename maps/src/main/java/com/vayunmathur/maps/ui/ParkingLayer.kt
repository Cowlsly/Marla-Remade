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
import com.vayunmathur.maps.data.ParkingSpot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Symbol layer id — hit-tested in MapPage.onMapClick so tapping the parking pin
 *  opens the parking sheet (Vela's parking pin). */
const val PARKING_PIN_LAYER_ID = "parking-pin"
private const val PARKING_DOT_LAYER_ID = "parking-dot"
private const val PARKING_SOURCE_ID = "parking-geojson"

/** Parking blue, distinct from the saved-place and search-result pins. */
private val PARKING_COLOR = Color(0xFF1967D2)

/**
 * Parking-pin overlay (P9): a [GeoJsonSource] holding the single active
 * [ParkingSpot] plus a [CircleLayer] body and a [SymbolLayer] "P" glyph on top,
 * ported to maplibre-compose declarative layers exactly like [SavedPlacesLayer]
 * / [GooglePoiLayer]. Renders nothing when no spot is saved.
 */
@Composable
@MaplibreComposable
fun ParkingLayer(spot: ParkingSpot?) {
    val marker = remember { generateParkingBitmap() }
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            PARKING_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(spot) {
            val features = spot?.let { listOf(it.toPinFeature()) } ?: emptyList()
            src.setData(GeoJsonData.Features(FeatureCollection(features)))
        }

        CircleLayer(
            PARKING_DOT_LAYER_ID,
            src,
            color = const(PARKING_COLOR),
            radius = interpolate(
                linear(), zoom(),
                11 to const(6.dp),
                14 to const(8.dp),
                17 to const(11.dp),
            ),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )

        SymbolLayer(
            PARKING_PIN_LAYER_ID,
            src,
            iconImage = image(marker),
            iconSize = interpolate(
                linear(), zoom(),
                11 to const(0.45f),
                14 to const(0.6f),
                17 to const(0.75f),
            ),
        )
    }
}

/** Rebuild the parking spot as a GeoJSON point feature; lat/lon ride along so
 *  onMapClick can recognise the pin. */
private fun ParkingSpot.toPinFeature(): Feature1 = Feature1(
    Point(Position(lon, lat)),
    JsonObject(
        mapOf(
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lon),
        )
    ),
)

/** Draw the parking marker once: a white "P" glyph shown on top of the blue base
 *  circle. */
private fun generateParkingBitmap(): ImageBitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = size * 0.72f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    // Vertically centre the glyph using the font metrics.
    val metrics = paint.fontMetrics
    val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText("P", size / 2f, baseline, paint)
    return bmp.asImageBitmap()
}
