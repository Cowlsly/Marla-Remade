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
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.util.SearchResult
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

/** Symbol layer id — hit-tested in MapPage.onMapClick (before the ambient POI
 *  layer) so a tapped search pin re-selects that place. */
const val SEARCH_RESULT_LAYER_ID = "search-result-pins"
private const val SEARCH_RESULT_DOT_LAYER_ID = "search-result-dots"
private const val SEARCH_RESULT_SOURCE_ID = "search-result-geojson"

/**
 * Search-result pin overlay (Vela's `MARKERS_LAYER` analog): a [GeoJsonSource]
 * fed from the Google search results plus a [CircleLayer] "dot" and a
 * [SymbolLayer] marker glyph on top, ported to maplibre-compose declarative
 * layers exactly like [GooglePoiLayer]. Distinct from the ambient POI overlay so
 * search pins read as "your results" (a single accent colour) rather than the
 * category-coloured ambient POIs. Tap → details via [toSelectedSearchResult].
 */
@Composable
@MaplibreComposable
fun SearchResultLayer(results: List<SearchResult>) {
    val marker = remember { generateSearchMarkerBitmap() }
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            SEARCH_RESULT_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(results) {
            src.setData(GeoJsonData.Features(FeatureCollection(results.map { it.toFeature() })))
        }

        CircleLayer(
            SEARCH_RESULT_DOT_LAYER_ID,
            src,
            color = const(MaterialTheme.colorScheme.primary),
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
            SEARCH_RESULT_LAYER_ID,
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

/** Rebuild a result as a GeoJSON point feature; name/lat/lon ride along so
 *  onMapClick can re-select the place. */
private fun SearchResult.toFeature(): Feature1 = Feature1(
    Point(Position(lon, lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(title),
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lon),
            "category" to JsonPrimitive(category ?: ""),
        )
    ),
)

/**
 * Convert a hit-tested search pin feature back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] so the existing
 * `SelectedFeatureViewModel.currentPoiInfo` flow fetches the Google enrichment
 * and `GooglePoiEnrichment` renders in the sheet — no new detail path needed.
 */
fun Feature1.toSelectedSearchResult(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, Position(lng, lat))
}

/** Draw the search marker once: a white-ringed accent dot used as the
 *  SymbolLayer glyph on top of the base circle. */
private fun generateSearchMarkerBitmap(): ImageBitmap {
    val size = 48
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, size / 6f, paint)
    return bmp.asImageBitmap()
}
