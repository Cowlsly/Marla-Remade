package com.vayunmathur.maps.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.ui.iconContent
import com.vayunmathur.maps.util.RouteService

/**
 * Lane-guidance strip (Vela's `LaneDiagram`). Renders one arrow per available
 * turn lane at the upcoming junction, derived by the Rust router (P5a). Lanes
 * that lead onto the taken route are highlighted; the rest are dimmed.
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
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            lanes.forEach { lane ->
                val dir = lane.directions.firstOrNull()
                    ?: RouteService.API.Maneuver.STRAIGHT
                val tint = if (lane.active) activeColor else inactiveColor
                val icon = dir.iconContent()
                if (icon != null) {
                    icon(Modifier.size(26.dp), tint)
                } else {
                    // Fallback glyph for a lane with no drawable maneuver icon.
                    Box(Modifier.size(26.dp)) {}
                }
            }
        }
    }
}
