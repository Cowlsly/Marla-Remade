package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.PoiCategories
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Symbol layer id for the OSM POI icons — hit-tested in MapPage.onMapClick so a
 *  POI tap selects the place (name/coord) and fetches Google rich details. */
const val MA_POIS_LAYER_ID = "ma-pois-icons"

/**
 * OUR baked OSM POI layer (P27). Placement / name / type come from the
 * `ma_pois` source-layer baked into the v5 basemap PMTiles by the generator
 * (z12–z16), replacing the runtime Google viewport scrape for ambient pins —
 * Google is now hit only for rich details when a POI is tapped
 * (see [Feature1.toSelectedMaPoi] + SelectedFeatureViewModel.currentPoiInfo).
 */
object MaPoisSource {
    /** Baked into the v5 basemap PMTiles — read from the shared base URL. */
    val PMTILES_URL: String get() = MapTileCache.BASEMAP_PMTILES_URL
    const val SOURCE_LAYER: String = "ma_pois"
    val available: Boolean get() = PMTILES_URL.isNotBlank()
}

/**
 * Draw the `ma_pois` source-layer: a category icon per feature (data-driven off
 * the numeric `type`, see [PoiCategories]), from z12 up. Reads the SAME shared
 * [VectorSource] the admin/transit overlays use (single source — a second source
 * on the same PMTiles triggers a directory parse error). The disc icons are
 * runtime Canvas bitmaps (no sprite/glyph assets needed), so this renders even
 * while the style's remote glyphs 404; the POI NAME is shown in the sheet on tap.
 */
@Composable
@MaplibreComposable
fun MaPoisLayer(source: VectorSource) {
    val icons = remember { poiIcons() }

    // Category icon, chosen by the numeric `type` (0..49, 255 = other).
    SymbolLayer(
        MA_POIS_LAYER_ID,
        source,
        sourceLayer = MaPoisSource.SOURCE_LAYER,
        minZoom = 12f,
        iconImage = switch(
            feature["type"].cast<IntValue>(),
            *PoiCategories.ALL_TYPES
                .map { t -> case(t, image(icons.getValue(t))) }
                .toTypedArray(),
            fallback = image(icons.getValue(PoiCategories.TYPE_OTHER)),
        ),
        iconSize = interpolate(
            linear(), zoom(),
            12 to const(0.35f),
            15 to const(0.5f),
            18 to const(0.7f),
        ),
    )
}

/**
 * Convert a tapped `ma_pois` feature into a selectable place. Its name comes
 * from the feature properties and its coordinate from the point geometry; we
 * reuse [SpecificFeature.GenericPlace] (name + position) so the existing
 * `SelectedFeatureViewModel.currentPoiInfo` flow fetches the Google enrichment
 * and `GooglePoiEnrichment` renders in the sheet — no new detail path needed.
 */
fun Feature1.toSelectedMaPoi(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val pos = (geometry as? Point)?.coordinates ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, Position(pos.longitude, pos.latitude))
}

/** Generate one category disc icon per POI type (Decision D5: Compose/Canvas
 *  runtime bitmaps rather than sprite assets). */
private fun poiIcons(): Map<Int, ImageBitmap> =
    PoiCategories.ALL_TYPES.associateWith { t ->
        poiDisc(PoiCategories.colorHex(t), PoiCategories.glyph(t))
    }

/** A white-ringed colour disc with a centred white glyph. */
private fun poiDisc(colorHex: String, glyph: String): ImageBitmap {
    val size = 64
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, size / 2f - 2f, paint)
    paint.color = runCatching { android.graphics.Color.parseColor(colorHex) }
        .getOrDefault(android.graphics.Color.parseColor("#5F6368"))
    canvas.drawCircle(cx, cy, size / 2f - 6f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = size * 0.46f
    paint.isFakeBoldText = true
    val fm = paint.fontMetrics
    canvas.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    return bmp.asImageBitmap()
}
