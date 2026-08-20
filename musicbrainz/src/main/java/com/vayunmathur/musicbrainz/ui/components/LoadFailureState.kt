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
 * The terminal state for a page that has nothing to show.
 *
 * A catalogue that has not finished importing is drawn apart from a failure because it is a
 * wait, not a fault: reporting it as an error sends the user hunting for a problem at their
 * end, and there is no fallback source to fall back to. Either way this is a settled state
 * rather than a spinner, so the screen never sits there implying progress it is not making.
 *
 * [notReadyReason] is the server explaining why a catalogue is missing, shown in place of the
 * default copy. [notReadyRetryable] is a SEPARATE question the server also answers: whether
 * asking again could ever help. The two do not move together - a build that failed and is queued
 * for the next check has a reason AND is worth retrying, while a host that cannot fit a build
 * has a reason and never will be. So the button follows [notReadyRetryable] alone, and offering
 * a retry that cannot work is the thing being avoided.
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
        EmptyState(
            title = stringResource(R.string.catalogue_not_ready),
            modifier = modifier,
            message = notReadyReason ?: stringResource(R.string.catalogue_not_ready_message),
            action = if (notReadyRetryable && onRetry != null && retryLabel != null) {
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
