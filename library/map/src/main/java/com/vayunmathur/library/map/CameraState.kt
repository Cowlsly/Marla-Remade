package com.vayunmathur.library.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpRect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.log2

/**
 * North-up camera position. Bearing/tilt are intentionally unsupported (the
 * three migrated apps only ever used a north-up basemap), so the axis-aligned
 * overlays and image quad stay correct.
 */
data class CameraPosition(
    val target: GeoPoint = GeoPoint(0.0, 0.0),
    val zoom: Double = 0.0,
)

/**
 * Holds the live [CameraPosition] (as Compose state so gestures/animations
 * drive recomposition) and derives a [Projection] once the viewport is
 * measured. Mirrors maplibre-compose's `CameraState` surface used by the apps:
 * [position], [projection], [awaitProjection], [animateTo].
 */
class CameraState(initial: CameraPosition = CameraPosition()) {
    var position: CameraPosition by mutableStateOf(initial)

    /** Viewport size in logical (dp) units; null until first layout. */
    internal var viewportDp: Size? by mutableStateOf(null)

    /**
     * Task-17 pick provider: registered by the rendered surface (see
     * `VulkanMapSurface`), answering `(box, layerIds) -> placed labels`.
     * Null until a surface registers — a projection without a live renderer
     * answers [queryRenderedLabels][Projection.queryRenderedLabels] empty.
     */
    internal var labelQueryProvider: ((DpRect, Set<String>) -> List<PlacedLabel>)? by mutableStateOf(null)

    /**
     * Sets the measured viewport and enforces the minimum "fill" zoom so the map
     * always covers the viewport (no blank margins when zoomed all the way out),
     * matching maplibre's behavior.
     */
    internal fun setViewport(size: Size) {
        viewportDp = size
        val floor = fillZoom(size)
        if (position.zoom < floor) position = position.copy(zoom = floor)
    }

    /** Smallest zoom at which the world fills [vp]'s larger dimension. */
    private fun fillZoom(vp: Size): Double {
        val dim = maxOf(vp.width, vp.height).toDouble()
        if (dim <= 0.0) return 0.0
        return log2(dim / Mercator.TILE_SIZE).coerceAtLeast(0.0)
    }

    /** Current projection, or null before the viewport has been measured. */
    val projection: Projection?
        get() = viewportDp?.let { vp ->
            Projection(position.target, position.zoom, vp.width, vp.height, labelQueryProvider)
        }

    /** Suspends until the viewport is measured, then returns the projection. */
    suspend fun awaitProjection(): Projection =
        snapshotFlow { projection }.filterNotNull().first()

    /** Linearly interpolate center + zoom to [target] over [durationMs]. */
    suspend fun animateTo(target: CameraPosition, durationMs: Int = 500) {
        val start = position
        Animatable(0f).animateTo(1f, tween(durationMs)) {
            val t = value.toDouble()
            position = CameraPosition(
                target = GeoPoint(
                    lerp(start.target.longitude, target.target.longitude, t),
                    lerp(start.target.latitude, target.target.latitude, t),
                ),
                zoom = lerp(start.zoom, target.zoom, t),
            )
        }
    }

    /**
     * Animated zoom by [deltaZoom] levels, keeping the geographic point under
     * [anchorDp] fixed for the whole flight (double-tap to zoom).
     */
    internal suspend fun animateZoomBy(
        deltaZoom: Double,
        anchorDp: Offset,
        minZoom: Double,
        maxZoom: Double,
        durationMs: Int = 250,
    ) {
        val vp = viewportDp ?: return
        val start = position
        val target = clampZoom(start.zoom + deltaZoom, vp, minZoom, maxZoom)
        if (target == start.zoom) return
        Animatable(0f).animateTo(1f, tween(durationMs)) {
            position = anchoredZoom(start, lerp(start.zoom, target, value.toDouble()), anchorDp, vp)
        }
    }

    /**
     * Applies a "quick zoom" drag (double-tap, hold, then swipe). [dragDp] is
     * the vertical distance dragged since the gesture began — down is positive
     * and zooms in, up zooms out — and [from] is the camera as of that start,
     * so the zoom is absolute rather than accumulated frame by frame. The
     * geographic point under [anchorDp] stays fixed.
     */
    internal fun onQuickZoom(
        from: CameraPosition,
        anchorDp: Offset,
        dragDp: Float,
        minZoom: Double,
        maxZoom: Double,
    ) {
        val vp = viewportDp ?: return
        if (vp.height <= 0f) return
        val delta = dragDp / vp.height * QUICK_ZOOM_LEVELS_PER_VIEWPORT
        position = anchoredZoom(from, clampZoom(from.zoom + delta, vp, minZoom, maxZoom), anchorDp, vp)
    }

    /** Clamps [zoom] to [minZoom]..[maxZoom], never below the "fill" floor for [vp]. */
    private fun clampZoom(zoom: Double, vp: Size, minZoom: Double, maxZoom: Double): Double =
        zoom.coerceIn(maxOf(minZoom, fillZoom(vp)), maxZoom)

    /**
     * Applies a transform gesture, anchoring the geographic point under
     * [centroidDp] so pinch-zoom keeps that point fixed. Deltas are in logical
     * (dp) units.
     */
    internal fun onGesture(
        centroidDp: Offset,
        panDp: Offset,
        zoomChange: Float,
        minZoom: Double,
        maxZoom: Double,
        scrollEnabled: Boolean,
        zoomEnabled: Boolean,
    ) {
        val vp = viewportDp ?: return
        // Never zoom out past a full-screen world.
        val effectiveMinZoom = maxOf(minZoom, fillZoom(vp))
        var zoom = position.zoom

        // Pan: dragging content one way shifts the world the same way, so the
        // center moves opposite to the pan.
        val cWorld = Mercator.project(position.target.longitude, position.target.latitude, zoom)
        var cx = cWorld.x
        var cy = cWorld.y
        if (scrollEnabled) {
            cx -= panDp.x
            cy -= panDp.y
        }
        var center = Mercator.unproject(cx, cy, zoom)

        // Zoom about the centroid: keep the geo point under the fingers fixed.
        if (zoomEnabled && zoomChange != 1f && zoomChange > 0f) {
            val newZoom = (zoom + log2(zoomChange.toDouble())).coerceIn(effectiveMinZoom, maxZoom)
            if (newZoom != zoom) {
                val zoomed = anchoredZoom(CameraPosition(center, zoom), newZoom, centroidDp, vp)
                center = zoomed.target
                zoom = zoomed.zoom
            }
        }

        position = CameraPosition(center, zoom)
    }
}

/** Zoom levels covered by a quick-zoom drag across the full viewport height. */
private const val QUICK_ZOOM_LEVELS_PER_VIEWPORT = 4.0

/** [from] re-centered so the geo point under [anchorDp] is still there at [newZoom]. */
private fun anchoredZoom(
    from: CameraPosition,
    newZoom: Double,
    anchorDp: Offset,
    vp: Size,
): CameraPosition {
    val dx = anchorDp.x - vp.width / 2.0
    val dy = anchorDp.y - vp.height / 2.0
    val cw = Mercator.project(from.target.longitude, from.target.latitude, from.zoom)
    val geo = Mercator.unproject(cw.x + dx, cw.y + dy, from.zoom)
    val gw = Mercator.project(geo.longitude, geo.latitude, newZoom)
    return CameraPosition(Mercator.unproject(gw.x - dx, gw.y - dy, newZoom), newZoom)
}

private fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t

/** Remembers a [CameraState] seeded with [initial]. */
@Composable
fun rememberCameraState(initial: CameraPosition = CameraPosition()): CameraState =
    remember { CameraState(initial) }
