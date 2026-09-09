package com.vayunmathur.library.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How [SurfaceMapRenderer.setRoute] paints the navigation route line.
 *
 * The defaults are the colours the car renderer this replaces used — a white casing under
 * a `#1A73E8` fill — at the one route width in this app that was authored against a real
 * rendering. So a host that just wants "the route back" can pass nothing.
 *
 * # Where the width comes from, since the old one cannot be converted
 *
 * The old renderer set `Paint.strokeWidth` to `12f` and `18f` on a `Canvas` over the
 * snapshot `Bitmap`. `Paint` has no notion of Dp and nothing on that path divided by
 * density, so those were **physical pixels** — a route that was a fixed 12 px wide on
 * every head unit. The snapshotter itself *was* given the density (`withPixelRatio`), so
 * the basemap scaled with DPI while the route did not: the line got visually thinner
 * relative to the map it was drawn on as head-unit DPI rose. That is a bug, not a design.
 *
 * Which means the old number cannot be carried across: `12f` equals `12.dp` at density
 * 1.0, `6.dp` at density 2.0, and nobody knows which unit it was tuned on. Any Dp value
 * picked from it silently chooses one density to be correct at.
 *
 * So it is not picked from it. `8.dp` is `maps/src/main/java/com/vayunmathur/maps/ui/
 * RouteLayer.kt:52`, the phone's route width — the only density-correct route width in
 * this app, chosen by someone looking at the result. The casing keeps the reference's
 * proportion, which had the casing half again as wide as the line.
 *
 * A head unit is read at about 70 cm against a phone's 30 cm, so there is a real argument
 * for going wider than the phone here. Nobody has seen this on a head unit yet, so that
 * argument has not been acted on: this is the grounded number, and widening it is a
 * deliberate change someone should make while looking at one.
 *
 * # One colour, deliberately
 *
 * The phone's route is coloured per navigation step — traffic bands, transit brand
 * colours, a travelled grey behind the puck — but it is drawn in Compose over `VectorMap`
 * and is not migrating to the renderer. Nothing that consumes this API needs per-segment
 * colour, so it does not have one; if `RouteLayer` is ever migrated, that is when the
 * shape should be revisited against a real caller.
 *
 * # Why there is a casing at all
 *
 * A route crosses roads drawn in its own colour family and runs over a busy basemap; the
 * outline is what keeps it separated from them at a glance, which matters more in a car
 * than on a phone. It costs one extra draw and no extra geometry — the renderer draws the
 * same mesh twice, wider underneath, because line widths live in a push constant rather
 * than in the vertices. Set [casingWidth] to `0.dp` to switch it off.
 */
data class RouteStyle(
    /** The route line's own width. */
    val width: Dp = 8.dp,
    /** How far the casing stands out past the route line **on each side**. */
    val casingWidth: Dp = 2.dp,
    /** The route line's colour. */
    val color: Color = Color(0xFF1A73E8),
    /** The casing's colour. Unused when [casingWidth] is zero. */
    val casingColor: Color = Color.White,
)
