package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.platform.DownloadState
import com.vayunmathur.euicc.ui.download.CompleteContent
import com.vayunmathur.euicc.ui.download.ConfirmCarrierContent
import com.vayunmathur.euicc.ui.download.ConfirmationCodeContent
import com.vayunmathur.euicc.ui.download.FailedContent
import com.vayunmathur.euicc.ui.download.InstallingContent
import com.vayunmathur.library.ui.SwappedContent
import com.vayunmathur.library.util.NavBackStack

/**
 * The download, from authentication to installed profile.
 *
 * Every phase lives on this one destination rather than on its own route, because they are
 * steps of a single eUICC session: going "back" from installing to the carrier
 * confirmation would leave the session running with nothing driving it. Back is suppressed
 * outright while the eUICC is being written to, which is the one point where interrupting
 * can leave a half-installed profile behind.
 */
@Composable
fun DownloadScreen(
    activationCode: String,
    state: DownloadState,
    backStack: NavBackStack<Route>,
    onStart: (String) -> Unit,
    onDone: () -> Unit,
) {
    LaunchedEffect(activationCode) {
        if (state is DownloadState.Idle) onStart(activationCode)
    }

    val busy = state is DownloadState.Preparing || state is DownloadState.Installing
    BackHandler(enabled = busy) {
        // Deliberately empty: swallow back while the eUICC is mid-write.
    }

    SwappedContent(state) { current ->
        when (current) {
            is DownloadState.Idle, is DownloadState.Preparing ->
                InstallingContent(progress = null)

            is DownloadState.Confirm ->
                ConfirmCarrierContent(
                    carrier = current.carrier,
                    profileName = current.profileName,
                    onConfirm = { onStart(activationCode) },
                    onCancel = onDone,
                )

            is DownloadState.AwaitingConfirmationCode ->
                ConfirmationCodeContent(
                    carrier = current.carrier,
                    error = current.error,
                    // The code is accepted by the UI but has nowhere to go until the native
                    // core can resume a paused download with it; retrying the whole
                    // activation code is the only thing that exists today.
                    onSubmit = { onStart(activationCode) },
                    onCancel = onDone,
                )

            is DownloadState.Installing -> InstallingContent(progress = current.progress)

            is DownloadState.Complete ->
                CompleteContent(
                    carrier = current.carrier,
                    onDone = onDone,
                )

            is DownloadState.Failed ->
                FailedContent(
                    message = current.message,
                    onRetry = { onStart(activationCode) },
                    onCancel = onDone,
                )
        }
    }
}
