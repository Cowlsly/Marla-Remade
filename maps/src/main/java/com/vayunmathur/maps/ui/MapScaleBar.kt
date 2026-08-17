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
import com.vayunmathur.maps.util.isImperialUnits
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

    val imperial = isImperialUnits()
    val (barMeters, label) = scaleBar(metersPerPixel * maxBarPx, imperial)
    val barWidth = with(density) { (barMeters / metersPerPixel).toFloat().toDp() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = label,
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

/**
 * Pick a "nice" round scale-bar distance that fits within [maxMeters] and label
 * it in the regional unit ([imperial] = ft/mi, else m/km). Returns the distance
 * in METERS (for bar width) paired with the display label.
 */
private fun scaleBar(maxMeters: Double, imperial: Boolean): Pair<Double, String> {
    if (imperial) {
        val maxMiles = maxMeters / 1609.34
        return if (maxMiles < 1.0) {
            val ft = niceRoundDistance(maxMeters * 3.28084)
            (ft / 3.28084) to "${ft.roundToInt()} ft"
        } else {
            val mi = niceRoundDistance(maxMiles)
            (mi * 1609.34) to "${mi.roundToInt()} mi"
        }
    }
    val meters = niceRoundDistance(maxMeters)
    return meters to if (meters >= 1000.0) {
        "${(meters / 1000.0).roundToInt()} km"
    } else {
        "${meters.roundToInt()} m"
    }
}
