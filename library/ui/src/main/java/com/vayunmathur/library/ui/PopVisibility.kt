package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [content] by growing it out of its own centre, and scales it away again when [visible] goes
 * false.
 *
 * For a small thing that owns a fixed spot on the screen and comes and goes there - a floating action
 * button, a badge, a chip. It reads as the button leaving rather than being cut, which a plain fade
 * does not: a fade keeps the full-size shape occupying its space for the whole animation.
 *
 * Wrong for a block inside a list or a column, which needs the space it leaves to close up behind it:
 * use [ExpandVisibility] there.
 */
@Composable
fun PopVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.motionScheme
    val scale: FiniteAnimationSpec<Float> = scheme.defaultSpatialSpec()
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(scale) + fadeIn(fade),
        exit = scaleOut(scale) + fadeOut(fade),
    ) {
        content()
    }
}
