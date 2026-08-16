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
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
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

/** Symbol layer id — hit-tested in MapPage.onMapClick so a tapped saved pin
 *  re-selects that place (Vela's `SavedPin`). */
const val SAVED_PLACE_LAYER_ID = "saved-place-pins"
private const val SAVED_PLACE_DOT_LAYER_ID = "saved-place-dots"
private const val SAVED_PLACE_SOURCE_ID = "saved-place-geojson"

/** Bookmark accent so saved pins read distinctly from the category-coloured
 *  ambient POIs and the red search-result pins. */
private val SAVED_PLACE_COLOR = Color(0xFF1A73E8)

/**
 * Saved-place overlay (Vela's `SavedPin`): a [GeoJsonSource] fed from the user's
 * saved places (Home, Work and the starred list) plus a [CircleLayer] "dot" and
 * a [SymbolLayer] bookmark glyph on top, ported to maplibre-compose declarative
 * layers exactly like [GooglePoiLayer] / [SearchResultLayer]. Tap → details via
 * [toSelectedSavedPlace], reusing [SpecificFeature.GenericPlace] so the existing
 * enrichment + place sheet render with no new detail path.
 */
@Composable
@MaplibreComposable
fun SavedPlacesLayer(places: List<SavedPlace>) {
    val marker = remember { generateSavedMarkerBitmap() }
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            SAVED_PLACE_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(places) {
            src.setData(GeoJsonData.Features(FeatureCollection(places.map { it.toPinFeature() })))
        }

        CircleLayer(
            SAVED_PLACE_DOT_LAYER_ID,
            src,
            color = const(SAVED_PLACE_COLOR),
            radius = interpolate(
                linear(), zoom(),
                11 to const(5.dp),
                14 to const(7.dp),
                17 to const(9.dp),
            ),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )

        SymbolLayer(
            SAVED_PLACE_LAYER_ID,
            src,
            iconImage = image(marker),
            iconSize = interpolate(
                linear(), zoom(),
                11 to const(0.4f),
                14 to const(0.55f),
                17 to const(0.7f),
            ),
        )
    }
}

/** Rebuild a saved place as a GeoJSON point feature; name/lat/lon ride along so
 *  onMapClick can re-select it. */
private fun SavedPlace.toPinFeature(): Feature1 = Feature1(
    Point(Position(lon, lat)),
    JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lon),
        )
    ),
)

/**
 * Convert a hit-tested saved pin feature back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] so `SelectedFeatureViewModel.currentPoiInfo`
 * fetches the Google enrichment and the place sheet renders.
 */
fun Feature1.toSelectedSavedPlace(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, Position(lng, lat))
}

/** Draw the saved marker once: a white bookmark-ish glyph on top of the base
 *  circle (a simple rounded tab reads as "saved" at pin size). */
private fun generateSavedMarkerBitmap(): ImageBitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    val w = size / 4f
    val h = size / 3f
    val left = (size - w) / 2f
    val top = (size - h) / 2f
    // Bookmark: a rounded rectangle with a notch cut from the bottom edge.
    canvas.drawRoundRect(left, top, left + w, top + h, 3f, 3f, paint)
    paint.color = android.graphics.Color.TRANSPARENT
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    val notch = android.graphics.Path().apply {
        moveTo(left, top + h)
        lineTo(left + w / 2f, top + h - h / 3f)
        lineTo(left + w, top + h)
        close()
    }
    canvas.drawPath(notch, paint)
    return bmp.asImageBitmap()
}
