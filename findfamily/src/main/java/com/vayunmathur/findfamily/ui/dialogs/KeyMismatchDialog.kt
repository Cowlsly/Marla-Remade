package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.findfamily.R

/**
 * Shown when the key the relay returned for a peer doesn't match the fingerprint their
 * invite link carried. Either the link was copied incompletely, or the key isn't the one
 * the sender published — so the connection is refused rather than made with a key nothing
 * vouches for.
 */
@Composable
fun KeyMismatchDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_mismatch_title)) },
        text = { Text(stringResource(R.string.key_mismatch_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.done)) } }
    )
}
