package com.vayunmathur.library.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp

/**
 * One tile-baked placed label hit by [Projection.queryRenderedLabels].
 *
 * The maps-side adapter maps [layerId] (our flat ids) to its own layer-id
 * sets and builds its `Feature1` (`Feature<Geometry.Point, JsonObject>`,
 * properties `{kind, name, name:en}`) for `SpecificFeature.parse`.
 */
data class PlacedLabel(
    val layerId: String,
    val name: String,
    val kind: String,
    val position: GeoPoint,
)

/**
 * Immutable snapshot of the camera (center + zoom) and viewport used to map
 * between geographic [GeoPoint]s and on-screen [DpOffset]s. Mirrors the subset
 * of maplibre-compose's projection API the apps call, so migration is an
 * import swap.
 *
 * The map center projects to the viewport center; offsets are density
 * independent (`Dp`) and measured from the viewport's top-left corner.
 *
 * [labelQuery] answers [queryRenderedLabels]: null until a rendered surface
 * registers one (see `VulkanMapSurface`), so a projection without a live
 * renderer answers empty rather than crashing.
 */
class Projection internal constructor(
    private val center: GeoPoint,
    private val zoom: Double,
    private val widthDp: Float,
    private val heightDp: Float,
    private val labelQuery: ((DpRect, Set<String>) -> List<PlacedLabel>)? = null,
) {
    /** Screen location (from the viewport top-left) of a geographic [position]. */
    fun screenLocationFromPosition(position: GeoPoint): DpOffset {
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val p = Mercator.project(position.longitude, position.latitude, zoom)
        val x = (p.x - c.x) + widthDp / 2.0
        val y = (p.y - c.y) + heightDp / 2.0
        return DpOffset(x.toFloat().dp, y.toFloat().dp)
    }

    /** Geographic position under a screen [offset] (from the viewport top-left). */
    fun positionFromScreenLocation(offset: DpOffset): GeoPoint {
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val x = c.x + (offset.x.value - widthDp / 2.0)
        val y = c.y + (offset.y.value - heightDp / 2.0)
        return Mercator.unproject(x, y, zoom)
    }

    /** The lon/lat bounds of the currently visible viewport. */
    fun queryVisibleBoundingBox(): GeoBounds {
        val topLeft = positionFromScreenLocation(DpOffset(0.dp, 0.dp))
        val bottomRight = positionFromScreenLocation(DpOffset(widthDp.dp, heightDp.dp))
        return GeoBounds(
            west = topLeft.longitude,
            south = bottomRight.latitude,
            east = bottomRight.longitude,
            north = topLeft.latitude,
        )
    }

    /**
     * Tile-baked placed labels (task 17) whose screen boxes intersect [box],
     * restricted to [layerIds, in placement order (topmost first).
     *
     * The [queryRenderedFeatures]-equivalent the maps `FeatureSource` adapter
     * needs: `source = { box, layerIds -> projection.queryRenderedLabels(box,
     * layerIds).map { it.toFeature1() } }`. Empty until a rendered surface
     * registers its native pick (and when nothing placed hits).
     */
    fun queryRenderedLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> =
        labelQuery?.invoke(box, layerIds) ?: emptyList()
}
