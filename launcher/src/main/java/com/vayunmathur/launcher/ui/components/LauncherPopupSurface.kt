package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The look every launcher popup shares: a filled, shadowed container with a caret pointing at the
 * icon it belongs to, growing out of that icon as it opens.
 *
 * One modifier rather than a wrapper composable, so each popup body stays the single public
 * composable in its own file and there is no third file whose only job is to hold a `Surface`.
 *
 * The caret is what makes an anchored popup read as belonging to something. It is drawn in a strip
 * reserved outside the rounded container — above it or below it depending on which side
 * [placement] says the popup ended up on — because a caret clipped by the container's own shape is
 * just a bump.
 *
 * [progress] is a lambda, and read only inside the layer block, so the whole open and close redraws
 * without recomposing any row. The pivot follows the caret, so the popup grows out of the icon
 * rather than out of its own middle.
 */
@Composable
fun Modifier.launcherPopupSurface(placement: PopupPlacement, progress: () -> Float): Modifier {
    val color = MaterialTheme.colorScheme.surface
    val shape = MaterialTheme.shapes.large

    return this
        .graphicsLayer {
            val shown = progress()
            alpha = shown
            val scale = POPUP_FROM + (1f - POPUP_FROM) * shown
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(
                // Growing out of the icon rather than out of its own middle.
                pivotFractionX = (placement.caretX / size.width).coerceIn(0f, 1f),
                pivotFractionY = if (placement.above) 1f else 0f,
            )
        }
        .drawBehind {
            val caret = CARET_HEIGHT.toPx()
            val half = CARET_WIDTH.toPx() / 2f
            // Kept clear of the rounded corners, where a caret would look detached.
            val x = placement.caretX.coerceIn(half + caret, size.width - half - caret)
            val path = Path()
            if (placement.above) {
                path.moveTo(x - half, size.height - caret)
                path.lineTo(x + half, size.height - caret)
                path.lineTo(x, size.height)
            } else {
                path.moveTo(x - half, caret)
                path.lineTo(x + half, caret)
                path.lineTo(x, 0f)
            }
            path.close()
            drawPath(path, color)
        }
        .padding(
            top = if (placement.above) 0.dp else CARET_HEIGHT,
            bottom = if (placement.above) CARET_HEIGHT else 0.dp,
        )
        .shadow(POPUP_ELEVATION, shape)
        .clip(shape)
        .background(color)
        // Launcher3's `popup_vertical_padding`.
        .padding(vertical = 4.dp)
}

/** Launcher3's `popup_arrow_height` and `popup_arrow_width`. */
private val CARET_HEIGHT = 10.dp
private val CARET_WIDTH = 12.dp

/** Off the wallpaper, since the workspace behind it is only dimmed rather than covered. */
private val POPUP_ELEVATION = 6.dp

/** Not from nothing: a popup that starts at zero size reads as a glitch rather than an opening. */
private const val POPUP_FROM = 0.85f
