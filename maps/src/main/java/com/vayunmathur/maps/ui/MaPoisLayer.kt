package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.PoiIndex
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Pin id for the OSM POI icons — hit-tested in MapSurface.onMapClick so a
 *  POI tap selects the place (name/coord) and fetches Google rich details. */
const val MA_POIS_LAYER_ID = "ma-pois-icons"

private val PIN_SIZE = 26.dp

/** Category fill colours, cached: ~52 stable values, parsed once. */
private val fillCache = mutableMapOf<Int, Color>()

private fun poiFill(type: Int): Color = fillCache.getOrPut(type) {
    runCatching { Color(android.graphics.Color.parseColor(PoiCategories.colorHex(type))) }
        .getOrDefault(Color(0xFF5F6368))
}

/**
 * Ambient OSM POI overlay (P29): pins from the offline `poi_index.bin` side file
 * ([PoiIndex.inViewport]) drawn as plain Compose over VectorMap, replacing the baked `ma_pois`
 * vector-tile layer. Google is still hit only for rich details when a POI is tapped
 * (see [Feature1.toSelectedMaPoi] + SelectedFeatureViewModel.currentPoiInfo).
 *
 * This is a data-source swap, not just a rendering swap: the old layer read the `ma_pois`
 * source-layer out of the overlay PMTiles archive (z12–z16); the renderer has no vector-layer
 * API, so pins come from the offline POI index the app already ships for search. Coverage is
 * whatever the downloaded side files hold, and per-category min-zoom staggering
 * ([PoiCategories.minZoom]) replaces the tile zoom gating.
 *
 * When [filterTypes] is non-null and non-empty the pins are filtered to just those numeric
 * types (the browse category chips); null shows all.
 */
@Composable
fun MaPoisLayer(cameraState: CameraState, filterTypes: Set<Int>? = null) {
    val projection = cameraState.projection ?: return
    val zoom = cameraState.position.zoom
    val bounds = projection.queryVisibleBoundingBox()
    // Pure maths over the mmap'd index; runs in composition like the search path does.
    val pois = PoiIndex.inViewport(bounds.west, bounds.south, bounds.east, bounds.north)
        .filter { poi ->
            (filterTypes.isNullOrEmpty() || poi.type in filterTypes) &&
                zoom >= PoiCategories.minZoom(poi.type)
        }
    if (pois.isEmpty()) return
    Box(Modifier.fillMaxSize()) {
        for (poi in pois) {
            val offset = projection.screenLocationFromPosition(GeoPoint(poi.lon, poi.lat))
            Box(
                Modifier
                    .offset(offset.x - PIN_SIZE / 2, offset.y - PIN_SIZE / 2)
                    .size(PIN_SIZE)
                    .background(poiFill(poi.type), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    PoiCategories.glyph(poi.type),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Convert a tapped POI pin back into a selectable place. Its name comes from the feature
 * properties and its coordinate from the point geometry; we reuse
 * [SpecificFeature.GenericPlace] (name + position) so the existing
 * `SelectedFeatureViewModel.currentPoiInfo` flow fetches the Google enrichment and
 * `GooglePoiEnrichment` renders in the sheet — no new detail path needed.
 *
 * [osmPlace] folds in the sidecar's hours / phone / website / address, so the sheet has
 * something to show before (and without) any Google response. That is a mapped-file read,
 * which is why this suspends.
 */
suspend fun Feature1.toSelectedMaPoi(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val pos = (geometry as? Point)?.coordinates ?: return null
    val type = props.string("type")?.toIntOrNull()
    return osmPlace(name, Position(pos.longitude, pos.latitude), poiType = type)
}
