package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.MapsPoiVectors
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.PoiCategories
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.any
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.NumberValue
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
 *
 * When [filterTypes] is non-null and non-empty the layer is filtered to just
 * those numeric types (the browse category chips, see MapPage); null shows all.
 */
@Composable
@MaplibreComposable
fun MaPoisLayer(source: VectorSource, filterTypes: Set<Int>? = null) {
    val icons = rememberPoiIcons()

    // Category filter (browse chips):
    // selected set, or everything when no category is active.
    val typeFilter = filterTypes
        ?.takeIf { it.isNotEmpty() }
        ?.let { types -> any(*types.map { feature["type"].cast<IntValue>() eq const(it) }.toTypedArray()) }
        ?: const(true)

    // Per-category min-zoom: a POI shows only once the map is zoomed to at least
    // its category's tier (see PoiCategories.minZoom). This staggers POIs across
    // zoom — landmarks early, long-tail retail later — so each urban zoom level
    // stays similarly dense instead of the whole set decluttering (merging) away
    // as you zoom out. Mirrors the protomaps `[">=",["zoom"],["get","min_zoom"]]`
    // pattern, but keyed on the numeric `type` since the tile has no min_zoom.
    val minZoomForType = switch(
        feature["type"].cast<IntValue>(),
        *PoiCategories.ALL_TYPES
            .map { t -> case(t, const(PoiCategories.minZoom(t))) }
            .toTypedArray(),
        fallback = const(PoiCategories.DEFAULT_MIN_ZOOM),
    )
    // Compare against `zoom()` (a NumberValue); cast the Int threshold to the same
    // value type so `gte` resolves to the numeric overload. Called infix like `eq`.
    val poiFilter = all(typeFilter, zoom() gte minZoomForType.cast<NumberValue<Number>>())

    // Category icon, chosen by the numeric `type` (0..49, 255 = other).
    SymbolLayer(
        MA_POIS_LAYER_ID,
        source,
        sourceLayer = MaPoisSource.SOURCE_LAYER,
        minZoom = 12f,
        filter = poiFilter,
        iconImage = switch(
            feature["type"].cast<IntValue>(),
            *PoiCategories.ALL_TYPES
                .map { t -> case(t, image(icons.getValue(t))) }
                .toTypedArray(),
            fallback = image(icons.getValue(PoiCategories.TYPE_OTHER)),
        ),
        // Half the previous marker size (was 0.75/1.05/1.4 across z12/15/18).
        iconSize = interpolate(
            linear(), zoom(),
            12 to const(0.375f),
            15 to const(0.525f),
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
    val type = props.string("type")?.toIntOrNull()
    return SpecificFeature.GenericPlace(name, null, null, null, Position(pos.longitude, pos.latitude), poiType = type)
}

/** Build one category disc bitmap per POI type: a white-ringed colour disc with
 *  the category's Material vector icon (from [MapsPoiVectors], the one module
 *  allowed to reference Material icons) rasterized white on top. Rendered ~3x the
 *  old resolution (144px) so the enlarged markers stay crisp. */
@Composable
private fun rememberPoiIcons(): Map<Int, ImageBitmap> {
    val density = LocalDensity.current
    // rememberVectorPainter is @Composable, so build a painter per (stable) type.
    val painters: Map<Int, VectorPainter> = PoiCategories.ALL_TYPES.associateWith { t ->
        rememberVectorPainter(MapsPoiVectors.of(t))
    }
    return remember(density) {
        painters.mapValues { (t, painter) -> poiDisc(PoiCategories.colorHex(t), painter, density) }
    }
}

private fun poiDisc(colorHex: String, painter: VectorPainter, density: Density): ImageBitmap {
    val sizePx = 144
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val fill = Color(
        runCatching { android.graphics.Color.parseColor(colorHex) }
            .getOrDefault(android.graphics.Color.parseColor("#5F6368")),
    )
    val full = Size(sizePx.toFloat(), sizePx.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, full) {
        drawCircle(Color.White, radius = sizePx / 2f - 4f, center = center)
        drawCircle(fill, radius = sizePx / 2f - 11f, center = center)
        val iconSide = sizePx * 0.5f
        translate(left = (sizePx - iconSide) / 2f, top = (sizePx - iconSide) / 2f) {
            with(painter) { draw(Size(iconSide, iconSide), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
    return bitmap
}
