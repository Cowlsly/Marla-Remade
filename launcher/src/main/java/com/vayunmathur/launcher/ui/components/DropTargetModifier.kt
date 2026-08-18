package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registers this composable as a drop target for as long as it is composed.
 *
 * Self-registering rather than a list of rectangles held by the home screen: the hotseat slots,
 * every folder icon and the Remove bar all come and go independently, so a central list would
 * have to be rebuilt on every workspace change and would go stale mid-drag.
 *
 * Re-registered on every composition, because the handlers close over state a recomposition may
 * have replaced — a stale registration would commit a drop against yesterday's grid.
 *
 * [onDrop] returns the window rect the item landed in, or null to refuse it; see [DropTarget].
 */
@Composable
fun Modifier.dropTarget(
    key: Any,
    priority: Int = 0,
    accepts: (DragPayload) -> Boolean = { true },
    onEnter: (DragPayload) -> Unit = {},
    onExit: () -> Unit = {},
    onDrop: (DragPayload, Offset) -> Rect?,
): Modifier {
    val controller = LocalLauncherDrag.current
    var bounds by remember { mutableStateOf(Rect.Zero) }

    DisposableEffect(key) {
        onDispose { controller.unregisterTarget(key) }
    }

    controller.registerTarget(
        key,
        DropTarget(bounds, priority, accepts, onDrop, onEnter, onExit),
    )

    return this.onGloballyPositioned { bounds = it.boundsInWindow() }
}
