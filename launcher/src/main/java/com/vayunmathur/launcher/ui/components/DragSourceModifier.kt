package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registers this composable as something the root gesture owner can pick up.
 *
 * The single [launcherDragInput] hit-tests against these rather than each item installing its
 * own long-press detector — which is what would put a second `pointerInput` in the hierarchy and
 * bring back the pager-versus-drag conflict.
 *
 * [payload] is a lambda rather than a value so it is read at the moment the drag starts, giving
 * the item's placement as it is then rather than as it was when the modifier was composed.
 *
 * Set [enabled] to false to make the composable un-draggable while staying composed. A widget
 * showing its resize frame does this: its handles need the gesture, so the root owner has to find
 * nothing here and stand aside.
 *
 * Bounds are translated by [LocalPopupWindowOffset], so a row inside a popup or a sheet registers
 * where it really is on screen rather than where it is within its own window.
 */
@Composable
fun Modifier.dragSource(
    key: Any,
    enabled: Boolean = true,
    payload: () -> DragPayload,
): Modifier {
    val controller = LocalLauncherDrag.current
    val windowOffset = LocalPopupWindowOffset.current

    DisposableEffect(key) {
        onDispose { controller.unregisterSource(key) }
    }

    if (!enabled) {
        controller.unregisterSource(key)
        return this
    }

    return this.onGloballyPositioned { coordinates ->
        controller.registerSource(key, coordinates.boundsInWindow().translate(windowOffset), payload)
    }
}
