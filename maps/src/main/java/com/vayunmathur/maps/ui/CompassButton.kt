package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.vayunmathur.library.ui.IconCompass
import com.vayunmathur.library.ui.SmallFloatingActionButton
import kotlin.math.abs

/**
 * Heading-up / north-up toggle bound to the camera [bearing] (degrees clockwise
 * from north). The compass rose rotates to keep pointing at true north; tapping
 * it calls [onResetNorth] to snap the camera back to a north-up orientation.
 *
 * Port of Vela's `onCompassTap`: the control only surfaces while the map is
 * rotated — at (near) north-up there's nothing to reset, so it hides itself.
 */
@Composable
fun CompassButton(bearing: Double, onResetNorth: () -> Unit, modifier: Modifier = Modifier) {
    // Normalize to (-180, 180] so a bearing of e.g. 359° reads as -1°.
    val normalized = ((bearing + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    if (abs(normalized) < 1.0) return

    SmallFloatingActionButton(onClick = onResetNorth, modifier = modifier) {
        IconCompass(Modifier.graphicsLayer { rotationZ = -normalized.toFloat() })
    }
}
