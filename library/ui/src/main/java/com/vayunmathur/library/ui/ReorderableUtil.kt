package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foundation-based replacement for sh.calvin.reorderable 3.1.0.
 * Implements minimal API surface used in this repo:
 * - rememberReorderableLazyListState / rememberReorderableLazyGridState
 * - ReorderableItem (list + grid)
 * - Modifier.draggableHandle / longPressDraggableHandle
 *
 * [draggableHandle] is iterative: each drag gesture chunk that exceeds a small
 * threshold swaps the dragged item with its neighbour in the drag direction.
 * This preserves the previous position-interpolation logic in NotesListPage etc
 * without requiring exact pixel-to-index mapping. It is a list-only API: counting
 * indices cannot express a grid, where a vertical move is a whole row rather than
 * one step. Grids use [reorderGridDragHandle], which hit-tests against the live
 * layout instead.
 */

data class ReorderableItemInfo(val index: Int, val key: Any = Unit)

@Stable
class ReorderableLazyListState(
    val listState: LazyListState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    /** Accumulated finger delta (px) since the drag started. */
    internal var draggingItemOffset by mutableFloatStateOf(0f)
    /** Layout offset (px) of the dragged item at drag start. */
    internal var draggingItemInitialOffset = 0f

    /**
     * Y translation (px) that keeps the dragged item under the finger while the list
     * reflows it to new slots. Read this from a `graphicsLayer { translationY = ... }`
     * on the dragged item (see [reorderDragHandle]). 0 when nothing is dragging.
     */
    val draggingItemTranslation: Float
        get() {
            val key = draggingKey ?: return 0f
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
            return draggingItemInitialOffset + draggingItemOffset - info.offset
        }

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingIndex = index
        isAnyItemDragging = true
    }

    fun stopDrag() {
        draggingKey = null
        draggingIndex = -1
        isAnyItemDragging = false
    }

    fun move(toIndex: Int) {
        val from = draggingIndex
        if (from == -1 || toIndex == from) return
        if (toIndex < 0) return
        onMoveInternal(from, toIndex)
        draggingIndex = toIndex
    }

    /** Re-evaluate which item the dragged item's centre is over and swap if needed. */
    internal fun updateDragTarget() {
        val key = draggingKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragging = items.firstOrNull { it.key == key } ?: return
        val center = draggingItemInitialOffset + draggingItemOffset + dragging.size / 2f
        val target = items.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        }
        if (target != null && target.index != dragging.index) move(target.index)
    }

    /**
     * One tick of edge auto-scroll: if the dragged item is within a row of the
     * viewport's top/bottom edge, scroll the list in that direction so the user can
     * keep dragging past what's currently visible, then re-check for a swap.
     */
    internal suspend fun autoScrollStep() {
        val key = draggingKey ?: return
        val layout = listState.layoutInfo
        val dragging = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = draggingItemInitialOffset + draggingItemOffset
        val bottom = top + dragging.size
        val edge = dragging.size.toFloat().coerceAtLeast(64f)
        val step = 24f
        val delta = when {
            top < layout.viewportStartOffset + edge -> -step
            bottom > layout.viewportEndOffset - edge -> step
            else -> 0f
        }
        if (delta != 0f) {
            listState.scrollBy(delta)
            updateDragTarget()
        }
    }
}

@Stable
class ReorderableLazyGridState(
    val gridState: LazyGridState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    /** Accumulated finger delta (px) since the drag started. */
    internal var draggingItemOffset by mutableStateOf(Offset.Zero)
    /** Layout offset (px) of the dragged item at drag start. */
    internal var draggingItemInitialOffset = Offset.Zero

    /**
     * Translation (px) that keeps the dragged tile under the finger while the grid reflows it into
     * new slots. Both axes, unlike the list's, because a grid moves sideways too. Read it from a
     * `graphicsLayer { }` on the dragged item; [Offset.Zero] when nothing is dragging.
     */
    val draggingItemTranslation: Offset
        get() {
            val key = draggingKey ?: return Offset.Zero
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                ?: return Offset.Zero
            return draggingItemInitialOffset + draggingItemOffset - info.offset.toOffset()
        }

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingIndex = index
        isAnyItemDragging = true
    }

    fun stopDrag() {
        draggingKey = null
        draggingIndex = -1
        isAnyItemDragging = false
    }

    fun move(toIndex: Int) {
        val from = draggingIndex
        if (from == -1 || toIndex == from) return
        if (toIndex < 0) return
        onMoveInternal(from, toIndex)
        draggingIndex = toIndex
    }

    /**
     * Re-evaluate which tile the dragged tile's centre is over and swap if needed, reporting
     * whether it swapped.
     *
     * Tested by position against the live layout rather than by counting indices, so the number of
     * columns never enters the arithmetic and a diagonal drag lands where it looks like it should.
     * [itemCount] bounds the reorderable prefix of the grid: a grid whose last slot is a footer
     * must not have items moved into it.
     */
    internal fun updateDragTarget(itemCount: Int): Boolean {
        val key = draggingKey ?: return false
        val items = gridState.layoutInfo.visibleItemsInfo
        val dragging = items.firstOrNull { it.key == key } ?: return false
        val centre = draggingItemInitialOffset + draggingItemOffset +
            Offset(dragging.size.width / 2f, dragging.size.height / 2f)
        val target = items.firstOrNull {
            it.key != key && it.index < itemCount &&
                centre.x >= it.offset.x && centre.x <= it.offset.x + it.size.width &&
                centre.y >= it.offset.y && centre.y <= it.offset.y + it.size.height
        } ?: return false
        if (target.index == dragging.index) return false
        // From the layout rather than the stored index, so a move the caller declined or clamped
        // cannot leave the two drifting apart for the rest of the drag.
        draggingIndex = dragging.index
        move(target.index)
        return true
    }

    /**
     * One tick of edge auto-scroll: if the dragged tile is within a row of the viewport's top or
     * bottom edge, scroll that way so the drag can continue past what is currently visible.
     */
    internal suspend fun autoScrollStep(itemCount: Int) {
        val key = draggingKey ?: return
        val layout = gridState.layoutInfo
        val dragging = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = draggingItemInitialOffset.y + draggingItemOffset.y
        val bottom = top + dragging.size.height
        val edge = dragging.size.height.toFloat().coerceAtLeast(64f)
        val step = 24f
        val delta = when {
            top < layout.viewportStartOffset + edge -> -step
            bottom > layout.viewportEndOffset - edge -> step
            else -> 0f
        }
        if (delta != 0f) {
            gridState.scrollBy(delta)
            updateDragTarget(itemCount)
        }
    }
}

@Composable
fun rememberReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyListState {
    return remember(lazyListState) {
        ReorderableLazyListState(lazyListState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun rememberReorderableLazyGridState(
    gridState: LazyGridState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyGridState {
    return remember(gridState) {
        ReorderableLazyGridState(gridState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyGridState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

private const val DRAG_THRESHOLD = 18f

fun Modifier.draggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
    onDragStarted: suspend CoroutineScope.(medium: Any) -> Unit = {},
    onDragStopped: suspend CoroutineScope.() -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(key, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                reorderState.startDrag(key, index)
                scope.launch { onDragStarted(Unit) }
            },
            onDragEnd = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDragCancel = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingIndex == -1) return@detectDragGesturesAfterLongPress
                val dy = dragAmount.y
                if (dy > DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex + 1)
                } else if (dy < -DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex - 1)
                }
            }
        )
    }
}

fun Modifier.longPressDraggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
): Modifier = draggableHandle(reorderState, key, index)

/**
 * Immediate (no long-press) drag handle with **true finger-following**: while you
 * drag, the item translates 1:1 with your finger via
 * [ReorderableLazyListState.draggingItemTranslation], and it swaps with whichever
 * item its centre is currently over (using the live [LazyListState] layout). The
 * gesture is consumed so it never fights the list's own vertical scroll.
 *
 * Apply to a small handle element, and give the dragged item root
 * `Modifier.zIndex(1f).graphicsLayer { translationY = state.draggingItemTranslation }`
 * while it's dragging, and `Modifier.animateItem()` otherwise (so the others glide).
 */
fun Modifier.reorderDragHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    pointerInput(key) {
        detectDragGestures(
            onDragStart = {
                reorderState.listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == key }
                    ?.let { info ->
                        reorderState.startDrag(key, info.index)
                        reorderState.draggingItemInitialOffset = info.offset.toFloat()
                        reorderState.draggingItemOffset = 0f
                        onDragStarted()
                        // Keep scrolling while the dragged item is held near an edge.
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            while (isActive && reorderState.draggingKey != null) {
                                reorderState.autoScrollStep()
                                delay(16)
                            }
                        }
                    }
            },
            onDragEnd = {
                scrollJob?.cancel(); scrollJob = null
                reorderState.stopDrag()
                reorderState.draggingItemOffset = 0f
                onDragStopped()
            },
            onDragCancel = {
                scrollJob?.cancel(); scrollJob = null
                reorderState.stopDrag()
                reorderState.draggingItemOffset = 0f
                onDragStopped()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingKey != null) {
                    reorderState.draggingItemOffset += dragAmount.y
                    reorderState.updateDragTarget()
                }
            },
        )
    }
}

/**
 * The sideways branch of [reorderGridDragHandle]: what a horizontal drag means when it is *not* a
 * reorder — dismissing the tile, typically.
 *
 * [enabled] is asked once the drag has started, so a caller can turn the branch off for the state
 * where the flick would be meaningless or destructive. [onDrag] gets the accumulated travel so the
 * tile can be translated under the finger, and [onRelease] the travel and the fling velocity
 * together, so a short fast flick and a long slow drag can both count as the same intent.
 */
class HorizontalFlick(
    val enabled: () -> Boolean = { true },
    val onDrag: (totalDx: Float) -> Unit,
    val onRelease: (totalDx: Float, velocity: Float) -> Unit,
)

/**
 * A grid tile that can be picked up and reordered, and optionally flicked sideways for something
 * else, from one gesture that decides which by **intent at the start** rather than by what the
 * finger happened to be doing at release.
 *
 * | Branch | Entered by | Consumes |
 * | --- | --- | --- |
 * | Reorder | the long-press timeout expiring | yes, from the pick-up |
 * | [horizontalFlick] | slop crossed with the travel clearly horizontal | yes, from the claim |
 * | nothing | slop crossed vertically, or released first | **no** |
 *
 * Consuming nothing until a branch commits is the load-bearing part: it is why a tap still reaches
 * the tile's own `onClick` and why a vertical fling still reaches the grid's scroll. The two are
 * raced against each other rather than resolved by release velocity, because a reorder is very
 * often released mid-motion — deciding at release would fire the sideways action on drags the user
 * meant as a move.
 *
 * The dominant-axis test is [SwipeActionsBox]'s: horizontal has to beat vertical by
 * [horizontalBias] to claim, and vertical winning bails out entirely. A plain slop crossing is not
 * enough, because a fast diagonal crosses it on both axes at once.
 *
 * Apply this to a node **inside** whatever owns the tile's click, not to the tile's own root: on
 * the `Main` pass the inner node is offered the event first, which is what lets it consume the UP
 * before a surrounding `clickable` can read it as a tap.
 *
 * The same ordering is what keeps a button *inside* the tile — a close or delete affordance — out of
 * the drag: it is deeper still, so its `clickable` consumes the DOWN before this ever sees it, and
 * the DOWN is required unconsumed here. Without that a long press on the button would pick the
 * whole tile up as well.
 *
 * [itemCount] is how many leading items are reorderable, so a grid ending in a footer does not
 * accept a tile dropped onto it. Give the dragged item root
 * `Modifier.zIndex(1f).graphicsLayer { }` reading
 * [ReorderableLazyGridState.draggingItemTranslation] while it is dragging, and `itemMotion()`
 * otherwise so the rest glide aside.
 */
fun Modifier.reorderGridDragHandle(
    reorderState: ReorderableLazyGridState,
    key: Any,
    itemCount: Int,
    onDragStarted: () -> Unit = {},
    onSwap: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    horizontalFlick: HorizontalFlick? = null,
    horizontalBias: Float = 2.5f,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    // Read through `rememberUpdatedState` rather than keyed into `pointerInput`: a fresh lambda or
    // a changed count each recomposition would otherwise restart the detector mid-drag.
    val count by rememberUpdatedState(itemCount)
    val flick by rememberUpdatedState(horizontalFlick)
    val bias by rememberUpdatedState(horizontalBias)
    val dragStarted by rememberUpdatedState(onDragStarted)
    val swapped by rememberUpdatedState(onSwap)
    val dragStopped by rememberUpdatedState(onDragStopped)

    pointerInput(reorderState, key) {
        val slop = viewConfiguration.touchSlop
        val longPressMillis = viewConfiguration.longPressTimeoutMillis

        awaitEachGesture {
            // Unconsumed only: a DOWN already claimed by a deeper node belongs to that node for the
            // whole gesture, so neither branch may start on it.
            val down = awaitFirstDown(requireUnconsumed = true)
            val press = awaitGridPress(down, slop, longPressMillis)

            if (!press.longPressed) {
                val branch = flick
                if (press.moved && branch != null && branch.enabled()) {
                    trackHorizontalFlick(down, slop, bias, branch, press)
                }
                return@awaitEachGesture
            }

            val info = reorderState.gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == key } ?: return@awaitEachGesture
            reorderState.startDrag(key, info.index)
            reorderState.draggingItemInitialOffset = info.offset.toOffset()
            reorderState.draggingItemOffset = Offset.Zero
            dragStarted()

            scrollJob?.cancel()
            scrollJob = scope.launch {
                while (isActive && reorderState.draggingKey != null) {
                    reorderState.autoScrollStep(count)
                    delay(16)
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                reorderState.draggingItemOffset += change.position - change.previousPosition
                if (reorderState.updateDragTarget(count)) swapped()
                if (!change.pressed) break
            }

            scrollJob?.cancel()
            scrollJob = null
            reorderState.stopDrag()
            reorderState.draggingItemOffset = Offset.Zero
            dragStopped()
        }
    }
}

/** How a press ended: with the long-press timeout, or with the finger moving or leaving first. */
private class GridPress(
    val longPressed: Boolean,
    val moved: Boolean,
    val velocity: VelocityTracker,
    val totalDx: Float,
    val totalDy: Float,
)

/**
 * Races the long-press timeout against the finger moving, and consumes nothing either way — until
 * the timeout expires there is no way to tell a pick-up from a tap, a sideways flick or a scroll.
 *
 * The travel and velocity so far come back with it, so a flick that grows out of the press does not
 * restart its axis test from wherever the finger was when the race ended.
 */
private suspend fun AwaitPointerEventScope.awaitGridPress(
    down: PointerInputChange,
    slop: Float,
    longPressMillis: Long,
): GridPress {
    val velocity = VelocityTracker()
    // `addPointerInputChange`, not `addPosition`: a fast flick arrives as very few events, each
    // carrying several historical samples, and only this overload feeds them all in. One sample per
    // event leaves a flick's velocity badly under-estimated.
    velocity.addPointerInputChange(down)
    var totalDx = 0f
    var totalDy = 0f
    var moved = false

    val longPressed = try {
        withTimeout(longPressMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                totalDx += change.position.x - change.previousPosition.x
                totalDy += change.position.y - change.previousPosition.y
                velocity.addPointerInputChange(change)
                if (!change.pressed) break
                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                    moved = true
                    break
                }
            }
        }
        false
    } catch (_: PointerEventTimeoutCancellationException) {
        true
    }

    return GridPress(longPressed, moved, velocity, totalDx, totalDy)
}

/**
 * Follows a drag that might be a sideways flick, claiming it only once it is clearly horizontal.
 *
 * Vertical winning returns without having consumed anything, which is what leaves the grid free to
 * scroll; the caller is left with a gesture it never touched.
 */
private suspend fun AwaitPointerEventScope.trackHorizontalFlick(
    down: PointerInputChange,
    slop: Float,
    horizontalBias: Float,
    flick: HorizontalFlick,
    press: GridPress,
) {
    val velocity = press.velocity
    var claimed = false
    var totalDx = press.totalDx
    var totalDy = press.totalDy

    // The travel so far may already settle the axis, and there is no guarantee of another event
    // before the finger lifts.
    fun vertical(): Boolean = abs(totalDy) > slop && abs(totalDy) >= abs(totalDx)
    fun horizontal(): Boolean = abs(totalDx) > slop && abs(totalDx) > abs(totalDy) * horizontalBias

    if (vertical()) return
    if (horizontal()) {
        claimed = true
        flick.onDrag(totalDx)
    }

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        totalDx += change.position.x - change.previousPosition.x
        totalDy += change.position.y - change.previousPosition.y
        velocity.addPointerInputChange(change)

        if (!claimed) {
            if (!change.pressed) break
            if (vertical()) return
            if (!horizontal()) continue
            claimed = true
        }

        change.consume()
        flick.onDrag(totalDx)
        if (!change.pressed) break
    }

    if (claimed) flick.onRelease(totalDx, velocity.calculateVelocity().x)
}
