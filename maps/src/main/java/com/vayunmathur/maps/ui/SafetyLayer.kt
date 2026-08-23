package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vayunmathur.maps.util.MapTileCache
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * Safety / road-furniture data source (P13). The features are baked into the
 * overlay PMTiles archive (source-layer [SOURCE_LAYER]), so the overlay reads
 * [MapTileCache.OVERLAY_PMTILES_URL] — switching to the global overlay file is a
 * one-line change in [MapTileCache].
 *
 * Each `safety` feature carries a `kind` in [KIND_SPEED_CAMERA],
 * [KIND_ALPR], [KIND_SURVEILLANCE], [KIND_STOP_SIGN], [KIND_TRAFFIC_SIGNALS],
 * which drives the category icon in [SafetyLayer].
 */
object SafetyLayersSource {
    /** Baked into the overlay PMTiles — read from the shared overlay URL. */
    val PMTILES_URL: String get() = MapTileCache.OVERLAY_PMTILES_URL
    const val SOURCE_LAYER: String = "safety"
    const val LAYER_ID: String = "safety-icons"

    const val KIND_SPEED_CAMERA = "speed_camera"
    const val KIND_ALPR = "alpr"
    const val KIND_SURVEILLANCE = "surveillance"
    const val KIND_STOP_SIGN = "stop_sign"
    const val KIND_TRAFFIC_SIGNALS = "traffic_signals"

    val available: Boolean get() = PMTILES_URL.isNotBlank()
}

/**
 * Mount the safety-layers overlay when [enabled] (the P6 LayersSheet toggle) and
 * the overlay tileset exists. A single [SymbolLayer] over the `safety` vector source
 * draws a data-driven category icon per feature `kind` (speed camera / ALPR /
 * surveillance / stop sign / traffic signals). No-op while browsing with the
 * toggle off.
 */
@Composable
@MaplibreComposable
fun SafetyLayer(enabled: Boolean) {
    if (!enabled || !SafetyLayersSource.available) return

    val icons = remember { safetyIcons() }
    val source = rememberVectorSource(SafetyLayersSource.PMTILES_URL)

    SymbolLayer(
        SafetyLayersSource.LAYER_ID,
        source,
        sourceLayer = SafetyLayersSource.SOURCE_LAYER,
        iconImage = switch(
            feature["kind"].cast<StringValue>(),
            case(SafetyLayersSource.KIND_SPEED_CAMERA, image(icons.getValue(SafetyLayersSource.KIND_SPEED_CAMERA))),
            case(SafetyLayersSource.KIND_ALPR, image(icons.getValue(SafetyLayersSource.KIND_ALPR))),
            case(SafetyLayersSource.KIND_SURVEILLANCE, image(icons.getValue(SafetyLayersSource.KIND_SURVEILLANCE))),
            case(SafetyLayersSource.KIND_STOP_SIGN, image(icons.getValue(SafetyLayersSource.KIND_STOP_SIGN))),
            case(SafetyLayersSource.KIND_TRAFFIC_SIGNALS, image(icons.getValue(SafetyLayersSource.KIND_TRAFFIC_SIGNALS))),
            fallback = image(icons.getValue(DEFAULT_ICON)),
        ),
        iconSize = interpolate(
            linear(), zoom(),
            12 to const(0.4f),
            15 to const(0.6f),
            18 to const(0.8f),
        ),
    )
}

private const val DEFAULT_ICON = "default"

/**
 * Generate the category-icon bitmaps once (Decision D5: Compose/Canvas-drawn
 * runtime bitmaps rather than sprite assets). Each is a colour-coded disc with a
 * white glyph so the kinds read apart at pin size.
 */
private fun safetyIcons(): Map<String, ImageBitmap> = mapOf(
    SafetyLayersSource.KIND_SPEED_CAMERA to discIcon("#D93025", "C"),
    SafetyLayersSource.KIND_ALPR to discIcon("#A142F4", "A"),
    SafetyLayersSource.KIND_SURVEILLANCE to discIcon("#5F6368", "V"),
    SafetyLayersSource.KIND_STOP_SIGN to discIcon("#C5221F", "S"),
    SafetyLayersSource.KIND_TRAFFIC_SIGNALS to discIcon("#F9AB00", "T"),
    DEFAULT_ICON to discIcon("#5F6368", "!"),
)

/** Draw one safety icon: a white-ringed colour disc with a centred white glyph. */
private fun discIcon(colorHex: String, glyph: String): ImageBitmap {
    val size = 64
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    // White outline ring so the disc stands off the basemap.
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, size / 2f - 2f, paint)
    // Category-coloured body.
    paint.color = android.graphics.Color.parseColor(colorHex)
    canvas.drawCircle(cx, cy, size / 2f - 6f, paint)
    // White glyph.
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = size * 0.5f
    paint.isFakeBoldText = true
    val fm = paint.fontMetrics
    canvas.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    return bmp.asImageBitmap()
}
