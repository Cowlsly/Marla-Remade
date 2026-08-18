package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** Which side of its anchor a popup ended up on, and where the anchor is within it. */
data class PopupPlacement(
    /** True when there was no room below the anchor, so the popup opened upwards instead. */
    val above: Boolean = false,
    /** The anchor's centre, in the popup's own pixels, so a caret can point at it. */
    val caretX: Float = 0f,
)

/**
 * An anchored popup in a window of its own, as Launcher3's long-press popup is.
 *
 * **This is the one sanctioned exception to the single-gesture-owner rule besides
 * [WidgetResizeFrame], and the window is the reason it is safe.** The popup has to appear while the
 * finger that opened it is still down, its rows have to stay tappable, its shortcut rows have to
 * stay draggable onto the grid — and inside the home hierarchy none of that is possible: it would
 * be the second `pointerInput` that [launcherDragInput] forbids, and once a drag arms, the root
 * consumes on [androidx.compose.ui.input.pointer.PointerEventPass.Initial] and starves every
 * descendant anyway.
 *
 * A [Popup] hosts its content in a separate `AndroidComposeView`, so its pointer handling is a
 * different hierarchy altogether — the single-owner invariant governs the home hierarchy only — and
 * a touch inside its bounds never reaches the pager's `scrollable`. The properties matter as much
 * as the window:
 *
 *  - `focusable = false`, because a focusable window would take the IME and the back dispatcher
 *    from the Activity. Non-focusable windows are still *touchable*, so the rows still work.
 *  - `dismissOnClickOutside = false` and `dismissOnBackPress = false`, because dismissal belongs to
 *    the gesture owner: it sees every DOWN the home window gets, and a DOWN the home window sees is
 *    by definition outside this popup.
 *
 * The scrim behind it is drawn in the home composition rather than here, for the same reason: a
 * scrim with a window of its own would be a second thing arbitrating touches.
 *
 * [anchor] is in app-window coordinates, which is what every rect in the drag machinery is in. The
 * offset the popup was finally placed at is published through [LocalPopupWindowOffset] so anything
 * inside can translate its own popup-local coordinates back — [onAppWindowBounds] and [dragSource]
 * both do it for free.
 */
@Composable
fun LauncherPopup(
    anchor: Rect,
    modifier: Modifier = Modifier,
    gap: Dp = POPUP_GAP,
    margin: Dp = POPUP_MARGIN,
    content: @Composable (PopupPlacement) -> Unit,
) {
    val density = LocalDensity.current
    var placement by remember { mutableStateOf(PopupPlacement()) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    val provider = remember(anchor, gap, margin, density) {
        AnchoredPositionProvider(
            anchor = anchor,
            gapPx = with(density) { gap.roundToPx() },
            marginPx = with(density) { margin.roundToPx() },
            onPlaced = { at, side ->
                origin = Offset(at.x.toFloat(), at.y.toFloat())
                placement = side
            },
        )
    }

    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        CompositionLocalProvider(LocalPopupWindowOffset provides origin) {
            Box(modifier = modifier) { content(placement) }
        }
    }
}

/**
 * Below the anchor when there is room, above it when there is not, and clamped into the window.
 *
 * An icon in the hotseat has nothing below it, and a popup clipped off the bottom of the screen
 * would hide the row it is most important to be able to reach.
 *
 * [onPlaced] reports the result, because two things outside the layout need it: the caret has to
 * point back at the anchor, and anything registering with the drag controller from inside the
 * popup has to translate its coordinates by the offset chosen here.
 */
private class AnchoredPositionProvider(
    private val anchor: Rect,
    private val gapPx: Int,
    private val marginPx: Int,
    private val onPlaced: (IntOffset, PopupPlacement) -> Unit,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // `anchorBounds` is the popup's parent node, which is the whole home screen; the anchor
        // that matters is the icon, and it arrives in window coordinates already.
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchor.center.x - popupContentSize.width / 2f).roundToInt().coerceIn(marginPx, maxX)

        val below = anchor.bottom.roundToInt() + gapPx
        val fitsBelow = below + popupContentSize.height + marginPx <= windowSize.height
        val y = if (fitsBelow) {
            below
        } else {
            (anchor.top.roundToInt() - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        }

        val at = IntOffset(x, y)
        onPlaced(at, PopupPlacement(above = !fitsBelow, caretX = anchor.center.x - x))
        return at
    }
}

/** Clear of the icon it belongs to, close enough to read as belonging to it. */
private val POPUP_GAP = 8.dp

/** Never flush against the edge of the screen, however far into a corner the anchor is. */
private val POPUP_MARGIN = 12.dp
