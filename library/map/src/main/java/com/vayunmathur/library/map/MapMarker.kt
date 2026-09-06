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
 * Receiver for [VectorMap]'s `content`, giving overlays a way to position themselves
 * geographically instead of each host re-deriving screen coordinates.
 *
 * Four apps had independently reimplemented "put this Compose thing at this lat/lon", three
 * of them with the same `offset(x - size/2, y - size/2)` arithmetic and one with a literal
 * `DpOffset(35.dp, 35.dp)` for a 70 dp avatar. [MapMarker] is that, once.
 */
@Stable
@LayoutScopeMarker
class MapScope internal constructor(private val cameraState: CameraState) {

    /**
     * The live projection, or null before the viewport is measured.
     *
     * Exposed because `findfamily`'s geofence circles are drawn in a `Canvas` and genuinely
     * need to project arbitrary points, not just anchor a composable. One caller does not
     * justify inventing a geo-aware draw scope, so it gets the projection directly — but
     * read it *inside* the draw lambda, not in composition, for the same reason [MapMarker]
     * reads it during placement.
     *
     * Nullable on purpose. It was previously common for hosts to write `projection!!`, which
     * is a live crash in the frames before the viewport is measured.
     */
    val projection: Projection?
        get() = cameraState.projection

    /**
     * Places [content] at [position] on the map.
     *
     * ## The projection is read during *placement*
     *
     * This is the one non-obvious correctness requirement here, and nothing enforces it, so:
     * `screenLocationFromPosition` is called inside the `layout {}` placement lambda. Never
     * hoist it into composition or into the measure block.
     *
     * Reading it in composition would make the camera a composition dependency, so every
     * marker's composition scope would invalidate on every frame of a pan — with a dense
     * cluster set that is strictly worse than the hand-rolled per-frame re-projection this
     * replaces. Reading it during placement makes the camera a *placement* dependency, so
     * Compose re-runs placement alone and skips both recomposition and measurement. It is
     * the same distinction as `Modifier.offset(x, y)` versus `Modifier.offset { }`.
     *
     * The symptom of getting this wrong is a frame-rate regression while panning, not a
     * failure, so it will not show up in a test.
     *
     * Measurement always happens; only placement is skipped when there is no projection yet
     * or the marker is wholly outside the viewport. Skipping measurement instead would make
     * the marker's size a function of the camera, which would reintroduce the per-frame
     * measure pass being avoided.
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
            // The map fills its parent, so the incoming maxima are the viewport in px and
            // this node sits at the parent's top-left — which makes the projected offset
            // directly usable as a placement offset.
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
