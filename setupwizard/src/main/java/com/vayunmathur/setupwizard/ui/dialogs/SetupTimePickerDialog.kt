package com.vayunmathur.setupwizard.ui.dialogs

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TimePicker
import com.vayunmathur.library.ui.rememberTimePickerState
import com.vayunmathur.setupwizard.R
import java.util.Calendar

/** Sets the time of day, leaving the date alone. Seconds are zeroed by the caller. */
@Composable
fun SetupTimePickerDialog(
    onSelected: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val now = remember { Calendar.getInstance() }
    val state = rememberTimePickerState(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = now.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time)) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onSelected(state.hour, state.minute) }) {
                Text(stringResource(UiR.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
