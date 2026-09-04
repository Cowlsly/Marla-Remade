package com.vayunmathur.maps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.ui.theme.toStyleHex
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.toGeoPoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

/**
 * The route polyline, drawn as plain Compose over VectorMap.
 *
 * Was a GeoJSON source + `LineLayer` (data-driven `route-color`); the renderer has no
 * vector-layer API, so each per-step segment is projected through library:map's Projection
 * and drawn on a Canvas with the same 8 dp round-cap stroke. The segment-colouring logic
 * (static mode colours + the travelled-gray split at the snapped point) is unchanged —
 * only the last mile (GeoJSON → layer) became Canvas.
 */
@Composable
fun RouteLayer(
    route: RouteService.Route,
    navProgress: NavigationProgress?,
    tokens: MapTokens,
    cameraState: CameraState,
) {
    val density = LocalDensity.current
    val segments = remember(
        route, tokens,
        navProgress?.segmentIndex,
        navProgress?.distanceAlongRoute?.let { (it / 5.0).toInt() },
    ) {
        buildRouteFeatures(route, navProgress, tokens)
    }
    val projection = cameraState.projection ?: return
    Canvas(Modifier.fillMaxSize()) {
        val widthPx = with(density) { 8.dp.toPx() }
        for (feature in segments) {
            val line = feature.geometry as? LineString ?: continue
            if (line.coordinates.size < 2) continue
            val colorHex = (feature.properties?.get("route-color") as? JsonPrimitive)?.content
                ?: continue
            val path = Path()
            line.coordinates.forEachIndexed { i, pos ->
                val o = projection.screenLocationFromPosition(pos.toGeoPoint())
                val px = with(density) { Offset(o.x.toPx(), o.y.toPx()) }
                if (i == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
            }
            drawPath(
                path,
                Color(runCatching { android.graphics.Color.parseColor(colorHex) }.getOrDefault(0xFF1710F1.toInt())),
                style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Compute the per-step `route-color` for the static (non-navigating) case.
 * Driving uses traffic-aware red/amber/green, transit uses the line's own colour,
 * walk/bike fall through to a single blue.
 */
private fun staticColorFor(step: RouteService.Step, tokens: MapTokens): String {
    return when (step.travelMode) {
        RouteService.TravelMode.DRIVE -> when {
            step.speedRatio < 0.5 -> tokens.traffic.jam.toStyleHex()
            step.speedRatio < 0.9 -> tokens.traffic.slow.toStyleHex()
            else -> tokens.traffic.free.toStyleHex()
        }
        // The colour the pack (or MOTIS) reported for this route, which is what
        // the step-list badge already shows. This used to re-derive the colour
        // through GTFSProvider instead, and that only knows the one feed bundled
        // in the APK — so every downloaded pack fell through to the fallback and
        // the whole map drew red while the badges were correct.
        RouteService.TravelMode.TRANSIT ->
            step.transitDetails?.transitLine?.color?.ifBlank { null }
                ?: tokens.routeTransitFallback.toStyleHex()
        else -> tokens.routeInert.toStyleHex()
    }
}

/**
 * Build the coloured segment list for the route polyline.
 *
 * When [navProgress] is null this returns one feature per [Step] with the
 * mode-aware static color.
 *
 * When [navProgress] is non-null the polyline is split at the snapped point
 * so that:
 *  - steps strictly before the current step get the traveled-gray color
 *  - the current step is split: portion behind the snap → gray; portion
 *    ahead → original mode color
 *  - steps strictly after keep their original color
 *
 * Splitting at the segment level requires matching the snapped segment
 * index (which is into the FULL `route.polyline`) to the corresponding
 * vertex inside the current step's local polyline. The math here is the
 * mirror of [com.vayunmathur.maps.util.PolylineIndex]'s `stepRanges`
 * construction (cursor walk; steps share endpoints).
 */
private fun buildRouteFeatures(
    route: RouteService.Route,
    navProgress: NavigationProgress?,
    tokens: MapTokens,
): List<Feature1> {
    val traveledGray = tokens.traffic.traveled.toStyleHex()
    if (navProgress == null) {
        return route.step.filter { it.polyline.size >= 2 }.map { step ->
            Feature1(
                LineString(step.polyline),
                JsonObject(mapOf("route-color" to JsonPrimitive(staticColorFor(step, tokens))))
            )
        }
    }

    val currentStepIdx = navProgress.currentStepIndex
    val snappedSegIdx = navProgress.segmentIndex // index into route.polyline
    val snappedPos = navProgress.snappedPosition

    val out = mutableListOf<Feature1>()
    // Walk the full polyline alongside the steps the same way PolylineIndex
    // builds stepRanges, so we know the vertex range for each step.
    var cursor = 0
    for ((stepIdx, step) in route.step.withIndex()) {
        val stepLen = step.polyline.size
        if (stepLen < 2) {
            // Nothing to render for a degenerate step. Cursor stays where it
            // was (mirrors PolylineIndex's `ranges.add(cursor..cursor)` /
            // skipping the cursor advance).
            continue
        }
        val first = cursor
        val last = (first + stepLen - 1).coerceAtMost(route.polyline.size - 1)
        val color = staticColorFor(step, tokens)

        when {
            stepIdx < currentStepIdx -> {
                // Entirely behind: gray.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(traveledGray)))
                )
            }
            stepIdx > currentStepIdx -> {
                // Entirely ahead: original color.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                )
            }
            snappedSegIdx < first -> {
                // Snap fell on an earlier step than our step-index math
                // attributed to this step (e.g. brief off-by-one near a
                // boundary, or a glitchy GPS fix). Treat the whole step as
                // ahead rather than fabricating a gray spur from a snapped
                // position that isn't on this step's segments.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                )
            }
            snappedSegIdx > last -> {
                // Snap fell on a later step. Treat the whole step as behind.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(traveledGray)))
                )
            }
            else -> {
                // The active step: split at the snap point. snappedSegIdx is
                // guaranteed in [first, last] by the two guards above.
                val localSnapVertex = snappedSegIdx - first
                // Behind portion: vertices 0..localSnapVertex, with the
                // snapped position appended so the gray ends exactly under
                // the user.
                val behindVertices = step.polyline.subList(0, localSnapVertex + 1).toMutableList()
                behindVertices.add(snappedPos)
                if (behindVertices.size >= 2) {
                    out += Feature1(
                        LineString(behindVertices),
                        JsonObject(mapOf("route-color" to JsonPrimitive(traveledGray)))
                    )
                }
                // Ahead portion: snapped position, then remaining vertices.
                val aheadVertices = mutableListOf(snappedPos)
                aheadVertices.addAll(step.polyline.subList(localSnapVertex + 1, stepLen))
                if (aheadVertices.size >= 2) {
                    out += Feature1(
                        LineString(aheadVertices),
                        JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                    )
                }
            }
        }
        cursor = last
    }
    return out
}
