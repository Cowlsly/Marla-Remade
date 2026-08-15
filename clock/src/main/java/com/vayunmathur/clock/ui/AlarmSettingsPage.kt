package com.vayunmathur.clock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.ui.component.AlarmOptionControls
import com.vayunmathur.clock.ui.component.ringtonePickerIntent
import com.vayunmathur.clock.ui.component.ringtonePickerResult
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsPage(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val scope = rememberCoroutineScope()

    var ringtone by remember { mutableStateOf(ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE)) }
    var vibrate by remember { mutableStateOf(ds.getBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, true)) }
    var snooze by remember { mutableStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_SNOOZE) ?: 5L).toInt()) }
    var gradual by remember { mutableStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_GRADUAL) ?: 0L).toInt()) }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = ringtonePickerResult(result.data)
            ringtone = picked
            scope.launch { ds.setString(ClockViewModel.KEY_DEFAULT_RINGTONE, picked) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.alarm_settings)) },
            navigationIcon = { IconButton(onClick = { backStack.pop() }) { IconBack() } },
        )
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.defaults_for_new_alarms),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            AlarmOptionControls(
                ringtoneUri = ringtone,
                vibrate = vibrate,
                snoozeMinutes = snooze,
                gradualVolumeSeconds = gradual,
                onRingtoneClick = { ringtoneLauncher.launch(ringtonePickerIntent(ringtone)) },
                onVibrateChange = {
                    vibrate = it
                    scope.launch { ds.setBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, it) }
                },
                onSnoozeChange = {
                    snooze = it
                    scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_SNOOZE, it.toLong()) }
                },
                onGradualChange = {
                    gradual = it
                    scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_GRADUAL, it.toLong()) }
                },
            )
        }
    }
}
