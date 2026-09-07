package com.vayunmathur.euicc.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * The download failed.
 *
 * [message] is whatever the SM-DP+ or the native core said, shown in preference to the
 * generic line because it is usually the only clue about which of the many things in an
 * SGP.22 download went wrong - an expired activation code and a DNS failure are both just
 * "couldn't set up eSIM" otherwise.
 */
@Composable
fun FailedContent(message: String?, onRetry: () -> Unit, onCancel: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.download_fail_dialog_title),
        subtitle = message ?: stringResource(R.string.download_fail_generic),
        icon = { IconWarning() },
        primaryAction = SetupAction(stringResource(R.string.try_again), onRetry),
        secondaryAction = SetupAction(stringResource(R.string.cancel), onCancel),
        scrollBehavior = appBarScrollBehavior(),
    )
}
