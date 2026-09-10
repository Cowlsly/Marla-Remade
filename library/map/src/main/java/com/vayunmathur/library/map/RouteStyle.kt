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
 * So it is not picked from it. `8.dp` is the phone's route width — set in
 * `maps/src/main/java/com/vayunmathur/maps/ui/map/RouteOverlayBuilder.kt`, the only
 * density-correct route width in this app, chosen by someone looking at the result. The
 * casing keeps the reference's proportion, which had the casing half again as wide as the
 * line.
 *
 * A head unit is read at about 70 cm against a phone's 30 cm, so there is a real argument
 * for going wider than the phone here. Nobody has seen this on a head unit yet, so that
 * argument has not been acted on: this is the grounded number, and widening it is a
 * deliberate change someone should make while looking at one.
 *
 * # Colour is per-segment
 *
 * The fill colour is not here: it rides on each [RouteSegment], because the whole point of
 * the route overlay is to colour each run differently — traffic bands, transit brand colours,
 * a travelled grey behind the puck during navigation. What stays shared, and lives here, is
 * the geometry of the stroke: the line width and the casing that outlines the lot. The car
 * (Android Auto) passes a single segment and one colour through the same path.
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
    /** The casing's colour. Unused when [casingWidth] is zero. */
    val casingColor: Color = Color.White,
)
