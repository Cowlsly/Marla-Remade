package com.vayunmathur.launcher.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.LauncherTuning
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * The oscillation an item shows while it is standing somewhere provisionally.
 *
 * This is the piece that makes a Launcher3 reorder read as a *preview* rather than as a decision
 * already taken. When a drag hovers long enough to displace its neighbours, Launcher3 does two
 * things at once, not one: the neighbours slide to where they would go **and** they pulse gently
 * back towards where they came from, slightly shrunk. Without the pulse the page looks as though it
 * has already been rearranged, so there is nothing to say the arrangement is still in the air.
 *
 * A faithful port of `ReorderPreviewAnimation` in `MODE_PREVIEW`: the nudge is
 * [LauncherTuning.ReorderPreviewMagnitude] of the icon size, aimed **opposite** the direction the
 * item is travelling, decomposed onto both axes by the angle of travel for a diagonal move, over
 * [LauncherTuning.ReorderPreviewMillis] reversing forever. The shrink is AOSP's
 * `DEFAULT_SCALE - CHILD_DIVIDEND / child.width`.
 *
 * Applied in a `graphicsLayer`, so the pulse costs a redraw rather than a recomposition, and only
 * composed while [from] and [to] actually differ — an always-running transition on every cell of
 * every page would invalidate the whole workspace's draw on every frame forever.
 */
@Composable
fun Modifier.reorderPreview(from: CellRect, to: CellRect, iconSizePx: Float): Modifier {
    if (from == to) return this

    val dx = (to.cellX - from.cellX).toFloat()
    val dy = (to.cellY - from.cellY).toFloat()
    val magnitude = LauncherTuning.ReorderPreviewMagnitude * iconSizePx
    // Straight along an axis when the move is along it, and split by the angle of travel when the
    // move is diagonal - so the nudge always points back down the item's own path.
    val nudgeX: Float
    val nudgeY: Float
    when {
        dy == 0f -> {
            nudgeX = -sign(dx) * magnitude
            nudgeY = 0f
        }
        dx == 0f -> {
            nudgeX = 0f
            nudgeY = -sign(dy) * magnitude
        }
        else -> {
            val angle = atan(dy / dx)
            nudgeX = -sign(dx) * abs(cos(angle) * magnitude)
            nudgeY = -sign(dy) * abs(sin(angle) * magnitude)
        }
    }

    val transition = rememberInfiniteTransition(label = "reorderPreview")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(LauncherTuning.ReorderPreviewMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reorderBounce",
    )

    return this.graphicsLayer {
        translationX = nudgeX * phase
        translationY = nudgeY * phase
        val shrunk = 1f - (LauncherTuning.ReorderPreviewShrinkPx / size.width) * phase
        scaleX = shrunk
        scaleY = shrunk
    }
}
