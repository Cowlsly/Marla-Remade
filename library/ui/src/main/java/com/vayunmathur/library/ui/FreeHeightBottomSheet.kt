package com.vayunmathur.library.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * State for [FreeHeightBottomSheetScaffold]: a single [Animatable] holding the
 * sheet's **visible height in pixels**, and nothing else.
 *
 * One source of truth is what makes a free-height sheet possible. Material 3's
 * `SheetState` is a closed `SheetValue` enum whose `PartiallyExpanded` is
 * hard-pinned to `sheetPeekHeight`, so no arbitrary height is expressible; here
 * every height between the peek and the expanded bound is just a value.
 *
 * [SheetValue.Hidden] is reachable only through [hide]. A drag clamps at the peek
 * and [Animatable.updateBounds] makes a fling decay stop there too, so
 * "the user cannot dismiss this sheet" is enforced by construction rather than by
 * vetoing state changes after the fact.
 */
@Stable
class FreeHeightSheetState(private val initialValue: SheetValue) {
    /** Visible sheet height, in pixels. `0` is hidden. */
    private val offset = Animatable(0f)

    private var peekPx = 0f
    private var expandedPx = 0f
    /**
     * False until the host has measured itself and the bounds mean anything. A
     * snapshot state, because a programmatic [expand] can arrive before the first
     * layout pass (a screen restoring its selection) and must wait rather than be
     * dropped.
     */
    private var measured by mutableStateOf(false)

    internal val offsetPx: Float
        get() = offset.value

    internal val expandedHeightPx: Float
        get() = expandedPx

    /**
     * Take the host's measurements. Called whenever the container height or the
     * peek height changes — a rotation, a keyboard, a new peek — so the bounds and
     * the current height stay consistent with what is on screen.
     */
    internal suspend fun onMeasured(peek: Float, expanded: Float) {
        peekPx = peek
        expandedPx = expanded.coerceAtLeast(peek)
        if (!measured) {
            offset.snapTo(
                when (initialValue) {
                    SheetValue.Hidden -> 0f
                    SheetValue.PartiallyExpanded -> peekPx
                    else -> expandedPx
                }
            )
            // Last, so anything waiting in `awaitMeasured` resumes to a sheet that is
            // already at its initial height with its bounds armed.
            armBounds()
            measured = true
        } else {
            // `updateBounds` re-clamps, so a screen that just got shorter is pulled
            // back inside the new bounds here.
            armBounds()
        }
    }

    private suspend fun awaitMeasured() {
        if (!measured) {
            snapshotFlow { measured }.first { it }
        }
    }

    /** Clamp drags and fling decay at the peek, unless the sheet is hidden. */
    private fun armBounds() {
        val floor = if (offset.value <= 0f) 0f else peekPx
        offset.updateBounds(floor.coerceAtMost(expandedPx), expandedPx)
    }

    /** Raise the sheet to its full height. */
    suspend fun expand() = animateToHeight { expandedPx }

    /** Drop the sheet back to its peek height. */
    suspend fun partialExpand() = animateToHeight { peekPx }

    /**
     * Dismiss the sheet. The only way to reach a zero height: the drag and fling
     * paths both floor at the peek.
     */
    suspend fun hide() {
        awaitMeasured()
        offset.updateBounds(0f, expandedPx)
        offset.animateTo(0f)
    }

    /**
     * `target` is a lambda because the height it names is not known until
     * [onMeasured] has run, which [awaitMeasured] may be waiting for.
     */
    private suspend fun animateToHeight(target: () -> Float) {
        awaitMeasured()
        // Widen the floor so the animation can leave a hidden sheet, then re-arm it
        // on the way out — including on cancellation, when the user grabs the handle
        // mid-flight and the drag path takes over.
        offset.updateBounds(0f, expandedPx)
        try {
            offset.animateTo(target())
        } finally {
            armBounds()
        }
    }

    /**
     * Grow the sheet by `growth` pixels (negative shrinks), returning how much of
     * that the sheet actually absorbed. Callers use the difference to decide how
     * much of a gesture to report as consumed.
     */
    internal fun growBy(growth: Float, scope: CoroutineScope): Float {
        if (!measured || offset.value <= 0f) return 0f
        val target = (offset.value + growth).coerceIn(peekPx, expandedPx)
        val applied = target - offset.value
        if (applied != 0f) {
            // A new snapTo cancels whatever animation was running, which is exactly
            // what grabbing the sheet mid-fling should do.
            scope.launch { offset.snapTo(target) }
        }
        return applied
    }

    /**
     * Fling the sheet, returning the velocity it could **not** absorb. There is
     * deliberately no settle-to-nearest-anchor afterwards: wherever the decay stops
     * is where the sheet stays.
     */
    internal suspend fun flingBy(velocity: Float): Float {
        if (!measured || offset.value <= 0f) return velocity
        armBounds()
        return offset.animateDecay(velocity, exponentialDecay()).endState.velocity
    }
}

@Composable
fun rememberFreeHeightSheetState(
    initialValue: SheetValue = SheetValue.PartiallyExpanded,
): FreeHeightSheetState = remember { FreeHeightSheetState(initialValue) }

/**
 * A bottom sheet over full-bleed content that rests at **any** height the user
 * drags or flings it to, rather than snapping to a peek/expanded pair.
 *
 * Drop-in shaped like Material 3's `BottomSheetScaffold` so a screen can move
 * across without restructuring, but it is not built on it: `SheetValue` is a
 * closed enum with no custom-anchor API, and `PartiallyExpanded` is pinned to the
 * peek height.
 *
 * The sheet is **measured** at its current height rather than offset into place.
 * Offsetting alone would leave the sheet's content — typically ending in a
 * `LazyColumn` — measured at full height, so its scroll extent would be wrong and
 * the nested-scroll edge detection below would misfire.
 *
 * @param sheetPeekHeight visible sheet content height when collapsed, above the
 *   navigation bar. Also the bottom padding handed to [content], so a collapsed
 *   sheet never covers anything [content] pins to the bottom.
 */
@Composable
fun FreeHeightBottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    state: FreeHeightSheetState = rememberFreeHeightSheetState(),
    sheetPeekHeight: Dp = BottomSheetDefaults.SheetPeekHeight,
    sheetContainerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    // The window insets are read as values, not applied as modifiers: the peek has
    // to clear the navigation bar and the expanded sheet must stop below the status
    // bar, or its drag handle ends up untappable underneath it.
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    val peekPx = with(density) { sheetPeekHeight.roundToPx() } + navBarPx
    var containerHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(containerHeightPx, peekPx, statusBarPx) {
        if (containerHeightPx > 0) {
            state.onMeasured(peekPx.toFloat(), (containerHeightPx - statusBarPx).toFloat())
        }
    }

    val scope = rememberCoroutineScope()
    val nestedScroll = remember(state, scope) { sheetNestedScroll(state, scope) }
    val contentPadding = remember(sheetPeekHeight) { PaddingValues(bottom = sheetPeekHeight) }
    val navBarDp = with(density) { navBarPx.toDp() }

    Layout(
        modifier = modifier.fillMaxSize().onSizeChanged { containerHeightPx = it.height },
        content = {
            // Exactly two children, so the measure block below can index them.
            Box { content(contentPadding) }
            Surface(color = sheetContainerColor, shape = BottomSheetDefaults.ExpandedShape) {
                Column(Modifier.fillMaxWidth().nestedScroll(nestedScroll)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    // Dragging down is a positive delta but shrinks the sheet.
                                    state.growBy(-delta, scope)
                                },
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity -> state.flingBy(-velocity) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                    Column(
                        Modifier.weight(1f, fill = false).padding(bottom = navBarDp),
                        content = sheetContent,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Read in the layout phase, so a drag remeasures without recomposing.
        val sheetHeight = state.offsetPx.toInt().coerceIn(0, height)
        val body = measurables[0].measure(constraints)
        val sheet = measurables[1].measure(
            constraints.copy(minHeight = sheetHeight, maxHeight = sheetHeight),
        )
        layout(width, height) {
            body.place(0, 0)
            sheet.place(0, height - sheetHeight)
        }
    }
}

/**
 * Bridge the sheet and the scrollable content inside it so the two do not fight:
 * upward drag grows the sheet until it is fully expanded and only then scrolls the
 * content, and downward drag collapses the sheet only once the content is back at
 * its top. Velocity is handed over on the same conditions, and only the part the
 * sheet actually absorbs is reported as consumed.
 */
private fun sheetNestedScroll(
    state: FreeHeightSheetState,
    scope: CoroutineScope,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource) =
        if (source == NestedScrollSource.UserInput &&
            available.y < 0f &&
            state.offsetPx < state.expandedHeightPx
        ) {
            Offset(0f, -state.growBy(-available.y, scope))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ) = if (source == NestedScrollSource.UserInput && available.y > 0f) {
        Offset(0f, -state.growBy(-available.y, scope))
    } else {
        Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val growth = -available.y
        if (growth <= 0f || state.offsetPx >= state.expandedHeightPx) return Velocity.Zero
        return Velocity(0f, -(growth - state.flingBy(growth)))
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val growth = -available.y
        if (growth >= 0f) return Velocity.Zero
        return Velocity(0f, -(growth - state.flingBy(growth)))
    }
}
