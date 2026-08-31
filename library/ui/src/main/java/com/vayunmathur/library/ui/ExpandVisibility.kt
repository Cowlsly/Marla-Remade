package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/**
 * Opens [content] out of a flat line into its full height, and closes the space up again when
 * [visible] goes false.
 *
 * For a block that belongs to a column and pushes what is below it: a section attached under the
 * header that expanded it, a form's advanced options. The point is that the space appears and
 * disappears with the content - fading a block in and out leaves a gap the size of it behind, so the
 * column looks broken rather than collapsed.
 *
 * Content is measured at full size throughout and clipped, so text does not re-wrap on every frame.
 */
@Composable
fun ExpandVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.motionScheme
    val size: FiniteAnimationSpec<IntSize> = scheme.defaultSpatialSpec()
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(size) + fadeIn(fade),
        exit = shrinkVertically(size) + fadeOut(fade),
    ) {
        content()
    }
}
