package com.vayunmathur.library.ui

import android.hardware.SensorManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R

/**
 * The quiet form of [CompassCalibrationBanner]: one line in a pill, for screens where the
 * heading is a detail rather than the subject.
 *
 * A low magnetometer reading is a hint, not a fault. Nothing has failed and nothing is
 * waiting on the user — the heading is simply imprecise, and it often fixes itself as they
 * move. On a compass tool that still deserves a card, because the heading *is* the screen;
 * floating over a map it does not, because it is covering the thing the user came to look at
 * to tell them about a needle they may not be using.
 *
 * So this says the one thing worth acting on — how to fix it — and drops the "Low compass
 * accuracy" title, which only names a state the instruction already implies. It sits on
 * `surfaceContainerHigh`, the same container the map's own chips use, so it reads as another
 * piece of map furniture rather than as an alert, and it wraps its content instead of
 * spanning the width.
 *
 * Shares [CompassCalibrationBanner]'s contract of rendering nothing once the sensor reaches
 * high accuracy, so callers can pass the raw accuracy through without guarding the call site.
 */
@Composable
fun CompassCalibrationHint(accuracy: Int, modifier: Modifier = Modifier) {
    if (accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCompass(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.compass_calibrate_figure_8),
                modifier = Modifier.padding(start = Spacing.sm),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
