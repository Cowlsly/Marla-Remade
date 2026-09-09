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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * State for [FreeHeightBottomSheetScaffold]: a single [Animatable] holding the
 * sheet's **visible height in pixels**, and nothing else.
 *
 * The sheet rests at whatever height the user leaves it at. There is a peek and an
 * expanded bound, but they are limits rather than anchors — every height between them
 * is a valid resting place, and a fling decays to a stop rather than snapping to one
 * end or the other.
 *
 * One source of truth is what makes that possible. Material 3's `SheetState` is a
 * closed `SheetValue` enum whose `PartiallyExpanded` is hard-pinned to a fixed
 * `sheetPeekHeight` dp, so no arbitrary height is expressible; here every height is
 * just a value. Neither limit is a fixed dp either: the peek is measured off the
 * sheet's own header and the expanded bound off its content.
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

    /**
     * How many host-driven animations — [hide], [expand], [partialExpand] — are in flight.
     *
     * Two things defer to this. [flingBy], because a fling is the tail of a gesture that may
     * already be over: one whose `onPostFling` lands a frame after the host called
     * [partialExpand] would otherwise cancel it and coast off from wherever it had got to. And
     * [armBounds], because a running animation owns the height and a floor installed underneath
     * it does not merely slow it — `Animatable` re-clamps every frame and ends the animation with
     * `BoundReached`, stopping it dead. A [hide] parked at the peek showing empty content is the
     * worst version of that, but the same applies to a sheet being lifted to a peek that just
     * grew. Two callers re-arm during exactly those frames: [resetContentCap], fired by the host
     * swapping content out, and [onContentHeight], fired by the layout pass once that content
     * measures smaller.
     *
     * A count rather than a flag because the three cancel *each other*, and the loser's `finally`
     * runs while the winner is still animating. A boolean would be cleared by that cleanup and
     * leave the winner unprotected for the rest of its run. [growBy] does not clear this: its
     * `snapTo` cancels the animation, and that cancellation decrements the count through the same
     * `finally`. It does not need to — the drag path coerces into `[peekPx, expandedPx]` itself,
     * so a temporarily absent floor cannot let a drag escape below the peek.
     */
    private var hostAnimations = 0

    private val hostAnimating: Boolean
        get() = hostAnimations > 0

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
     * peek height changes — a rotation, a keyboard, a newly measured header — so the
     * bounds and the current height stay consistent with what is on screen.
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
        } else if (offset.value > 0f && offset.value < peekPx && !hostAnimating) {
            // The peek grew out from under a sheet that was resting below it — the usual cause
            // being a measured header that gained a row once async content arrived, which for a
            // place sheet is the tab row appearing a beat after the user opened the POI.
            //
            // `armBounds` alone would handle it, but by teleporting: `updateBounds` clamps the
            // current value into the new bounds synchronously, so the sheet jumps by the height
            // of whatever appeared. Animating there instead makes it read as the sheet growing
            // to fit its content, which is what actually happened.
            animateToHeight { peekPx }
        } else {
            // `updateBounds` re-clamps, so a screen that just got shorter is pulled
            // back inside the new bounds here. A jump is right for that one: a rotation
            // or a keyboard relays out everything on screen at once anyway.
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

    /**
     * Clamp drags and fling decay at the peek, unless the sheet is hidden or a host-driven
     * animation is in flight — see [hostAnimations] for why those must not have a floor put
     * under them.
     *
     * The floor never rises above where the sheet already is. Raising it is the one thing
     * `updateBounds` does that the user can see: it clamps synchronously, so a peek that grew
     * would teleport a resting sheet up by however much it grew. [onMeasured] animates that
     * move instead, and this coerce is what stops whoever re-arms first from doing it abruptly
     * beforehand — [resetContentCap] shares a key with [onMeasured] and would otherwise race it.
     */
    private fun armBounds() {
        val floor = if (hostAnimating || offset.value <= 0f) 0f else peekPx.coerceAtMost(offset.value)
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
        hostAnimations++
        try {
            offset.updateBounds(0f, expandedPx)
            offset.animateTo(0f)
        } finally {
            // Decremented even on cancellation — a hide interrupted by the user grabbing the
            // sheet must hand a normal peek floor back to the drag path.
            hostAnimations--
            armBounds()
        }
    }

    /**
     * `target` is a lambda because the height it names is not known until
     * [onMeasured] has run, which [awaitMeasured] may be waiting for.
     */
    private suspend fun animateToHeight(target: () -> Float) {
        awaitMeasured()
        hostAnimations++
        // Widen the floor so the animation can leave a hidden sheet, then re-arm it
        // on the way out — including on cancellation, when the user grabs the handle
        // mid-flight and the drag path takes over.
        offset.updateBounds(0f, expandedPx)
        try {
            offset.animateTo(target())
        } finally {
            hostAnimations--
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
        // `hostAnimations` is deliberately not touched here even though the user taking hold of
        // the sheet supersedes whatever the host was animating: the `snapTo` below cancels that
        // animation and the cancellation decrements the count through its own `finally`.
        // Decrementing here as well would drive it negative and disarm the guard for good. The
        // window before that cancellation lands is harmless, because the coerce below is what
        // keeps a drag inside the bounds, not the floor.
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
     *
     * Unlike [growBy] this does **not** supersede a host-driven animation, because it is
     * dispatched at the end of every gesture rather than only when the user is holding
     * the sheet — see [hostAnimating].
     */
    internal suspend fun flingBy(velocity: Float): Float {
        if (!measured || hostAnimating || offset.value <= 0f) return velocity
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
 * closed enum with no custom-anchor API, and its `PartiallyExpanded` is pinned to a
 * fixed peek height.
 *
 * **The whole sheet is the drag surface.** A vertical drag anywhere on it moves it,
 * not just on the handle. Where that drag lands on something scrollable, the two are
 * bridged by [sheetNestedScroll] rather than fought over — see its documentation for
 * the handover rule.
 *
 * There are three ways to say how tall the peek is, and they mirror the three Compose
 * already has for height — `wrapContentHeight`, `fillMaxHeight(fraction)`, `height(dp)`:
 *
 * - [sheetHeader], the preferred one. The peek is *measured*: a sheet whose header is a
 *   title and an action row peeks tall enough for exactly those, and one with no action
 *   row peeks shorter, on every density and every font scale, which is the thing a dp
 *   cannot do. Use this whenever the resting height is a statement about the sheet's own
 *   content.
 * - [sheetPeekFraction], for when it is a statement about the *host* instead — "leave
 *   half the map visible" is spatial, not typographic, so it should scale with the window
 *   and should not move when the user changes font size.
 * - [sheetPeekHeight], the fixed fallback, for sheets that have not been given either.
 *
 * They resolve in that order, most specific first.
 *
 * The sheet is **measured** at its current height rather than offset into place.
 * Offsetting alone would leave the sheet's content — typically ending in a
 * `LazyColumn` — measured at full height, so its scroll extent would be wrong and
 * the nested-scroll edge detection below would misfire.
 *
 * The height is a maximum, not an exact size: content shorter than the sheet's
 * current height shrinks the sheet to fit rather than leaving blank surface below
 * it, and that shorter height also becomes the full height a drag can reach.
 *
 * @param sheetHeader the part of the sheet that stays put — what the peek must be
 *   tall enough to show, and all it is tall enough to show. Drawn above
 *   [sheetContent] and draggable along with the handle, because at the peek it is
 *   the only part of the sheet on screen and it would otherwise be dead to the
 *   gesture that expands it. It gets no horizontal padding of its own, so it should
 *   carry the same insets as [sheetContent].
 * @param sheetPeekFraction peek height as a fraction of the room the sheet has — the
 *   window less the status bar, the same quantity that bounds the full height — so
 *   `0.5f` rests at half and leaves the other half of the host on screen. Counts the
 *   navigation bar, unlike [sheetPeekHeight]: a proportion of the window is a
 *   statement about the whole sheet, not about how much content sits above the inset.
 *   Ignored once a [sheetHeader] has measured.
 * @param sheetPeekHeight peek height for a sheet that passes neither of the above,
 *   above the navigation bar.
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
    sheetHeader: (@Composable ColumnScope.() -> Unit)? = null,
    sheetPeekFraction: Float? = null,
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
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var sheetContentHeightPx by remember { mutableIntStateOf(0) }
    var peekRegionPx by remember { mutableIntStateOf(0) }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    // Keyed off the header rather than the region: the drag handle is always there and
    // always measures, so a region height alone cannot tell a sheet with an empty header
    // slot from one that has yet to fill it, and such a sheet would peek at a bare handle.
    val availablePx = (containerHeightPx - statusBarPx).coerceAtLeast(0)
    val peekPx = when {
        headerHeightPx > 0 -> peekRegionPx + navBarPx
        // Guarded on having measured: before the first layout pass a fraction of nothing
        // is nothing, and a peek of zero reads as a sheet that failed to open.
        sheetPeekFraction != null && availablePx > 0 ->
            (availablePx * sheetPeekFraction).roundToInt()
        else -> with(density) { sheetPeekHeight.roundToPx() } + navBarPx
    }
    LaunchedEffect(containerHeightPx, peekPx, statusBarPx) {
        if (containerHeightPx > 0) {
            state.onMeasured(peekPx.toFloat(), availablePx.toFloat())
        }
    }

    // New content may well be taller than whatever the last content settled at, so
    // the learned ceiling cannot carry over.
    LaunchedEffect(contentKey, peekPx) { state.resetContentCap(peekPx.toFloat()) }

    val scope = rememberCoroutineScope()
    val nestedScroll = remember(state, scope) { sheetNestedScroll(state, scope) }
    val peekDp = with(density) { peekPx.toDp() }
    val contentPadding = remember(peekDp) { PaddingValues(bottom = peekDp) }
    val navBarDp = with(density) { navBarPx.toDp() }

    Layout(
        modifier = modifier.fillMaxSize().onSizeChanged { containerHeightPx = it.height },
        content = {
            // Exactly two children, so the measure block below can index them.
            Box { content(contentPadding) }
            Surface(color = sheetContainerColor, shape = BottomSheetDefaults.ExpandedShape) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .nestedScroll(nestedScroll)
                        // On the whole sheet, not just the handle: a vertical drag anywhere
                        // moves it. Where that drag starts on something scrollable the child
                        // claims it first — pointer input resolves leaf-to-root — and the
                        // delta comes back up through `nestedScroll` above instead, which is
                        // the handover. So this only ever fires on the parts with nothing
                        // scrollable under the finger: the header, the action row, the tab
                        // row, the chips, the navigation-bar gutter.
                        //
                        // Vertical orientation, so the horizontally-scrolling photo strip is
                        // not hijacked: this never claims a horizontal gesture.
                        .draggable(
                            state = rememberDraggableState { delta ->
                                // Dragging down is a positive delta but shrinks the sheet.
                                state.growBy(-delta, scope)
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> state.flingBy(-velocity) },
                        )
                ) {
                    SheetPeekRegion(
                        onHeights = { region, header ->
                            peekRegionPx = region
                            headerHeightPx = header
                        },
                        modifier = Modifier.fillMaxWidth().clipToBounds(),
                        header = sheetHeader,
                    )
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
            // A sheet whose header and content both measured to nothing leaves only the
            // drag handle and the navigation-bar inset: a blank bar resting over the host
            // with no information in it. Measured, so the next pass sees it grow, but not
            // drawn. The header counts, because at the peek the content column is squeezed
            // to nothing by design and the header is the whole sheet.
            if (sheetContentHeightPx > 0 || headerHeightPx > 0) {
                sheet.place(0, height - sheet.height)
            }
        }
    }
}

/**
 * The drag handle and [header], measured against unbounded height so what they report
 * is what they *want* rather than what the sheet currently has room for.
 *
 * That distinction is the whole reason this is a `Layout` and not a `Column`. The sheet
 * is measured at whatever height it is at right now, and the peek is derived from this
 * region — so measuring the region against the room on offer would make the peek equal
 * to the height the sheet already had, and a hidden sheet would measure a zero-height
 * region and then peek at nothing. The handle is inside for the same reason: it is part
 * of what the peek has to make room for, and an `onSizeChanged` on it would collapse to
 * zero every time the sheet closed.
 *
 * The region reports its own height clipped to what it was offered rather than squashing
 * its children into it. Below the peek — hidden, or on the way up — the header sliding up
 * behind the sheet's own edge is the reveal; a compressed one is a broken layout.
 *
 * It carries no gesture of its own. The drag lives on the sheet's root column so that the
 * whole surface moves the sheet, and this region is simply part of that surface.
 */
@Composable
private fun SheetPeekRegion(
    onHeights: (region: Int, header: Int) -> Unit,
    modifier: Modifier,
    header: (@Composable ColumnScope.() -> Unit)?,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
            Column { header?.invoke(this) }
        },
    ) { measurables, constraints ->
        val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val handle = measurables[0].measure(unbounded)
        val headerPlaceable = measurables[1].measure(unbounded)
        onHeights(handle.height + headerPlaceable.height, headerPlaceable.height)
        layout(
            constraints.maxWidth,
            (handle.height + headerPlaceable.height).coerceAtMost(constraints.maxHeight),
        ) {
            handle.place(0, 0)
            headerPlaceable.place(0, handle.height)
        }
    }
}

/**
 * Bridge the sheet and the scrollable content inside it so the two do not fight:
 * upward drag grows the sheet until it is fully expanded and only then scrolls the
 * content, and downward drag collapses the sheet only once the content is back at
 * its top. Velocity is handed over on the same conditions, and only the part the
 * sheet actually absorbs is reported as consumed.
 *
 * This is also what makes "drag anywhere" safe over scrollable regions. The sheet's
 * own `draggable` never sees those gestures — the scrollable child claims them first —
 * so without this bridge a drag over the tab panel would scroll content while the
 * sheet sat still, and a drag two inches to the left would move the sheet. The rule
 * above is what makes both feel like the one gesture the user thinks they are making.
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
