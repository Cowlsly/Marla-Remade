package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import kotlin.math.abs

/**
 * Page dots, with the active one sliding between them. Hidden for a single page, where they only
 * take up room.
 *
 * [scrollProgress] is the pager's position as a fraction of a page — 1.5 meaning halfway between
 * pages one and two — and the active dot follows it continuously, stretching towards the page being
 * moved to as Launcher3's does. That is the difference between an indicator that is part of the
 * gesture and one that repaints itself after the fact.
 *
 * A lambda, and read only inside [drawBehind], for a reason worth stating: the pager's offset
 * changes every frame of a fling. Read at composition scope it would recompose the whole workspace
 * column sixty times a second, and page-fling jank is precisely what the launcher's single
 * gesture owner exists to prevent.
 */
@Composable
fun PageIndicator(
    pageCount: Int,
    scrollProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val color = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .padding(vertical = Spacing.sm)
            .height(DOT_SELECTED)
            .fillMaxWidth()
            .drawBehind {
                val dot = DOT.toPx()
                val active = DOT_SELECTED.toPx()
                val gap = Spacing.sm.toPx()
                val step = dot + gap
                val row = pageCount * dot + (pageCount - 1) * gap
                val left = (size.width - row) / 2f
                val centreY = size.height / 2f

                repeat(pageCount) { page ->
                    drawCircle(
                        color = color,
                        radius = dot / 2f,
                        center = Offset(left + page * step + dot / 2f, centreY),
                        alpha = UNSELECTED_ALPHA,
                    )
                }

                // Stretched across the gap while a page change is in flight: one rounded bar
                // spanning from where it was to where it is going, which is what makes the
                // indicator read as being dragged rather than as switching.
                val progress = scrollProgress().coerceIn(0f, (pageCount - 1).toFloat())
                val centre = left + progress * step + dot / 2f
                // A triangle over the fraction of a page travelled: nothing at either end, most in
                // the middle, where the dot is between two pages and belongs to neither.
                val between = progress - progress.toInt()
                val width = active + active * STRETCH * (1f - abs(1f - 2f * between))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(centre - width / 2f, centreY - active / 2f),
                    size = Size(width, active),
                    cornerRadius = CornerRadius(active / 2f),
                    alpha = SELECTED_ALPHA,
                )
            },
    )
}

private val DOT = 6.dp
private val DOT_SELECTED = 8.dp
private const val SELECTED_ALPHA = 0.9f
private const val UNSELECTED_ALPHA = 0.35f

/** How much longer the active dot gets at the midpoint between two pages. */
private const val STRETCH = 1.2f
