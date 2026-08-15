package com.vayunmathur.measure.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.domain.sensor.HeldOrientation
import com.vayunmathur.measure.ui.LevelActions
import com.vayunmathur.measure.ui.LevelUiState
import com.vayunmathur.measure.ui.MeasureViewModel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun LevelPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.level.collectAsState()
    LevelContent(
        state = state,
        actions = viewModel,
        onOpenSettings = { backStack.add(Route.Settings) },
        bottomBar = { MeasureBottomBar(backStack, Route.Level) },
    )
}

@Composable
fun LevelContent(
    state: LevelUiState,
    actions: LevelActions,
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    AppScaffold(
        title = "Level",
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val isLevel = if (state.isFlat) {
                abs(state.pitchDeg) < LEVEL_TOLERANCE_DEG && abs(state.rollDeg) < LEVEL_TOLERANCE_DEG
            } else {
                abs(state.edgeAngleDeg) < LEVEL_TOLERANCE_DEG
            }

            Text(
                if (state.isFlat) {
                    "${Units.formatAngle(state.pitchDeg)} / ${Units.formatAngle(state.rollDeg)}"
                } else {
                    Units.formatAngle(state.edgeAngleDeg)
                },
                style = MaterialTheme.typography.displaySmall,
                color = if (isLevel) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                if (state.isFlat) "Surface level" else state.orientation.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isFlat) {
                SurfaceBubble(
                    pitchDeg = state.pitchDeg,
                    rollDeg = state.rollDeg,
                    // Sized from the leftover height, not the width: a width-driven
                    // square is taller than a landscape window and pushes the controls
                    // off the bottom.
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .padding(16.dp),
                )
            } else {
                EdgeBubble(
                    angleDeg = state.edgeAngleDeg,
                    // Keeps the bar horizontal in the real world whether or not the
                    // activity rotated with the device.
                    uiRotationDeg = state.uiRotationDeg,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            OutlinedButton(onClick = {
                if (state.isCalibrated) actions.clearCalibration() else actions.calibrateZero()
            }) {
                Text(if (state.isCalibrated) "Clear calibration" else "Set zero here")
            }
            if (state.isCalibrated) {
                Text(
                    "Zeroed against a reference surface",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Circular two-axis bubble, used when the phone lies flat. */
@Composable
private fun SurfaceBubble(pitchDeg: Double, rollDeg: Double, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val level = abs(pitchDeg) < LEVEL_TOLERANCE_DEG && abs(rollDeg) < LEVEL_TOLERANCE_DEG

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(surfaceVariant, radius, center)
        drawCircle(outline, radius, center, style = Stroke(2f))
        // Inner ring marks the in-tolerance zone. Sized to the bubble so "inside the
        // ring" and "reads level" agree; a ring scaled to the tolerance angle alone
        // would be a couple of pixels across and useless to aim at.
        val bubbleRadius = min(radius * 0.18f, 44f)
        drawCircle(outline, bubbleRadius * 1.35f, center, style = Stroke(2f))
        drawLine(outline, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
        drawLine(outline, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)

        // Bubble floats toward the raised side, like a real spirit level.
        val nx = (rollDeg / MAX_DISPLAY_DEG).coerceIn(-1.0, 1.0).toFloat()
        val ny = (-pitchDeg / MAX_DISPLAY_DEG).coerceIn(-1.0, 1.0).toFloat()
        val mag = hypot(nx, ny).coerceAtMost(1f)
        val norm = hypot(nx, ny)
        val dirX = if (norm > 0f) nx / norm else 0f
        val dirY = if (norm > 0f) ny / norm else 0f
        val travel = radius - bubbleRadius
        val pos = Offset(center.x + dirX * mag * travel, center.y + dirY * mag * travel)

        drawCircle(
            color = if (level) primary else primary.copy(alpha = 0.6f),
            radius = bubbleRadius,
            center = pos,
        )
    }
}

/**
 * Single-axis bubble for a phone stood on any of its four edges.
 *
 * [angleDeg] is already measured against the nearest quarter turn, so this reads the
 * same whether the phone is upright, upside down, or on its side.
 */
@Composable
private fun EdgeBubble(angleDeg: Double, uiRotationDeg: Float, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val level = abs(angleDeg) < LEVEL_TOLERANCE_DEG

    Canvas(modifier.rotate(uiRotationDeg)) {
        val barHeight = min(size.height, 90f.coerceAtLeast(size.height * 0.4f))
        val top = (size.height - barHeight) / 2f
        val w = size.width

        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(w, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
        drawLine(outline, Offset(w / 2f, top), Offset(w / 2f, top + barHeight), 2f)

        val bubbleRadius = barHeight * 0.35f
        drawCircle(
            outline,
            bubbleRadius * 1.3f,
            Offset(w / 2f, top + barHeight / 2f),
            style = Stroke(2f),
        )

        val n = (angleDeg / MAX_DISPLAY_DEG).coerceIn(-1.0, 1.0).toFloat()
        val cx = w / 2f + n * (w / 2f - bubbleRadius)
        drawCircle(
            color = if (level) primary else primary.copy(alpha = 0.6f),
            radius = bubbleRadius,
            center = Offset(cx, top + barHeight / 2f),
        )
    }
}

/** Deflection at which the bubble reaches the edge of its travel. */
private const val MAX_DISPLAY_DEG = 15.0
private const val LEVEL_TOLERANCE_DEG = 0.35
