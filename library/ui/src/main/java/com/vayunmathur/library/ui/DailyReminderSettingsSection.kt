package com.vayunmathur.library.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/**
 * The opt-in daily-puzzle reminder rows: a toggle, and the time it fires.
 *
 * Shared because every daily-puzzle game wants exactly these two rows and the same picker.
 * Knows nothing about scheduling — that is `:library:work`'s `DailyPuzzleReminder`, which this
 * module does not depend on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReminderSettingsSection(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (Int, Int) -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.settings_reminders)) {
        SettingsSwitchRow(
            title = stringResource(R.string.setting_daily_reminder),
            supportingText = stringResource(R.string.setting_daily_reminder_hint),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        SettingsRow(
            title = stringResource(R.string.setting_daily_reminder_time),
            supportingText = "%02d:%02d".format(hour, minute),
            enabled = enabled,
            onClick = { showTimePicker = true },
        )
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.setting_daily_reminder_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
