package com.vayunmathur.library.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

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
    /**
     * The feature's **own** archive kind (`cafe`, `hotel`, `station`, …), not its layer's.
     *
     * A symbol layer filters on several kinds — `poi-food` draws four — so before this was
     * read from the feature it reported the layer's first whitelist entry, and every food POI
     * came back as `restaurant`.
     */
    val kind: String,
    val position: GeoPoint,
    /**
     * The archive's stable id for the feature, or `0` when it has none.
     *
     * Only `places` and `poi` features carry one — they are the only pure points, and the
     * tiler merges everything else. The low two bits say which OSM id space it came from
     * (1 node, 2 way, 3 relation) because those three sequences overlap; the id itself is
     * the remaining bits.
     */
    val featureId: Long = 0L,
)

/**
 * Immutable snapshot of the camera (center + zoom + pitch) and viewport used to map
 * between geographic [GeoPoint]s and on-screen [DpOffset]s. Mirrors the subset
 * of maplibre-compose's projection API the apps call, so migration is an
 * import swap.
 *
 * The map center projects to the viewport center; offsets are density
 * independent (`Dp`) and measured from the viewport's top-left corner.
 *
 * At [pitchDeg] `== 0` this is the plain orthographic inverse it always was. When tilted it
 * mirrors the native perspective in `camera::Camera` exactly — [screenLocationFromPosition]
 * projects through the same divide, [positionFromScreenLocation] intersects the eye ray with the
 * ground plane — so Compose overlays land where the renderer drew the basemap under them. Bearing
 * is not carried here: the Compose path renders north-up, so a projection for it does too.
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
    private val pitchDeg: Double = 0.0,
    private val labelQuery: ((DpRect, Set<String>) -> List<PlacedLabel>)? = null,
    private val markerPick: ((Float, Float) -> Long)? = null,
) {
    private val halfW = widthDp / 2.0
    private val halfH = heightDp / 2.0
    // Perspective constants, mirroring `camera::Camera::perspective` so this agrees with what the
    // renderer drew. `d` (camera-to-centre distance) cancels at pitch 0, so it only sets the
    // foreshortening strength; only used on the tilted branches below.
    private val d = 1.5 * heightDp
    private val fx = d / halfW
    private val fy = d / halfH

    /** Screen location (from the viewport top-left) of a geographic [position]. */
    fun screenLocationFromPosition(position: GeoPoint): DpOffset {
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val p = Mercator.project(position.longitude, position.latitude, zoom)
        val sx = p.x - c.x
        val sy = p.y - c.y
        if (pitchDeg == 0.0) {
            return DpOffset((sx + halfW).toFloat().dp, (sy + halfH).toFloat().dp)
        }
        val pitch = Math.toRadians(pitchDeg)
        val psin = sin(pitch)
        val pcos = cos(pitch)
        val w = d - psin * sy
        // Behind the eye — only past the horizon, which the pitch cap keeps off-screen. Push it
        // far off rather than dividing by a non-positive w.
        if (w <= 0.0) return DpOffset((-1e5f).dp, (-1e5f).dp)
        val screenX = halfW + (fx * sx / w) * halfW
        val screenY = halfH + (fy * pcos * sy / w) * halfH
        return DpOffset(screenX.toFloat().dp, screenY.toFloat().dp)
    }

    /**
     * Geographic position under a screen [offset] (from the viewport top-left).
     *
     * The tilted branch intersects the eye ray with the flat ground **plane**, ignoring terrain
     * elevation: over relief this leaves a bounded vertical error, which is fine for gestures
     * (pan/anchor follow the finger approximately). Exact ground/feature hits under tilt go through
     * the GPU instead — label picks via `pickLabels` and precise hits via `pickAt`'s id/depth
     * readback — both of which are terrain-correct because they read what was actually drawn.
     */
    fun positionFromScreenLocation(offset: DpOffset): GeoPoint {
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val sx: Double
        val sy: Double
        if (pitchDeg == 0.0) {
            sx = offset.x.value - halfW
            sy = offset.y.value - halfH
        } else {
            val pitch = Math.toRadians(pitchDeg)
            val psin = sin(pitch)
            val pcos = cos(pitch)
            val ndcX = (offset.x.value - halfW) / halfW
            val ndcY = (offset.y.value - halfH) / halfH
            // Invert clip.y = fy*cos*sy / (d - sin*sy) for sy, then clip.x for sx. The denominator
            // only vanishes at/above the horizon, which the pitch cap keeps off-screen; clamp to a
            // tiny positive so a query exactly on it maps far away rather than to a NaN.
            val denom = (fy * pcos + ndcY * psin).coerceAtLeast(1e-6)
            sy = ndcY * d / denom
            val w = d - psin * sy
            sx = ndcX * w / fx
        }
        return Mercator.unproject(c.x + sx, c.y + sy, zoom)
    }

    /** The lon/lat bounds of the currently visible viewport. */
    fun queryVisibleBoundingBox(): GeoBounds {
        if (pitchDeg == 0.0) {
            val topLeft = positionFromScreenLocation(DpOffset(0.dp, 0.dp))
            val bottomRight = positionFromScreenLocation(DpOffset(widthDp.dp, heightDp.dp))
            return GeoBounds(
                west = topLeft.longitude,
                south = bottomRight.latitude,
                east = bottomRight.longitude,
                north = topLeft.latitude,
            )
        }
        // Tilted: the visible ground is a trapezoid, so take the AABB of all four screen corners'
        // ground points. The top edge recedes toward the horizon, so this box is larger than the
        // untilted one — correctly, since that ground really is on screen.
        val corners = listOf(
            positionFromScreenLocation(DpOffset(0.dp, 0.dp)),
            positionFromScreenLocation(DpOffset(widthDp.dp, 0.dp)),
            positionFromScreenLocation(DpOffset(0.dp, heightDp.dp)),
            positionFromScreenLocation(DpOffset(widthDp.dp, heightDp.dp)),
        )
        return GeoBounds(
            west = corners.minOf { it.longitude },
            south = corners.minOf { it.latitude },
            east = corners.maxOf { it.longitude },
            north = corners.maxOf { it.latitude },
        )
    }

    /**
     * Tile-baked placed labels (task 17) whose screen boxes intersect [box],
     * restricted to [layerIds, in placement order (topmost first).
     *
     * The [queryRenderedFeatures]-equivalent the maps `FeatureSource` adapter
     * needs: `source = { box, layerIds -> projection.queryRenderedLabels(box,
     * layerIds).map { it.toFeature1() } }`. Registered by `VulkanMapSurface`, so this
     * is empty only when nothing placed is under the query box.
     */
    fun queryRenderedLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> =
        labelQuery?.invoke(box, layerIds) ?: emptyList()

    /**
     * The [MapMarker.id] of the renderer-drawn pin under a tap at ([xDp], [yDp]) (Dp from the
     * viewport top-left), or `0` when the tap hit no pin.
     *
     * The marker counterpart of [queryRenderedLabels]: it reads the renderer's GPU id buffer, so a
     * pin stays tappable under tilt — where its screen box is no longer a plain projection of its
     * lon/lat — and never trails the basemap on a pan. Registered by `VulkanMapSurface`, so this is
     * `0` only when nothing is under the finger or no rendered surface is live.
     */
    fun pickMarker(xDp: Float, yDp: Float): Long = markerPick?.invoke(xDp, yDp) ?: 0L
}
