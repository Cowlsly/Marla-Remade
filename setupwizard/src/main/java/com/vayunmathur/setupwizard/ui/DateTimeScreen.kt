package com.vayunmathur.setupwizard.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconToday
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.ZoneInfo
import com.vayunmathur.setupwizard.ui.dialogs.SetupDatePickerDialog
import com.vayunmathur.setupwizard.ui.dialogs.SetupTimePickerDialog
import com.vayunmathur.setupwizard.ui.dialogs.TimeZonePickerDialog

/**
 * Time zone, date and time - the three things the device cannot work out for itself before it
 * has a network, and which every certificate check afterwards depends on.
 */
@Composable
fun DateTimeScreen(
    state: SetupUiState,
    timeZones: () -> List<ZoneInfo>,
    onTimeZoneSelected: (String) -> Unit,
    onDateSelected: (year: Int, month: Int, dayOfMonth: Int) -> Unit,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    onClockTick: () -> Unit,
    onNext: () -> Unit,
) {
    var picker by remember { mutableStateOf(ClockPicker.None) }

    ClockTicker(onClockTick)

    SetupScaffold(
        title = stringResource(R.string.date_and_time),
        subtitle = stringResource(R.string.date_and_time_desc),
        icon = { IconToday() },
        scrollBehavior = appBarScrollBehavior(),
        primaryAction = SetupAction(stringResource(R.string.next), onNext),
    ) {
        ClockRow(
            label = stringResource(R.string.time_zone),
            value = state.timeZone,
            onClick = { picker = ClockPicker.Zone },
        )
        ClockRow(
            label = stringResource(R.string.date),
            value = state.date,
            onClick = { picker = ClockPicker.Date },
        )
        ClockRow(
            label = stringResource(R.string.time),
            value = state.time,
            onClick = { picker = ClockPicker.Time },
        )
    }

    when (picker) {
        ClockPicker.None -> Unit
        ClockPicker.Zone -> TimeZonePickerDialog(
            zones = timeZones,
            onSelected = { picker = ClockPicker.None; onTimeZoneSelected(it.id) },
            onDismiss = { picker = ClockPicker.None },
        )
        ClockPicker.Date -> SetupDatePickerDialog(
            onSelected = { y, m, d -> picker = ClockPicker.None; onDateSelected(y, m, d) },
            onDismiss = { picker = ClockPicker.None },
        )
        ClockPicker.Time -> SetupTimePickerDialog(
            onSelected = { h, m -> picker = ClockPicker.None; onTimeSelected(h, m) },
            onDismiss = { picker = ClockPicker.None },
        )
    }
}

private enum class ClockPicker { None, Zone, Date, Time }

/**
 * Keeps the displayed clock honest while the step is on screen.
 *
 * `ACTION_TIME_TICK` is the minute edge and cannot be received by a manifest receiver, which
 * is why it is registered here and torn down when the step leaves. The other two actions catch
 * the user's own edits landing.
 */
@Composable
private fun ClockTicker(onTick: () -> Unit) {
    val context = LocalContext.current
    val currentOnTick by rememberUpdatedState(onTick)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = currentOnTick()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

@Composable
private fun ClockRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
