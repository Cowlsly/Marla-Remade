package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.vayunmathur.launcher.domain.FastScroll
import com.vayunmathur.library.ui.Haptics
import com.vayunmathur.library.ui.rememberHaptics
import kotlin.math.abs

/**
 * A vertical swipe over empty space, which is how the app drawer is opened and closed.
 *
 * [claims] is asked, once the drag is clearly vertical, whether this swipe is wanted at all — it
 * is given the accumulated travel, negative upwards. Answering false leaves the gesture untouched,
 * which is what lets the drawer's own list keep its scrolling.
 *
 * [onDrag] receives each frame's delta in pixels so the drawer can track the finger 1:1, and
 * [onRelease] the fling velocity in pixels per second, so it can settle on the throw rather than
 * on where the finger happened to stop.
 */
class VerticalSwipe(
    val claims: (Float) -> Boolean,
    val onDrag: (Float) -> Unit,
    val onRelease: (Float) -> Unit,
)

/**
 * The single root gesture owner, and the only `pointerInput` in the home screen hierarchy.
 *
 * The conflict this resolves: [androidx.compose.foundation.pager.HorizontalPager] wants
 * horizontal drags, and so does an icon being dragged, and both start from the same finger on
 * the same pixel. Anything that hangs a second `pointerInput` off a child re-opens it. There are
 * exactly two sanctioned exceptions, both of which are safe for a stated reason:
 * [WidgetResizeFrame], whose handles run on the `Main` pass while the widget under them has
 * unregistered itself as a drag source, and [LauncherPopup], whose content is in **a window of its
 * own** and therefore in a different pointer hierarchy altogether.
 *
 * It works by arbitrating on [PointerEventPass.Initial], which travels **parent to child**, so
 * this sees every event before the pager's scrollable does — and by **consuming nothing** until a
 * long press actually fires. Up to that moment a tap reaches the icon's `clickable` and a fling
 * reaches the pager exactly as if this modifier were not here.
 *
 * The state machine, one pass per gesture:
 *
 * | State | Entered by | Consumes | Leaves to |
 * | --- | --- | --- | --- |
 * | Idle | DOWN | no | swallow if a popup is open, else wait out the long-press timeout |
 * | DismissSwallow | DOWN while a popup is open | yes | dismisses; a DOWN the home window sees is by definition outside the popup |
 * | FastScrolling | DOWN inside [fastScroll]'s strip | yes | the drawer's A-Z scroller, from the DOWN itself |
 * | Pressed | DOWN anywhere else | no | slop → `SwipeCandidate`; timeout → armed; release → nothing, so a tap still launches |
 * | SwipeCandidate | slop crossed before the timeout | not until claimed | [verticalSwipe] if the travel is vertical, the pager if it is horizontal |
 * | OptionsArmed | timeout with nothing draggable under the finger | yes | [onLongPressEmpty] at the touch point |
 * | PopupArmed | timeout with something draggable under it | **yes, from here** | [onLongPressItem]; releasing leaves the popup up |
 * | Dragging | slop crossed while armed | yes | [onDismissPopup], then the drag proper |
 *
 * `SwipeCandidate` is reached from a press on *anything*, not just on bare wallpaper, and that is
 * deliberate: Launcher3 opens all-apps from a swipe up over an icon as readily as over the
 * wallpaper. Which of the drawer and the pager gets it is decided by the dominant axis inside
 * [trackVerticalSwipe], and the loser has consumed nothing.
 *
 * Two things about `PopupArmed` are load-bearing. It **does not** start a drag — Launcher3 shows
 * the popup on the long press and only starts dragging once the finger moves, and a drag armed
 * eagerly would mean every long press picked the icon up. And it consumes from that moment, which
 * is what cancels the icon's pending click; without it, releasing to dismiss the popup would also
 * launch the app.
 *
 * Positions are translated into window coordinates, because that is what drop targets register
 * their bounds in.
 */
@Composable
fun Modifier.launcherDragInput(
    controller: LauncherDragController,
    onDragStart: (DragPayload) -> Unit = {},
    onLongPressItem: (DragPayload, Rect) -> Unit = { _, _ -> },
    onLongPressEmpty: (Offset) -> Unit = {},
    popupOpen: () -> Boolean = { false },
    onDismissPopup: () -> Unit = {},
    fastScroll: FastScrollStrip? = null,
    verticalSwipe: VerticalSwipe? = null,
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptics = rememberHaptics()

    return this
        .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
        .pointerInput(controller, fastScroll, verticalSwipe) {
            val slop = viewConfiguration.touchSlop
            val longPressMillis = viewConfiguration.longPressTimeoutMillis

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

                if (popupOpen()) {
                    onDismissPopup()
                    swallow(down)
                    return@awaitEachGesture
                }

                // Before the drag-source lookup, and claimed from the DOWN: on the strip there is
                // nothing else the gesture could be, and a fast scroller that waited for slop
                // would ignore the tap that a fast scroller is mostly used with.
                val strip = fastScroll?.takeIf { it.bounds.contains(down.position + origin) }
                if (strip != null) {
                    trackFastScroll(down, origin, strip, haptics)
                    return@awaitEachGesture
                }

                val payload = controller.sourceAt(down.position + origin)

                // One race for every press, whatever is underneath: the long-press timeout against
                // the finger moving. Nothing is consumed either way, so the loser - a tap, a page
                // fling, a drawer swipe - reaches whoever was going to handle it.
                val press = awaitPress(down, slop, longPressMillis)

                if (!press.longPressed) {
                    // Moved first. A vertical drag belongs to the drawer wherever it started; a
                    // horizontal one belongs to the pager, and this bows out of it having consumed
                    // nothing.
                    if (press.moved && verticalSwipe != null) {
                        trackVerticalSwipe(down, slop, verticalSwipe, press)
                    }
                    return@awaitEachGesture
                }

                // The one unambiguous moment in the gesture: the finger has committed to whatever
                // is under it, and nothing on screen has moved yet to say so.
                haptics.longPress()

                if (payload == null) {
                    onLongPressEmpty(down.position + origin)
                    swallow(down)
                    return@awaitEachGesture
                }

                onLongPressItem(payload, payload.sourceBounds)

                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // From here the gesture is ours, so the pager and the icon below both see it
                    // as already handled - which is also what cancels the icon's pending click.
                    change.consume()
                    if (!dragging && (change.position - down.position).getDistance() > slop) {
                        dragging = true
                        onDismissPopup()
                        onDragStart(payload)
                        controller.start(payload, change.position + origin)
                    } else if (dragging) {
                        controller.move(change.position + origin)
                    }
                    if (!change.pressed) break
                }

                // Accepted or refused is the one thing the finger cannot see for itself, since
                // the item flies away either way and only the destination differs.
                if (dragging) {
                    if (controller.drop()) haptics.confirm() else haptics.reject()
                }
            }
        }
}

/**
 * How a press ended: with the long-press timeout, or with the finger moving or leaving first.
 *
 * The travel and the velocity so far come with it, so a swipe that grows out of the press does not
 * restart its axis test from wherever the finger happened to be when the timeout expired.
 */
private class Press(
    val longPressed: Boolean,
    val moved: Boolean,
    val velocity: VelocityTracker,
    val totalDx: Float,
    val totalDy: Float,
)

/**
 * Races the long-press timeout against the finger moving, and consumes nothing either way.
 *
 * That last part is the whole reason this is one function rather than a branch per thing that can be
 * pressed: a tap has to reach an icon's `clickable`, a horizontal fling has to reach the pager, and
 * a vertical drag has to reach the drawer - and until the timeout expires there is no way to know
 * which of the three is happening.
 */
private suspend fun AwaitPointerEventScope.awaitPress(
    down: PointerInputChange,
    slop: Float,
    longPressMillis: Long,
): Press {
    val velocity = VelocityTracker()
    // `addPointerInputChange`, not `addPosition`: a fast flick is delivered as very few events,
    // each carrying several *historical* samples, and only this overload feeds them in. Taking one
    // sample per event instead leaves a flick's velocity badly underestimated - which is what stops
    // the fling from ever being recognised, and makes the drawer feel as though it can only be
    // dragged the whole way open.
    velocity.addPointerInputChange(down)
    var totalDx = 0f
    var totalDy = 0f
    var moved = false

    val longPressed = try {
        withTimeout(longPressMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                totalDx += change.position.x - change.previousPosition.x
                totalDy += change.position.y - change.previousPosition.y
                velocity.addPointerInputChange(change)
                // Released, or moved far enough to be a drag rather than a press.
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

    return Press(longPressed, moved, velocity, totalDx, totalDy)
}

/**
 * Follows a gesture that might be the drawer's, claiming it only once it is clearly vertical.
 *
 * Dominant axis, as Launcher3's `SingleAxisSwipeDetector` uses: whichever of the two has travelled
 * further owns the gesture, horizontal going to the pager and vertical to the drawer. A plain slop
 * crossing on its own is not enough, because a fast diagonal crosses it on both axes at once.
 *
 * The travel accumulated during [awaitPress] is carried in rather than restarted, so the frames
 * spent deciding between this and a long press still count towards the axis test - and towards the
 * distance the drawer has already been dragged.
 */
private suspend fun AwaitPointerEventScope.trackVerticalSwipe(
    down: PointerInputChange,
    slop: Float,
    swipe: VerticalSwipe,
    press: Press,
) {
    val velocity = press.velocity
    var claimed = false
    var totalDx = press.totalDx
    var totalDy = press.totalDy

    // The travel so far may already settle the axis, and there is no guarantee of another event
    // before the finger lifts.
    fun horizontal(): Boolean = abs(totalDx) > slop && abs(totalDx) >= abs(totalDy)
    fun vertical(): Boolean = abs(totalDy) > slop && abs(totalDy) > abs(totalDx)

    if (horizontal()) return
    if (vertical()) {
        claimed = swipe.claims(totalDy)
        if (claimed) swipe.onDrag(totalDy)
    }

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        val dy = change.position.y - change.previousPosition.y
        totalDx += change.position.x - change.previousPosition.x
        totalDy += dy
        velocity.addPointerInputChange(change)

        if (!claimed) {
            if (!change.pressed) break
            // Horizontal first: the pager's, and not to be interfered with.
            if (horizontal()) return
            if (!vertical() || !swipe.claims(totalDy)) continue
            claimed = true
        }

        change.consume()
        swipe.onDrag(dy)
        if (!change.pressed) break
    }

    if (claimed) swipe.onRelease(velocity.calculateVelocity().y)
}

/**
 * Drives the drawer's A-Z strip for as long as the finger is on it.
 *
 * Consumes everything from the DOWN, because the strip overlaps the drawer's own list and the two
 * must not both scroll. A haptic per section crossed, which is the only thing that makes a scroller
 * this fast feel controllable.
 */
private suspend fun AwaitPointerEventScope.trackFastScroll(
    down: PointerInputChange,
    origin: Offset,
    strip: FastScrollStrip,
    haptics: Haptics,
) {
    fun report(y: Float): Int? {
        val bounds = strip.bounds
        if (bounds.height <= 0f) return null
        val fraction = ((y + origin.y - bounds.top) / bounds.height).coerceIn(0f, 1f)
        strip.active = fraction
        strip.onFraction(fraction)
        return FastScroll.sectionAt(fraction, strip.sections)
    }

    down.consume()
    var section = report(down.position.y)
    haptics.tick()

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        val next = report(change.position.y)
        if (next != null && next != section) {
            section = next
            haptics.tick()
        }
        if (!change.pressed) break
    }
    strip.active = null
}

/**
 * Takes the rest of this gesture and gives nothing to anybody.
 *
 * What a dismissal is: the touch that closes a popup must not also land on whatever it was over.
 */
private suspend fun AwaitPointerEventScope.swallow(down: PointerInputChange) {
    down.consume()
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
    }
}
