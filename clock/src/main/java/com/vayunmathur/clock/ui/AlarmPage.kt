package com.vayunmathur.clock.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localeWeekDayNumbers
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.clock.util.AlarmScheduler
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.clock.util.AlarmActions
import com.vayunmathur.clock.util.AlarmUiState
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalTime
import com.vayunmathur.library.ui.DateString

/** Binds [ClockViewModel] to the stateless [AlarmScreen]. */
@Composable
fun AlarmPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel, newAlarmParams: Route.NewAlarmDialog? = null) {
    val alarms by clockViewModel.alarms.collectAsState()
    val context = LocalContext.current
    val alarmScheduler = AlarmScheduler

    // One ringtone picker for the whole page; tracks which alarm is choosing.
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

    // Editing an alarm always means "persist it, then re-arm (or cancel) its schedule",
    // which needs a Context — and the ringtone picker needs the Activity launcher above —
    // so the adapter lives here rather than on the ViewModel.
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

/**
 * The alarm list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    backStack: NavBackStack<Route>,
    state: AlarmUiState,
    actions: AlarmActions,
    /**
     * Seed for a card's own UI-only state: the alarm whose options drawer starts open. The
     * app always takes the default; a preview sets it to capture the expanded card.
     */
    initialExpandedAlarmId: Long? = null,
) {
    val alarms = state.alarms
    Scaffold(topBar = {
        TopAppBar(
            actions = {
                IconButton(onClick = { backStack.add(Route.AlarmSettings) }) { IconSettings() }
            },
        )
    }, floatingActionButton = {
        if (alarms.isNotEmpty()) {
            FloatingActionButton({
                backStack.add(Route.NewAlarmDialog())
            }) {
                IconAdd()
            }
        }
    }) { paddingValues ->
        if (alarms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Button(onClick = { backStack.add(Route.NewAlarmDialog()) }) {
                    IconAdd()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.set_an_alarm))
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        backStack = backStack,
                        alarm = alarm,
                        is24Hour = state.is24Hour,
                        actions = actions,
                        initialExpanded = alarm.id == initialExpandedAlarmId,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlarmCard(
    backStack: NavBackStack<Route>,
    alarm: Alarm,
    is24Hour: Boolean,
    actions: AlarmActions,
    initialExpanded: Boolean = false,
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(initialExpanded) }
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
                    // Alarm.days is a bitmask with Sunday at bit 0 .. Saturday at bit 6.
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

/**
 * Format [time] for display, honoring the device's 12h/24h setting.
 * Shared by the alarm list and the full-screen ringing activity.
 */
fun formatAlarmTime(context: Context, time: LocalTime): String =
    formatAlarmTime(DateFormat.is24HourFormat(context), time)

/**
 * As above, but with the 12h/24h choice already resolved — the alarm list reads it once
 * into [AlarmUiState] so the cards themselves need no [Context], and so a preview renders
 * the same clock format on any machine.
 */
fun formatAlarmTime(is24Hour: Boolean, time: LocalTime): String =
    DateString.time(time, is24Hour)
