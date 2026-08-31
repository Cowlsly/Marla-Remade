package com.vayunmathur.library.util

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * When the field's own text comes back, measured from the *start* of the morph.
 *
 * [isNavMorphing] stays true until every part of the transition has settled, including the long tail
 * of the spring on the screen change, so revealing when it clears puts the text back well after the
 * travelling copy has arrived. Timing it from the start instead lands the reveal just before the
 * bounds travel finishes, which is where it belongs - hence a fraction of [NavMorphMillis] rather
 * than a literal that has to be kept in step with it by hand.
 */
private const val RevealDelayMillis = NavMorphMillis * 2L / 3L

/**
 * Wraps the inner text of an editable field so the read-only text carrying the same [key] on the
 * previous screen morphs into it.
 *
 * Call this from a text field's `decorationBox` around its `innerTextField`, and pass the [style]
 * the field draws its text in.
 *
 * A field cannot simply carry [sharedText] itself. Its inner text fills the field's whole width -
 * it has to, or tapping to the right of the value could not place the cursor - so a content-sized
 * text on the other screen pairs against that full width, balloons out to it and then snaps back to
 * its real size on arrival. This keeps the real editable line full-width and *unkeyed*, and puts the
 * key on a transparent copy of [value], which measures to the glyphs and so gives the arriving text
 * a destination its own size.
 *
 * The real text is hidden with [alpha] rather than by not composing it: the field keeps its focus,
 * its cursor and its measured size throughout, and nothing about the app's state changes for the
 * sake of an animation. There is no fade on the reveal either - a fade reads as the letters
 * resolving out of a blur, and the point is that the letters that just travelled here *are* these
 * letters.
 *
 * A null [key] is the non-morphing case and simply emits [innerTextField] unchanged, so a field
 * shared by screens that do and do not morph needs no branch of its own.
 */
@Composable
fun SharedEditableText(
    key: Any?,
    value: String,
    style: TextStyle = LocalTextStyle.current,
    innerTextField: @Composable () -> Unit,
) {
    if (key == null) {
        innerTextField()
        return
    }
    Box {
        val morphing = isNavMorphing()
        var revealed by remember(morphing) { mutableStateOf(!morphing) }
        LaunchedEffect(morphing) {
            if (morphing) {
                delay(RevealDelayMillis)
                revealed = true
            }
        }
        Box(Modifier.alpha(if (revealed) 1f else 0f)) { innerTextField() }
        Text(
            text = value,
            style = style,
            color = Color.Transparent,
            maxLines = 1,
            modifier = Modifier.sharedText(key),
        )
    }
}
