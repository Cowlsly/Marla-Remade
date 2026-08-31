package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Fades [content] in and out without moving or resizing it.
 *
 * For something drawn *over* other content, where nothing has to make room for it and any movement
 * would be movement of the thing underneath: player controls over a video, an overlay on a photo, a
 * result banner over a quiz.
 *
 * Deliberately the plainest of the three. Prefer [PopVisibility] for a control that owns its spot and
 * [ExpandVisibility] for a block in a column - a fade leaves the space it occupied behind it, which
 * is only correct when the content was floating above the layout in the first place.
 */
@Composable
fun FadeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fade: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(fade),
        exit = fadeOut(fade),
    ) {
        content()
    }
}
