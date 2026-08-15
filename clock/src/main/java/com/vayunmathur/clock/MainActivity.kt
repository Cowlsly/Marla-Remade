package com.vayunmathur.clock

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconAccessTime
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.ui.AlarmPage
import com.vayunmathur.clock.ui.ClockPage
import com.vayunmathur.clock.ui.StopwatchPage
import com.vayunmathur.clock.ui.TimerPage
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.SelectTimeZonesDialog
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.ClockViewModelFactory
import com.vayunmathur.clock.util.createNotificationChannels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    private val clockViewModel: ClockViewModel by viewModels {
        ClockViewModelFactory(application, ClockRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            // Redirect user to system settings to allow exact alarms
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
        createNotificationChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                // Direct the user to the settings page to toggle "Allow full screen intents"
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = "package:${packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }
        val ds = DataStoreUtils.getInstance(this)

        val initialRoute = clockViewModel.handleIncomingIntent(intent)

        setContent {
            DynamicTheme {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
                var hasPermissions by remember {
                    mutableStateOf(
                        permissions.all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
                if (!hasPermissions && permissions.isNotEmpty()) {
                    InitialPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    Navigation(ds, clockViewModel, initialRoute)
                }
            }
        }
    }
}

@Composable
fun InitialPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                {
                    permissionRequestor.launch(permissions)
                }
            ) {
                Text(text = stringResource(R.string.grant_notifications_permission))
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Main(val initialTab: Int = 0) : Route
    @Serializable
    data object Alarm: Route
    @Serializable
    data object Clock: Route
    @Serializable
    data object Timer: Route
    @Serializable
    data object Stopwatch: Route
    @Serializable
    data object AlarmSettings: Route
    @Serializable
    data object SelectTimeZonesDialog: Route
    @Serializable
    data class NewTimerDialog(val lengthSeconds: Int? = null, val message: String? = null): Route
    @Serializable
    data class NewAlarmDialog(
        val hour: Int? = null,
        val minutes: Int? = null,
        val message: String? = null,
        val days: ArrayList<Int>? = null,
        val skipUi: Boolean = false
    ): Route
    @Serializable
    data class AlarmSetTimeDialog(val id: Long, val time: LocalTime): Route
}

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
            com.vayunmathur.clock.ui.AlarmSettingsPage(backStack, ds)
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

@Composable
private fun ClockTabs(
    backStack: com.vayunmathur.library.util.NavBackStack<Route>,
    ds: DataStoreUtils,
    clockViewModel: ClockViewModel,
    initialTab: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 4 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.label_alarm), { IconAlarm() }) {
            AlarmPage(backStack, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_clock), { IconAccessTime() }) {
            ClockPage(backStack, ds, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_timer), { IconHourglass() }) {
            TimerPage(backStack, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_stopwatch), { IconTimer() }) {
            StopwatchPage(backStack, clockViewModel)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
