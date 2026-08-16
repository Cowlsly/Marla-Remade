package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Web-Mercator scale bar bound to the map camera. Given the current [zoom] and
 * [latitude] (Mercator ground resolution depends on both), it picks the largest
 * "nice" round distance (1 / 2 / 5 × 10ⁿ) that fits within a fixed on-screen
 * width and draws a labelled bar for it.
 *
 * Port of Vela's `ScaleBar`/`ScaleBarReader` — kept declarative so it just reads
 * `camera.position.zoom` / `camera.position.target.latitude` from the caller.
 */
@Composable
fun MapScaleBar(zoom: Double, latitude: Double, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxBarPx = with(density) { 96.dp.toPx() }

    // Ground resolution (meters per screen pixel) at this latitude and zoom.
    val metersPerPixel = 156543.03392 * cos(latitude * PI / 180.0) / 2.0.pow(zoom)
    if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return

    val niceMeters = niceRoundDistance(metersPerPixel * maxBarPx)
    val barWidth = with(density) { (niceMeters / metersPerPixel).toFloat().toDp() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = scaleLabel(niceMeters),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(barWidth)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

/** Largest value of the form 1/2/5 × 10ⁿ that is ≤ [maxMeters]. */
private fun niceRoundDistance(maxMeters: Double): Double {
    if (maxMeters <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(maxMeters)))
    var best = magnitude
    for (factor in listOf(1.0, 2.0, 5.0, 10.0)) {
        val candidate = factor * magnitude
        if (candidate <= maxMeters) best = candidate
    }
    return best
}

/** Nice distances are always whole, so integer m / km rendering is exact. */
private fun scaleLabel(meters: Double): String =
    if (meters >= 1000.0) "${(meters / 1000.0).roundToInt()} km" else "${meters.roundToInt()} m"
