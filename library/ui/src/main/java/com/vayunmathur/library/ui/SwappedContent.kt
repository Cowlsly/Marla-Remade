package com.vayunmathur.library.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dissolves one content into another in the same spot as [state] changes.
 *
 * For a surface whose *contents* are replaced while the surface itself stays put and stays the same
 * size - a panel that becomes a delete target mid-drag, artwork that becomes lyrics. Nothing moves,
 * because nothing has moved: the box is where it was and only what is inside it is different.
 *
 * Wrong for a bar that a mode takes over, which wants direction to say where the new bar came from -
 * use [SwappedTopBar]. Wrong for content that comes and goes rather than being replaced, which wants
 * one of the `*Visibility` helpers.
 */
@Composable
fun <T> SwappedContent(
    state: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val fade: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    Crossfade(targetState = state, modifier = modifier, animationSpec = fade) { current ->
        content(current)
    }
}
