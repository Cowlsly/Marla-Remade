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
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiPin
import com.vayunmathur.maps.data.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Symbol layer id — hit-tested first in MapPage.onMapClick so a Google pin tap
 *  wins over basemap label features. */
const val GOOGLE_POI_LAYER_ID = "google-poi-pins"
private const val GOOGLE_POI_DOT_LAYER_ID = "google-poi-dots"
private const val GOOGLE_POI_SOURCE_ID = "google-poi-geojson"

/**
 * Custom Google-POI overlay: a [GeoJsonSource] fed from the viewport scrape plus
 * a category-coloured [CircleLayer] "dot" and a [SymbolLayer] marker on top
 * (Vela's ambient dot + icon pattern, ported to maplibre-compose declarative
 * layers). Native basemap POIs are suppressed (see MapPage.patchStyleForHybrid),
 * so these are the only POI pins on the map.
 *
 * Icons are Compose-generated bitmaps (Decision D5): the marker glyph is drawn
 * once with a [android.graphics.Canvas]; per-place colour is data-driven from
 * each feature's `color` property (derived from its category).
 */
@Composable
@MaplibreComposable
fun GooglePoiLayer(pins: List<GooglePoiPin>) {
    val marker = remember { generateMarkerBitmap() }
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            GOOGLE_POI_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(pins) {
            src.setData(GeoJsonData.Features(FeatureCollection(pins.map { it.toFeature() })))
        }

        // Category-coloured base dot (the pin body).
        CircleLayer(
            GOOGLE_POI_DOT_LAYER_ID,
            src,
            color = feature["color"].cast<StringValue>().convertToColor(),
            radius = interpolate(
                linear(), zoom(),
                11 to const(4.dp),
                14 to const(6.dp),
                17 to const(8.dp),
            ),
            strokeColor = const(Color.White),
            strokeWidth = const(1.5.dp),
        )

        // White centre glyph (generated bitmap) so the pin reads as a marker.
        SymbolLayer(
            GOOGLE_POI_LAYER_ID,
            src,
            iconImage = image(marker),
            iconSize = interpolate(
                linear(), zoom(),
                11 to const(0.35f),
                14 to const(0.5f),
                17 to const(0.65f),
            ),
        )
    }
}

/** Rebuild a pin as a GeoJSON point feature; category → display colour is baked
 *  into `color` so the CircleLayer can style it data-driven, and name/lat/lng
 *  ride along so onMapClick can re-select the place. */
private fun GooglePoiPin.toFeature(): Feature1 = Feature1(
    Point(Position(lng, lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lng),
            "category" to JsonPrimitive(category ?: ""),
            "color" to JsonPrimitive(colorForCategory(category)),
        )
    ),
)

/**
 * Convert a hit-tested pin feature back into a selectable place. We reuse
 * [SpecificFeature.GenericPlace] (name + position) so the existing
 * `SelectedFeatureViewModel.currentPoiInfo` flow fetches the Google enrichment
 * and `GooglePoiEnrichment` renders in the sheet — no new detail path needed.
 */
fun Feature1.toSelectedGooglePoi(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, Position(lng, lat))
}

/** Category → pin colour, mirroring Vela's `PoiIcons.colorFor(group)` grouping. */
private fun colorForCategory(category: String?): String {
    val c = category?.lowercase() ?: return "#EA4335"
    return when {
        listOf("restaurant", "food", "dining", "pizza", "burger", "steak", "sushi").any { it in c } -> "#EA4335"
        listOf("cafe", "coffee", "bakery", "tea", "dessert").any { it in c } -> "#F9AB00"
        listOf("bar", "pub", "night", "brew", "wine").any { it in c } -> "#A142F4"
        listOf("hotel", "lodging", "motel", "hostel", "resort").any { it in c } -> "#4285F4"
        listOf("store", "shop", "market", "mall", "grocery", "supermarket").any { it in c } -> "#1A73E8"
        listOf("gas", "fuel", "charging", "ev ").any { it in c } -> "#34A853"
        listOf("park", "garden", "trail", "recreation", "museum", "gallery").any { it in c } -> "#34A853"
        listOf("hospital", "clinic", "pharmacy", "doctor", "health", "dentist").any { it in c } -> "#D93025"
        listOf("bank", "atm", "finance").any { it in c } -> "#188038"
        listOf("school", "university", "college", "library").any { it in c } -> "#F29900"
        else -> "#5F6368"
    }
}

/** Draw the marker once: a white-ringed dot used as the SymbolLayer glyph on top
 *  of the category-coloured base circle. */
private fun generateMarkerBitmap(): ImageBitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, size / 2f - 4f, paint)
    paint.color = android.graphics.Color.parseColor("#33000000")
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(cx, cy, size / 2f - 5f, paint)
    return bmp.asImageBitmap()
}
