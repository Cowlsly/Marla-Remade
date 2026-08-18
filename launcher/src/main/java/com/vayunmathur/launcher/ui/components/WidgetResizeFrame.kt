package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.WidgetResize
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The resize frame over a selected widget: an outline with a draggable handle on each edge.
 *
 * Three things about the way this looks are Launcher3's, and all three were wrong before:
 *
 *  - **An outline, not a wash.** `widget_resize_frame` is a shape with `solid` transparent and a
 *    [FRAME_STROKE] stroke. A translucent fill over the widget makes its own content unreadable, and
 *    a widget you cannot read is a widget you cannot judge the size of.
 *  - **Outside the widget, not inside it.** The frame is inset from its container by
 *    [FRAME_MARGIN] - Launcher3's `resize_frame_margin` - and the container is grown by the same
 *    amount, so the outline lands *on* the widget's edge rather than inside its content.
 *  - **Handles straddle the edge.** Each sits centred on one side at [HANDLE_MARGIN] from the frame's
 *    bounds (`widget_handle_margin`), so half of it hangs outside the widget. That is what keeps the
 *    widget's interior free: a drag that starts in the middle of the widget is a *move*, and only a
 *    drag that starts on the boundary is a resize.
 *
 * Handles carry their own [pointerInput], which is safe here even though the home screen has a
 * single root gesture owner, because they run on the `Main` pass - child to parent - so a handle
 * beats both the page and the pager to the gesture, while everything it does not claim falls
 * through to the owner as usual.
 *
 * Resizing is **previewed** per whole cell and **committed on release**, as Launcher3 does. A write
 * per step would re-emit the whole workspace mid-gesture and visibly reload every hosted widget on
 * the page, which is the same reason a drag does not write until it lands. The accumulator keeps the
 * leftover pixels so a slow drag across a cell boundary still steps once rather than stalling.
 *
 * [onStep] answers whether the step was taken. A step that would leave the grid, or shove a
 * neighbour into a wall, is refused — and the accumulator is left alone, so continuing to push does
 * nothing until the finger comes back.
 */
@Composable
fun WidgetResizeFrame(
    rect: CellRect,
    cellWidthPx: Int,
    cellHeightPx: Int,
    onStep: (CellRect, WidgetResize.Edge) -> Boolean,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Grown past the widget it wraps, so the outline can trace the widget's own edge and the handles
    // can straddle it. `Modifier.padding` cannot do this - it rejects negative values outright - so
    // the outset is a layout that measures larger than its constraints and places itself back.
    Box(modifier = modifier.outset(FRAME_MARGIN)) {
        // The outline alone. Inset by the same amount it was grown, so it lands on the widget's edge
        // with nothing over its face.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(FRAME_MARGIN)
                .border(
                    width = FRAME_STROKE,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(FRAME_CORNER),
                ),
        )

        ResizeHandle(Alignment.CenterStart, WidgetResize.Edge.Left, cellWidthPx, rect, onStep, onRelease)
        ResizeHandle(Alignment.CenterEnd, WidgetResize.Edge.Right, cellWidthPx, rect, onStep, onRelease)
        ResizeHandle(Alignment.TopCenter, WidgetResize.Edge.Top, cellHeightPx, rect, onStep, onRelease)
        ResizeHandle(Alignment.BottomCenter, WidgetResize.Edge.Bottom, cellHeightPx, rect, onStep, onRelease)
    }
}

/**
 * Measures [all] larger than the incoming constraints on every side and places itself back by the
 * same amount, while still reporting the original size to its parent.
 *
 * The parent's layout is therefore untouched - the widget's cell keeps its size - but this draws and
 * takes touches outside it.
 */
private fun Modifier.outset(all: Dp) = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val edge = all.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(
            width = constraints.maxWidth + edge * 2,
            height = constraints.maxHeight + edge * 2,
        )
    )
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(-edge, -edge)
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    alignment: Alignment,
    edge: WidgetResize.Edge,
    cellSizePx: Int,
    rect: CellRect,
    onStep: (CellRect, WidgetResize.Edge) -> Boolean,
    onRelease: () -> Unit,
) {
    val horizontal = edge == WidgetResize.Edge.Left || edge == WidgetResize.Edge.Right
    // Float state rather than a captured var: the gesture callbacks outlive a recomposition,
    // and the leftover pixels have to survive one or a slow drag loses a step.
    var accumulated by remember { mutableFloatStateOf(0f) }

    // Pulled back so the touch target's *centre* lands on the outline rather than its leading edge,
    // which is what puts the handle on the widget's boundary and leaves the interior draggable.
    val slide = FRAME_MARGIN - HANDLE_TOUCH / 2
    Box(
        modifier = Modifier
            .align(alignment)
            .offset(
                x = if (horizontal) slide * if (edge == WidgetResize.Edge.Left) 1f else -1f else 0.dp,
                y = if (horizontal) 0.dp else slide * if (edge == WidgetResize.Edge.Top) 1f else -1f,
            )
            // A finger-sized touch target around a smaller dot, which is Launcher3's
            // `resize_frame_touch_target_size` against its much smaller handle drawable.
            .size(HANDLE_TOUCH)
            // Keyed on the current rect, so each previewed step restarts the gesture handler
            // against the new geometry rather than against the rect the drag began on.
            .pointerInput(rect, cellSizePx, edge) {
                if (cellSizePx <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = {
                        accumulated = 0f
                        onRelease()
                    },
                    // Cancelled rather than released still ends the gesture, and the preview is
                    // what the user is looking at - so it is what gets written either way.
                    onDragCancel = {
                        accumulated = 0f
                        onRelease()
                    },
                ) { _, dragAmount ->
                    accumulated += if (horizontal) dragAmount.x else dragAmount.y
                    val steps = (accumulated / cellSizePx).toInt()
                    if (steps == 0) return@detectDragGestures
                    val candidate = WidgetResize.resized(rect, edge, steps)
                    if (candidate != null && onStep(candidate, edge)) {
                        accumulated -= steps * cellSizePx
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(HANDLE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Launcher3's `resize_frame_margin`: how far the frame stands off the widget it wraps. */
private val FRAME_MARGIN = 23.dp

/** Launcher3's `resize_frame_touch_target_size`, around a dot small enough not to hide the edge. */
private val HANDLE_TOUCH = 48.dp
private val HANDLE_SIZE = 16.dp

/** The platform's `system_app_widget_background_radius`, which is what a widget's own corners use. */
private val FRAME_CORNER = 16.dp
private val FRAME_STROKE = 2.dp
