package com.vayunmathur.logviewer.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogLevel

/**
 * Picks the minimum severity to show.
 *
 * Choosing applies immediately - there is no Apply button, matching the single-choice list this
 * replaces. Applying means re-running `logcat` with a new `*:X` argument, so it is a new screen
 * rather than a filter over what is already loaded.
 *
 * The labels are translated, but the value they select is not: see [LogLevel].
 */
@Composable
internal fun LogLevelDialog(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_level)) },
        text = {
            Column {
                for (level in LogLevel.entries) {
                    ListItem(
                        content = { Text(stringResource(level.labelRes)) },
                        leadingContent = {
                            RadioButton(selected = level == current, onClick = { onSelect(level) })
                        },
                        modifier = Modifier.clickable { onSelect(level) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val LogLevel.labelRes: Int
    get() = when (this) {
        LogLevel.Verbose -> R.string.log_level_verbose
        LogLevel.Debug -> R.string.log_level_debug
        LogLevel.Info -> R.string.log_level_info
        LogLevel.Warn -> R.string.log_level_warn
        LogLevel.Error -> R.string.log_level_error
        LogLevel.Assert -> R.string.log_level_assert
    }
