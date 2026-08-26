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
 *
 * The upper bound is the lesser of the space available and the sheet content's
 * own height, so a sheet holding two lines of text does not stretch to fill the
 * window. Content height is learned by observation — whenever the content
 * measures shorter than the height it was offered, that shorter height becomes
 * the ceiling — because the content typically ends in a `LazyColumn`, whose
 * intrinsic height cannot be queried. The observation is discarded via
 * [resetContentCap] when the host swaps in different content.
 */
@Stable
class FreeHeightSheetState(private val initialValue: SheetValue) {
    /** Visible sheet height, in pixels. `0` is hidden. */
    private val offset = Animatable(0f)

    private var peekPx = 0f
    private var expandedPx = 0f
    /** Space the host can give the sheet, before the content ceiling applies. */
    private var availablePx = 0f
    /** Tallest the content has been seen to need. [Float.MAX_VALUE] = not yet known. */
    private var contentCapPx = Float.MAX_VALUE
    /**
     * False until the host has measured itself and the bounds mean anything. A
     * snapshot state, because a programmatic [expand] can arrive before the first
     * layout pass (a screen restoring its selection) and must wait rather than be
     * dropped.
     */
    private var measured by mutableStateOf(false)

    internal val offsetPx: Float
        get() = offset.value

    /**
     * How far above its resting place map chrome must sit to clear the sheet, in
     * pixels. `0` while the sheet is hidden.
     *
     * **This is a height, not a distance from the top of the window** — the opposite
     * of Material's `SheetState.requireOffset`, so a caller offsets by the *negation*
     * of it (`IntOffset(0, -lift)`) and needs no baseline to difference against.
     *
     * There is also nothing to sample a baseline from: this sheet has no anchors, so
     * a fling rests wherever the decay stops and there is no settled "peek" position.
     * The value is read inside a layout lambda rather than observed, so chrome tracks
     * the sheet every frame without recomposing.
     */
    val liftPx: Float
        get() = offset.value

    internal val expandedHeightPx: Float
        get() = expandedPx

    /**
     * Take the host's measurements. Called whenever the container height or the
     * peek height changes — a rotation, a keyboard, a new peek — so the bounds and
     * the current height stay consistent with what is on screen.
     */
    internal suspend fun onMeasured(peek: Float, expanded: Float) {
        availablePx = expanded
        applyBounds(peek)
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

    /**
     * Derive [expandedPx] and [peekPx] from the available space and the content
     * ceiling. The peek is clamped rather than used as a floor: content shorter
     * than the peek should shrink the sheet, not leave dead space below it.
     */
    private fun applyBounds(peek: Float) {
        expandedPx = availablePx.coerceAtMost(contentCapPx).coerceAtLeast(0f)
        peekPx = peek.coerceAtMost(expandedPx)
    }

    /**
     * Report what the sheet content actually measured when offered `offered`
     * pixels. Slack means the content is fully visible and needs no more room, so
     * `measured` becomes the new ceiling; filling the offer says nothing about how
     * much taller it might be, so the ceiling is left alone and re-probed as the
     * user drags further up.
     */
    internal fun onContentHeight(contentHeight: Float, offered: Float, scope: CoroutineScope) {
        if (!measured || contentHeight <= 0f || contentHeight >= offered) return
        if (contentHeight == contentCapPx) return
        contentCapPx = contentHeight
        applyBounds(peekPx)
        if (offset.value > expandedPx) {
            scope.launch { offset.snapTo(expandedPx) }
        }
        armBounds()
    }

    /**
     * Forget the learned content ceiling, so the next layout re-probes it. The
     * host calls this when it swaps in different content, which may well be
     * taller than whatever the last content settled on.
     */
    internal fun resetContentCap(peek: Float) {
        contentCapPx = Float.MAX_VALUE
        applyBounds(peek)
        armBounds()
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
 * The height is a maximum, not an exact size: content shorter than the sheet's
 * current height shrinks the sheet to fit rather than leaving blank surface below
 * it, and that shorter height also becomes the ceiling a drag can reach.
 *
 * @param sheetPeekHeight visible sheet content height when collapsed, above the
 *   navigation bar. This is the resting height the sheet opens at, so it should be
 *   tall enough to show the content's header. Also the bottom padding handed to
 *   [content], so a collapsed sheet never covers anything [content] pins to the
 *   bottom. Content shorter than this shrinks the sheet below it.
 * @param contentKey identifies what [sheetContent] is currently showing. When it
 *   changes, the learned content-height ceiling is discarded so taller content is
 *   not trapped at the previous content's height.
 */
@Composable
fun FreeHeightBottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    state: FreeHeightSheetState = rememberFreeHeightSheetState(),
    sheetPeekHeight: Dp = BottomSheetDefaults.SheetPeekHeight,
    sheetContainerColor: Color = BottomSheetDefaults.ContainerColor,
    contentKey: Any? = null,
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
    var sheetContentHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(containerHeightPx, peekPx, statusBarPx) {
        if (containerHeightPx > 0) {
            state.onMeasured(peekPx.toFloat(), (containerHeightPx - statusBarPx).toFloat())
        }
    }

    // New content may well be taller than whatever the last content settled at, so
    // the learned ceiling cannot carry over.
    LaunchedEffect(contentKey, peekPx) { state.resetContentCap(peekPx.toFloat()) }

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
                    Column(Modifier.weight(1f, fill = false).padding(bottom = navBarDp)) {
                        Column(
                            Modifier.onSizeChanged { sheetContentHeightPx = it.height },
                            content = sheetContent,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Read in the layout phase, so a drag remeasures without recomposing.
        val offered = state.offsetPx.toInt().coerceIn(0, height)
        val body = measurables[0].measure(constraints)
        // A maximum, not an exact height: a short sheet wraps its content instead of
        // padding itself out with blank surface.
        val sheet = measurables[1].measure(
            constraints.copy(minHeight = 0, maxHeight = offered),
        )
        state.onContentHeight(sheet.height.toFloat(), offered.toFloat(), scope)
        layout(width, height) {
            body.place(0, 0)
            // Content that measured to nothing leaves only the drag handle and the
            // navigation-bar inset: a blank bar resting over the host with no information
            // in it. Measured, so the next pass sees it grow, but not drawn.
            if (sheetContentHeightPx > 0) sheet.place(0, height - sheet.height)
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
