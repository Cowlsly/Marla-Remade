package com.vayunmathur.setupwizard.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDatePickerState
import com.vayunmathur.setupwizard.R
import java.util.Calendar
import java.util.TimeZone

/**
 * Sets the calendar date, leaving the time of day alone.
 *
 * The picker works in UTC millis while [onSelected] hands back a plain year/month/day, because
 * the caller writes those three fields into the existing local time rather than replacing the
 * instant - setting the date should not also move the clock.
 */
@Composable
fun SetupDatePickerDialog(
    onSelected: (year: Int, month: Int, dayOfMonth: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(System.currentTimeMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        // The picker reports midnight UTC for the day the user tapped, so the
                        // fields have to be read back in UTC or a device west of Greenwich
                        // resolves them to the previous day.
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        utc.timeInMillis = millis
                        onSelected(
                            utc.get(Calendar.YEAR),
                            utc.get(Calendar.MONTH),
                            utc.get(Calendar.DAY_OF_MONTH),
                        )
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(UiR.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state)
    }
}
