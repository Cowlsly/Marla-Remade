package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The ring that grows around an icon a drag is about to be folded into.
 *
 * Launcher3 does not just decide whether a drop makes a folder — it shows the decision coming, so
 * folder creation is something the user watched happen rather than something that happened to them.
 * The ring's size and opacity follow [progress] from
 * [com.vayunmathur.launcher.domain.FolderMerge.mergeProgress], which is 0 on the threshold and 1
 * dead centre, so crossing into the merge zone is visible at the moment it happens.
 *
 * Drawn rather than composed as a border, because it changes every frame of a drag and a border on a
 * `Box` would relayout to do it. [progress] is a lambda for the same reason: read inside the draw
 * block, a ring that grows costs a redraw rather than a recomposition of the cell it is around.
 */
@Composable
fun MergeRing(scale: Float, progress: () -> Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(LauncherIconSize * scale * RING_SIZE)
            .drawBehind {
                val shown = progress()
                if (shown <= 0f) return@drawBehind
                val stroke = RING_STROKE.toPx()
                val radius = (size.minDimension / 2f - stroke / 2f) *
                    (RING_FROM + (1f - RING_FROM) * shown)
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(size.width / 2f, size.height / 2f),
                    alpha = shown,
                    style = Stroke(width = stroke),
                )
            },
    )
}

/** Wider than the icon, so the ring reads as being *around* it rather than on top of it. */
private const val RING_SIZE = 1.25f

/** Never from nothing: a ring that starts at zero radius appears as a dot rather than a ring. */
private const val RING_FROM = 0.7f

private val RING_STROKE = 2.dp
