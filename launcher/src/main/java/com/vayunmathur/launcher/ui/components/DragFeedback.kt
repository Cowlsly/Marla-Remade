package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.rememberHaptics

/**
 * Haptic feedback for a drag in flight: one tick each time the finger crosses onto a new target.
 *
 * Its own composable, and not a line inside [DragLayer] or [HomeContent], so that reading the
 * controller confines recomposition to this node. Read at the top of the home screen it would
 * recompose the whole workspace every time the finger crossed a target.
 *
 * Keyed on [LauncherDragController.activeTargetKey] rather than on the target object, which is the
 * whole reason that key exists: targets re-register on every recomposition, a page recomposes on
 * every frame of a drag, and keying on the object therefore ticks once per frame — a continuous
 * buzz for the length of the drag rather than a tick per boundary crossed.
 */
@Composable
fun DragFeedback(controller: LauncherDragController) {
    val haptics = rememberHaptics()
    val target = controller.activeTargetKey
    var previous by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(target) {
        // Nothing for the *first* target of a drag: the long press that armed it has already
        // buzzed, and two haptics in the same instant read as one long rattle rather than as two
        // events.
        if (target != null && previous != null) haptics.tick()
        previous = target
    }
}
