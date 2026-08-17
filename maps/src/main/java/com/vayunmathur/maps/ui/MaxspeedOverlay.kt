package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.data.parseMaxspeed
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraProjection
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/**
 * Posted-speed-limit data source (Decision D4), ported from Vela's maxspeed
 * PMTiles overlay (`VelaMapView.kt:967-1003` + `OsmMaxspeed.kt`).
 *
 * A vector overlay whose features carry the OSM `maxspeed` tag is mounted as an
 * invisible probe layer; the driving UI queries it with `queryRenderedFeatures`
 * under the puck (see [queryPostedLimit]) and shows the parsed value on the
 * speed widget.
 *
 * The maxspeed data is baked into the v5 PMTiles (P13), so the probe reads the
 * same file as the basemap — [MaxspeedSource.PMTILES_URL] delegates to the
 * single [com.vayunmathur.maps.util.MapTileCache.BASEMAP_PMTILES_URL] source of
 * truth (source-layer [SOURCE_LAYER], feature property [PROP]). Switching to the
 * global v5 file is therefore a one-line change in [MapTileCache].
 */
object MaxspeedSource {
    /** Baked into the v5 basemap PMTiles — read from the shared base URL. */
    val PMTILES_URL: String get() = com.vayunmathur.maps.util.MapTileCache.BASEMAP_PMTILES_URL
    const val LAYER_ID: String = "maxspeed-probe"
    const val SOURCE_LAYER: String = "maxspeed"
    const val PROP: String = "maxspeed"

    val enabled: Boolean get() = PMTILES_URL.isNotBlank()
}

/**
 * Mount the maxspeed probe overlay. No-op (renders nothing) while the tileset
 * is unhosted, so it is always safe to include in the layer tree.
 */
@Composable
@MaplibreComposable
fun MaxspeedLayer() {
    if (!MaxspeedSource.enabled) return
    val source = rememberVectorSource(MaxspeedSource.PMTILES_URL)
    // Invisible, but rendered so queryRenderedFeatures can hit it under the puck.
    LineLayer(
        MaxspeedSource.LAYER_ID,
        source,
        sourceLayer = MaxspeedSource.SOURCE_LAYER,
        opacity = const(0f),
        width = const(2.dp),
    )
}

/**
 * Query the posted speed limit at [puck] (the snapped nav position) by
 * projecting it to screen and hit-testing the maxspeed probe layer. Returns
 * `null` when the overlay is unhosted, off-network, or the road has no
 * concrete `maxspeed` tag.
 */
fun queryPostedLimit(projection: CameraProjection, puck: Position): PostedLimit? {
    if (!MaxspeedSource.enabled) return null
    val offset = projection.screenLocationFromPosition(puck)
    val features = projection.queryRenderedFeatures(offset, setOf(MaxspeedSource.LAYER_ID))
    val raw = features.firstNotNullOfOrNull { f ->
        (f.properties?.get(MaxspeedSource.PROP) as? JsonPrimitive)?.content
    }
    return parseMaxspeed(raw)
}
