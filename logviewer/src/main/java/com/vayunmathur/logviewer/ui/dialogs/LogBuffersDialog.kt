package com.vayunmathur.logviewer.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AddToListDialog
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.platform.LogcatReader

/**
 * Picks which of the kernel and framework ring buffers to read.
 *
 * The shared [AddToListDialog] is exactly this shape - a checkbox list staged locally and applied
 * on OK - so it is reused rather than rebuilt, with the "create new" half left out.
 *
 * An empty selection dismisses without applying: `logcat --buffer=` with nothing after it is an
 * error, and silently doing nothing is what the dialog this replaces did.
 */
@Composable
internal fun LogBuffersDialog(
    current: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AddToListDialog(
        title = stringResource(R.string.log_buffers),
        options = LogcatReader.ALL_BUFFERS,
        itemLabel = { it },
        confirmLabel = stringResource(R.string.action_apply),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { selected ->
            onDismiss()
            // Re-filtered rather than used as-is: the dialog hands back a Set, and the buffer order
            // shows up in both the `buffers:` header line and the title's "MSCEK" suffix.
            if (selected.isNotEmpty()) onApply(LogcatReader.ALL_BUFFERS.filter { it in selected })
        },
        onDismiss = onDismiss,
        initiallyChecked = { it in current },
    )
}
