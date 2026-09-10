package com.vayunmathur.maps.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconLaneLeftRight
import com.vayunmathur.library.ui.IconLaneThroughLeft
import com.vayunmathur.library.ui.IconLaneThroughLeftRight
import com.vayunmathur.library.ui.IconLaneThroughRight
import com.vayunmathur.library.ui.IconLaneThroughSlightLeft
import com.vayunmathur.library.ui.IconLaneThroughSlightRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.maps.ui.iconContent
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.RouteService.API.Maneuver

/**
 * Lane-guidance strip (Vela's `LaneDiagram`). Renders one cell per available
 * turn lane at the upcoming junction, derived by the Rust router (P5a). Lanes
 * that lead onto the taken route are highlighted; the rest are dimmed.
 *
 * A lane permitting several movements gets a single combined glyph — one shaft
 * with a head per direction, the way road signage draws it.
 *
 * Renders nothing when there is no lane data (single continuation, or a router
 * that couldn't resolve the junction).
 */
@Composable
fun LaneGuidance(
    lanes: List<RouteService.API.Lane>,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.35f),
) {
    if (lanes.isEmpty()) return
    // One literal drives both the cell and the glyph inside it, so they cannot
    // drift apart.
    val cell = 26.dp
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.25f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            lanes.forEach { lane ->
                val tint = if (lane.active) activeColor else inactiveColor
                Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                    val glyph = combinedLaneIcon(lane.directions)
                        ?: lane.directions.dominant()?.iconContent()
                    glyph?.invoke(Modifier.size(cell), tint)
                }
            }
        }
    }
}

/**
 * The single combined glyph for a lane permitting several movements, or null
 * when there is no combined glyph for this set and the caller should fall back
 * to one arrow. Covers the combinations that actually occur in OSM
 * `turn:lanes`.
 */
private fun combinedLaneIcon(
    directions: List<Maneuver>,
): (@Composable (Modifier, Color) -> Unit)? {
    val set = directions.toSet()
    if (set.size < 2) return null
    val through = Maneuver.STRAIGHT in set
    val turns = set - Maneuver.STRAIGHT
    return when {
        through && turns == setOf(Maneuver.TURN_LEFT) ->
            { m, t -> IconLaneThroughLeft(m, t) }
        through && turns == setOf(Maneuver.TURN_RIGHT) ->
            { m, t -> IconLaneThroughRight(m, t) }
        through && turns == setOf(Maneuver.TURN_SLIGHT_LEFT) ->
            { m, t -> IconLaneThroughSlightLeft(m, t) }
        through && turns == setOf(Maneuver.TURN_SLIGHT_RIGHT) ->
            { m, t -> IconLaneThroughSlightRight(m, t) }
        through && turns == setOf(Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT) ->
            { m, t -> IconLaneThroughLeftRight(m, t) }
        !through && turns == setOf(Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT) ->
            { m, t -> IconLaneLeftRight(m, t) }
        else -> null
    }
}

/**
 * The one movement to draw for a lane with no combined glyph. Going straight on
 * dominates when it is offered; otherwise take the leftmost turn, which is the
 * order the router packs them in.
 */
private fun List<Maneuver>.dominant(): Maneuver? =
    firstOrNull { it == Maneuver.STRAIGHT } ?: firstOrNull()
