package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * Drops [content] in over the top edge of the screen and takes it back out the same way.
 *
 * For an unsolicited announcement laid over a screen the user is already using - an achievement, an
 * unlock. The direction carries the meaning: it arrives from off-screen because it came from outside
 * what the user was doing, which a fade in place does not say.
 *
 * Only correct for something anchored at the top and drawn *over* the layout. A banner that belongs
 * in a column wants [ExpandVisibility], and a control that owns a fixed spot wants [PopVisibility].
 */
@Composable
fun BannerVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.motionScheme
    val offset: FiniteAnimationSpec<IntOffset> = scheme.defaultSpatialSpec()
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(offset) { -it } + fadeIn(fade),
        exit = slideOutVertically(offset) { -it } + fadeOut(fade),
    ) {
        content()
    }
}
