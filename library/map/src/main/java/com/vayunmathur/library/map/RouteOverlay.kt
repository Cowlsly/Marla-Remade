package com.vayunmathur.library.map

import androidx.compose.ui.graphics.Color

/**
 * A navigation route drawn inside the renderer's frame, so it pans in lock-step with the
 * basemap the way the puck, region mask and traffic already do.
 *
 * A route is a list of [segments], each its own polyline and fill [colour][RouteSegment.color]
 * — a driving route split into traffic bands, a transit route into per-line colours, an
 * active navigation leg into a travelled grey behind the puck and the mode colour ahead. The
 * renderer strokes them into one mesh, draws a single continuous casing over the whole thing,
 * then paints each segment's fill over it. [style] carries what the segments share: the line
 * width and the casing.
 *
 * A plain single-colour route is just a one-segment list:
 * `RouteOverlay(listOf(RouteSegment(points, Color(0xFF1A73E8))))`. `null` (or an all-empty
 * list) draws nothing, which is how a route is cleared. Pushed into the renderer through
 * [VectorMap]'s `route` parameter, out of band from the frame loop.
 */
data class RouteOverlay(
    val segments: List<RouteSegment>,
    val style: RouteStyle = RouteStyle(),
) {
    /** True when there is nothing to draw, which the surface treats the same as `null`. */
    fun isEmpty(): Boolean = segments.all { it.points.size < 2 }
}

/**
 * One coloured run of a [RouteOverlay]: a polyline and the colour its fill is painted.
 *
 * [points] are lon-first ([GeoPoint]), in order. Each run carries its whole polyline,
 * including the point it shares with its neighbours, so the strokes meet end to end under the
 * shared casing. A run of fewer than two distinct points draws nothing.
 */
data class RouteSegment(
    val points: List<GeoPoint>,
    val color: Color,
)
