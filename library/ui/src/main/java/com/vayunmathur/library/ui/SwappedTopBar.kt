package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * How far the two bars travel as they swap, as a fraction of the bar's height. A third: enough to
 * read as a push, short enough that neither bar is ever mostly off its own strip.
 */
private const val SwapTravel = 3

/**
 * Swaps a top bar for another one that slides down over it, and lets the first slide back down into
 * place when it is dismissed.
 *
 * For a bar that is taken over by a mode rather than replaced by a different screen - a selection bar
 * over a search field, an in-place search bar over a title. [content] is given the current state, so
 * it emits one bar or the other exactly as an `if` would.
 *
 * The direction is the whole point and is why this is not a crossfade: the overlay comes *from above*
 * and leaves back upwards, so the two bars read as one surface being pushed in and out of the strip
 * rather than as two unrelated bars dissolving into each other.
 */
@Composable
fun SwappedTopBar(
    showingOverlay: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.motionScheme
    val offset: FiniteAnimationSpec<IntOffset> = scheme.defaultSpatialSpec()
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    AnimatedContent(
        targetState = showingOverlay,
        modifier = modifier,
        transitionSpec = {
            val towards = if (targetState) -1 else 1
            (fadeIn(fade) + slideInVertically(offset) { towards * it / SwapTravel })
                .togetherWith(fadeOut(fade) + slideOutVertically(offset) { -towards * it / SwapTravel })
        },
    ) { overlay ->
        content(overlay)
    }
}
