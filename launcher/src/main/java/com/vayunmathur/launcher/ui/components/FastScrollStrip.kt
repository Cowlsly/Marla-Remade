package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * The A-Z strip's share of the one root gesture, hoisted so both ends can reach it.
 *
 * This is the **second and last** pressure point on the single-gesture-owner rule, and unlike the
 * long-press popup it cannot be resolved with a window: the drawer is a sheet that translates with
 * the [VerticalSwipe] which opens it, so it has to stay inside the home hierarchy. Its scroller
 * therefore cannot install a `pointerInput` of its own — a drag starting on the strip has to be
 * recognised by [launcherDragInput] and routed here.
 *
 * A mutable holder rather than a value for the same reason [LauncherDragController] is one: the
 * gesture owner is keyed on it, so it must be a stable instance, while the thing that knows where
 * the strip *is* and what scrolling it should do is composed inside that gesture owner and changes
 * every time the app list does.
 *
 * Get it wrong and both drawer scrolling and swipe-to-close break, leaving the drawer unexitable by
 * gesture — which is why the strip's own bounds are the whole test: a DOWN inside them is the
 * scroller's, and a DOWN anywhere else is never seen by this at all.
 */
class FastScrollStrip {

    /** Where the strip is, in app-window coordinates. Empty when the drawer is not up. */
    var bounds by mutableStateOf(Rect.Zero)

    /** How many sections the strip is showing, so a fraction can be turned into one of them. */
    var sections by mutableIntStateOf(0)

    /** Jumps the list to the section at this fraction of the strip. */
    var onFraction: (Float) -> Unit = {}

    /**
     * Which fraction the finger is at, or null when it is not on the strip. Read by the letter
     * bubble, which is the only feedback that a fast scroll is happening at all.
     */
    var active by mutableStateOf<Float?>(null)
}
