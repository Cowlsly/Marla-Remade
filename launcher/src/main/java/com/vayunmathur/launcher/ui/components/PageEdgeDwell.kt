package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.launcher.domain.LauncherTuning
import kotlinx.coroutines.delay

/**
 * Flips pages while a drag hovers near the left or right edge.
 *
 * With the pager's own scrolling disabled for the duration of a drag, this is the only way to
 * move an item between pages. Dwell rather than an immediate flip, because the edge is also
 * where the outermost column of icons lives, and dropping one there has to remain possible.
 */
@Composable
fun PageEdgeDwell(
    controller: LauncherDragController,
    pagerState: PagerState,
    widthPx: Float,
    onPageChange: suspend (Int) -> Unit,
) {
    val edge = widthPx * LauncherTuning.PageEdgeZone
    val x = controller.position.x
    val zone = when {
        !controller.isDragging || widthPx <= 0f -> 0
        x < edge -> -1
        x > widthPx - edge -> 1
        else -> 0
    }

    // Restarting on a page change as well as a zone change is what lets a held finger walk
    // through several pages instead of stopping after one.
    LaunchedEffect(zone, pagerState.currentPage) {
        if (zone == 0) return@LaunchedEffect
        delay(DWELL_MILLIS)
        val next = pagerState.currentPage + zone
        if (next in 0 until pagerState.pageCount) onPageChange(next)
    }
}

/** Long enough that brushing past the edge on the way somewhere does not flip the page. */
private const val DWELL_MILLIS = 500L
