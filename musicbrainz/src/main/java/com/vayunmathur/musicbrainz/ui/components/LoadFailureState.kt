package com.vayunmathur.musicbrainz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.musicbrainz.R

/**
 * The copy a not-ready screen uses, chosen solely by whether waiting could change the answer.
 *
 * Pulled out of the composable so the rule that matters can be asserted rather than reviewed:
 * a state that waiting cannot fix must never say "try again" and must never offer a button.
 */
internal data class NotReadyCopy(
    val title: Int,
    val fallbackMessage: Int,
    val showRetry: Boolean,
)

internal fun notReadyCopy(retryable: Boolean): NotReadyCopy = if (retryable) {
    NotReadyCopy(
        title = R.string.catalogue_not_ready,
        fallbackMessage = R.string.catalogue_not_ready_message,
        showRetry = true,
    )
} else {
    NotReadyCopy(
        title = R.string.catalogue_unavailable,
        fallbackMessage = R.string.catalogue_unavailable_message,
        showRetry = false,
    )
}

/**
 * The terminal state for a page that has nothing to show.
 *
 * A catalogue that has not finished importing is drawn apart from a failure because it is a
 * wait, not a fault: reporting it as an error sends the user hunting for a problem at their
 * end, and there is no fallback source to fall back to. Either way this is a settled state
 * rather than a spinner, so the screen never sits there implying progress it is not making.
 *
 * [notReadyRetryable] is the server answering one question - would waiting change the answer?
 * It is the ONLY thing the copy branches on, deliberately, because the state cannot answer it:
 * a catalogue can be absent and on its way, or absent and never coming.
 *
 * That honesty is structural rather than a consequence of the server always sending a
 * [notReadyReason]: each branch carries its own copy, so a missing reason degrades to a vaguer
 * TRUE message instead of a confident false one. The reason itself is displayed verbatim and
 * never parsed.
 */
@Composable
fun LoadFailureState(
    error: String?,
    notReady: Boolean,
    modifier: Modifier = Modifier,
    notReadyReason: String? = null,
    notReadyRetryable: Boolean = true,
    title: String = stringResource(R.string.load_failed),
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    if (notReady) {
        val copy = notReadyCopy(notReadyRetryable)
        val reason = notReadyReason?.takeIf { it.isNotBlank() }
        // When waiting helps, the server's words replace the copy. When it does not, they are
        // shown ahead of it: the reason names the shortfall, the copy says where it has to be
        // dealt with, and the user cannot infer the second from the first.
        val message = when {
            reason == null -> stringResource(copy.fallbackMessage)
            copy.showRetry -> reason
            else -> reason + "\n\n" + stringResource(copy.fallbackMessage)
        }
        EmptyState(
            title = stringResource(copy.title),
            modifier = modifier,
            message = message,
            action = if (copy.showRetry && onRetry != null && retryLabel != null) {
                { Button(onClick = onRetry) { Text(retryLabel) } }
            } else {
                null
            },
        )
    } else {
        ErrorState(
            title = title,
            modifier = modifier,
            message = error,
            retryLabel = retryLabel,
            onRetry = onRetry,
        )
    }
}
