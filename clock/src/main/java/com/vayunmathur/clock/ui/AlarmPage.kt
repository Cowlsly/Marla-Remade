package com.vayunmathur.clock.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.ui.components.ringtonePickerIntent
import com.vayunmathur.clock.ui.components.ringtonePickerResult
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmScheduler
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalTime

/** Binds [ClockViewModel] to the stateless [AlarmScreen]. */
@Composable
fun AlarmPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel, newAlarmParams: Route.NewAlarmDialog? = null) {
    val alarms by clockViewModel.alarms.collectAsState()
    val context = LocalContext.current
    val alarmScheduler = AlarmScheduler

    var pickingAlarmId by remember { mutableStateOf<Long?>(null) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = ringtonePickerResult(result.data)
            clockViewModel.alarms.value.firstOrNull { it.id == pickingAlarmId }?.let { target ->
                clockViewModel.upsert(target.copy(ringtoneUri = picked))
            }
        }
        pickingAlarmId = null
    }

    val actions = remember(clockViewModel, context) {
        object : AlarmActions {
            private fun save(alarm: Alarm) {
                if (alarm.enabled) alarmScheduler.schedule(context, alarm)
                clockViewModel.upsert(alarm)
            }

            override fun setName(alarm: Alarm, name: String) = save(alarm.copy(name = name))
            override fun setTime(alarm: Alarm, time: LocalTime) = save(alarm.copy(time = time))
            override fun setDays(alarm: Alarm, days: Int) = save(alarm.copy(days = days))

            override fun setEnabled(alarm: Alarm, enabled: Boolean) {
                val updated = alarm.copy(enabled = enabled)
                if (enabled) alarmScheduler.schedule(context, updated) else alarmScheduler.cancel(context, updated)
                clockViewModel.upsert(updated)
            }

            override fun delete(alarm: Alarm) {
                alarmScheduler.cancel(context, alarm)
                clockViewModel.delete(alarm)
            }

            override fun pickRingtone(alarm: Alarm) {
                pickingAlarmId = alarm.id
                ringtoneLauncher.launch(ringtonePickerIntent(alarm.ringtoneUri))
            }

            override fun setVibrate(alarm: Alarm, vibrate: Boolean) {
                clockViewModel.upsert(alarm.copy(vibrate = vibrate))
            }

            override fun setSnoozeMinutes(alarm: Alarm, minutes: Int) {
                clockViewModel.upsert(alarm.copy(snoozeMinutes = minutes))
            }

            override fun setGradualVolumeSeconds(alarm: Alarm, seconds: Int) {
                clockViewModel.upsert(alarm.copy(gradualVolumeSeconds = seconds))
            }
        }
    }

    AlarmScreen(
        backStack = backStack,
        state = AlarmUiState(alarms = alarms, is24Hour = DateFormat.is24HourFormat(context)),
        actions = actions,
    )
}
