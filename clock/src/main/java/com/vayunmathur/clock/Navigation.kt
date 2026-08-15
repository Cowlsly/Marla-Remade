package com.vayunmathur.clock

import androidx.compose.runtime.Composable
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.AlarmSettingsPage
import com.vayunmathur.clock.ui.ClockTabs
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.SelectTimeZonesDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(
    ds: DataStoreUtils,
    clockViewModel: ClockViewModel,
    initialRoute: Route?,
) {
    val initialTab = when (initialRoute) {
        is Route.Alarm -> 0
        is Route.Clock -> 1
        is Route.Timer -> 2
        is Route.Stopwatch -> 3
        else -> 0
    }
    val dialogRoute = when (initialRoute) {
        is Route.Alarm, is Route.Clock, is Route.Timer, is Route.Stopwatch -> null
        else -> initialRoute
    }
    val backStack = rememberNavBackStack<Route>(listOfNotNull(Route.Main(initialTab), dialogRoute).distinct())
    MainNavigation(backStack) {
        entry<Route.Main> { key ->
            ClockTabs(backStack, ds, clockViewModel, key.initialTab)
        }
        entry<Route.AlarmSettings> {
            AlarmSettingsPage(backStack, ds)
        }
        entry<Route.SelectTimeZonesDialog>(metadata = DialogPage()) {
            SelectTimeZonesDialog(backStack, ds, clockViewModel)
        }
        entry<Route.NewTimerDialog>(metadata = DialogPage()) { key ->
            NewTimerDialog(backStack, clockViewModel, key.lengthSeconds, key.message)
        }
        entry<Route.NewAlarmDialog>(metadata = DialogPage()) { key ->
            NewAlarmDialog(
                backStack = backStack,
                clockViewModel = clockViewModel,
                initialHour = key.hour,
                initialMinutes = key.minutes,
                initialMessage = key.message,
                initialDays = key.days,
            )
        }
        entry<Route.AlarmSetTimeDialog>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, "alarm_set_time_${it.id}", it.time)
        }
    }
}
