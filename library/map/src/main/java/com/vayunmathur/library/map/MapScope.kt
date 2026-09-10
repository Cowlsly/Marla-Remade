package com.vayunmathur.library.map

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/** Which part of a marker sits on its geographic position. */
enum class MarkerAnchor {
    /** The marker's centre. What a circular pin or a cluster chip wants. */
    Center,

    /** The middle of the marker's bottom edge. What a teardrop pin wants. */
    BottomCenter,
}

/**
 * Receiver for [VectorMap]'s `content`, giving Compose overlays a way to position themselves
 * geographically instead of each host re-deriving screen coordinates.
 *
 * This is the Compose-overlay path: content placed here is a second, unsynchronised pass that
 * lags the basemap by a frame while panning. Renderer-drawn pins that must stay glued under
 * pan/tilt go through [VectorMap]'s `markers` (the [MapMarker] data class) instead; this scope
 * is for arbitrary Compose content (avatars, geofence canvases, clusters) that a sprite atlas
 * cannot express.
 */
@Stable
@LayoutScopeMarker
class MapScope internal constructor(private val cameraState: CameraState) {

    /**
     * The live projection, or null before the viewport is measured.
     *
     * Exposed because `findfamily`'s geofence circles are drawn in a `Canvas` and genuinely
     * need to project arbitrary points, not just anchor a composable. Read it *inside* the
     * draw lambda, not in composition, for the same reason [MapMarker] reads it during
     * placement. Nullable on purpose: it is null in the frames before the viewport is measured.
     */
    val projection: Projection?
        get() = cameraState.projection

    /**
     * Places [content] at [position] on the map.
     *
     * The projection is read during *placement* (inside the `layout {}` lambda), never in
     * composition or measurement: that makes the camera a placement dependency, so a pan
     * re-runs placement alone and skips recomposition and measurement. Hoisting the projection
     * read into composition reintroduces a per-frame recomposition of every marker; the symptom
     * is a pan frame-rate regression, not a failure, so nothing catches it in a test.
     */
    @Composable
    fun MapMarker(
        position: GeoPoint,
        modifier: Modifier = Modifier,
        anchor: MarkerAnchor = MarkerAnchor.Center,
        content: @Composable () -> Unit,
    ) {
        Layout(content = content, modifier = modifier) { measurables, constraints ->
            // Loosened: a marker is sized by its content, not stretched to the map.
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(childConstraints) }
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val height = placeables.maxOfOrNull { it.height } ?: 0

            // MeasureScope is a Density; PlacementScope is not, so capture it here.
            val density: Density = this
            // The map fills its parent, so the incoming maxima are the viewport in px and this
            // node sits at the parent's top-left — which makes the projected offset directly
            // usable as a placement offset.
            val viewportWidth = constraints.maxWidth
            val viewportHeight = constraints.maxHeight

            layout(width, height) {
                val projected = projection?.screenLocationFromPosition(position) ?: return@layout
                val originX = with(density) { projected.x.toPx() }.roundToInt()
                val originY = with(density) { projected.y.toPx() }.roundToInt()
                val x = originX - width / 2
                val y = when (anchor) {
                    MarkerAnchor.Center -> originY - height / 2
                    MarkerAnchor.BottomCenter -> originY - height
                }
                val offscreen = x + width < 0 || y + height < 0 ||
                    x > viewportWidth || y > viewportHeight
                if (offscreen) return@layout
                placeables.forEach { it.place(x, y) }
            }
        }
    }
}
