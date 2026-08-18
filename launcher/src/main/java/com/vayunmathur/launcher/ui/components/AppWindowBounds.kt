package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * How far this composition's window is from the app window's origin.
 *
 * Zero everywhere except inside a [LauncherPopup] or a bottom sheet, whose content is hosted in a
 * window of its own — so [androidx.compose.ui.layout.LayoutCoordinates.boundsInWindow] there
 * measures against *that* window and lands a couple of hundred pixels away from where the drag
 * machinery thinks it is. Every drop target's bounds, every drag source's hit rect and every
 * fly-back rect are in app-window coordinates, so anything registering from inside another window
 * has to be translated first.
 *
 * Provided by whoever creates the window, because only it knows where the window was put.
 */
val LocalPopupWindowOffset = compositionLocalOf { Offset.Zero }

/**
 * Reports this composable's bounds in the **app** window, wherever it happens to be composed.
 *
 * The one thing to use instead of `onGloballyPositioned { boundsInWindow() }` for any rect that
 * will be handed to [LauncherDragController]. In the home window it is exactly that; inside a
 * popup or a sheet it is that plus [LocalPopupWindowOffset].
 */
@Composable
fun Modifier.onAppWindowBounds(onBounds: (Rect) -> Unit): Modifier {
    val offset = LocalPopupWindowOffset.current
    return this.onGloballyPositioned { onBounds(it.boundsInWindow().translate(offset)) }
}
