package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier

fun Modifier.invisibleClickable(onClick: () -> Unit) = clickable(null, null) {onClick()}

/**
 * Adds a double-tap handler to this modifier.
 * Usage: `Modifier.onDoubleTap { /* duplicate item in viewmodel/state */ }`
 */
fun Modifier.onDoubleTap(onDoubleTap: () -> Unit): Modifier =
	this.pointerInput(onDoubleTap) {
		detectTapGestures(onDoubleTap = { onDoubleTap() })
	}

/**
 * Dims everything behind this composable, and touches nothing else.
 *
 * A draw-only scrim, unlike the one a `Dialog` or a `ModalBottomSheet` brings with it: those also
 * install a window, a focus owner and a dismiss-on-outside-touch handler. Somewhere like a home
 * screen, where one gesture handler owns the whole hierarchy, that extra machinery is exactly
 * what must not appear — so the dim is drawn here and dismissal stays with whoever owns the
 * gesture.
 *
 * [alpha] is a lambda so a scrim that fades with a drag redraws without recomposing anything.
 */
@Composable
fun Modifier.scrim(alpha: () -> Float): Modifier {
	val color = MaterialTheme.colorScheme.scrim
	return this.drawBehind { drawRect(color, alpha = alpha().coerceIn(0f, 1f)) }
}
