package com.vayunmathur.clock.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleButton
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.localeWeekDayNumbers
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlarmCard(
    backStack: NavBackStack<Route>,
    alarm: Alarm,
    is24Hour: Boolean,
    actions: AlarmActions,
    initialExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    ResultEffect<LocalTime>("alarm_set_time_${alarm.id}") { actions.setTime(alarm, it) }
    Card(
        onClick = { backStack.add(Route.AlarmSetTimeDialog(alarm.id, alarm.time)) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    if (alarm.name.isNotEmpty()) {
                        Text(
                            alarm.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        formatAlarmTime(is24Hour, alarm.time),
                        style = MaterialTheme.typography.displayMedium,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = alarm.enabled, onCheckedChange = { actions.setEnabled(alarm, it) })
                    Spacer(Modifier.width(8.dp))
                    IconButton({ actions.delete(alarm) }) {
                        IconDelete()
                    }
                    IconButton({ expanded = !expanded }) {
                        IconChevronRight(modifier = Modifier.rotate(if (expanded) 90f else 0f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val dayNames = localizedDayOfWeekNames(DateNameStyle.NARROW)
                localeWeekDayNumbers().forEach { isoDay ->
                    val bit = isoDay % 7
                    val isSelected = alarm.days and (1 shl bit) != 0
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = {
                            val newDays = if (isSelected) alarm.days and (1 shl bit).inv() else alarm.days or (1 shl bit)
                            actions.setDays(alarm, newDays)
                        }
                    ) {
                        Text(dayNames[isoDay - 1])
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = alarm.name,
                    onValueChange = { actions.setName(alarm, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_label)) },
                    placeholder = { Text(stringResource(R.string.alarm_label_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                AlarmOptionControls(
                    ringtoneUri = alarm.ringtoneUri,
                    vibrate = alarm.vibrate,
                    snoozeMinutes = alarm.snoozeMinutes,
                    gradualVolumeSeconds = alarm.gradualVolumeSeconds,
                    onRingtoneClick = { actions.pickRingtone(alarm) },
                    onVibrateChange = { actions.setVibrate(alarm, it) },
                    onSnoozeChange = { actions.setSnoozeMinutes(alarm, it) },
                    onGradualChange = { actions.setGradualVolumeSeconds(alarm, it) },
                )
            }
        }
    }
}

fun formatAlarmTime(context: Context, time: LocalTime): String =
    formatAlarmTime(DateFormat.is24HourFormat(context), time)

fun formatAlarmTime(is24Hour: Boolean, time: LocalTime): String =
    DateString.time(time, is24Hour)
