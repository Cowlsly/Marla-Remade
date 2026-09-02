package com.vayunmathur.things.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.Sex

/**
 * The landing screen once a device is connected: non-health device telemetry only.
 *
 * Health data (hydration volume, weight, body composition) is written to Health Connect by
 * [com.vayunmathur.things.MainActivity] and read by the Health app; this app deliberately does not
 * display any of it. What remains here is device/water telemetry (battery, charging, temperature,
 * purity) and the scale profile inputs, which are *inputs* to the body-composition calc rather than
 * health readouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(
    bottleConnected: Boolean,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    volumePct: Int?,
    lastUpdatedMillis: Long?,
    scaleConnected: Boolean,
    scaleConnectionState: String,
    scaleSex: Sex,
    scaleAge: String,
    scaleHeight: String,
    scaleAthlete: Boolean,
    onScaleSexChange: (Sex) -> Unit,
    onScaleAgeChange: (String) -> Unit,
    onScaleHeightChange: (String) -> Unit,
    onScaleAthleteChange: (Boolean) -> Unit,
    onOpenDevices: () -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = appBarScrollBehavior(),
        actions = {
            IconButton(onClick = onOpenDevices) { IconWidgets() }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (bottleConnected) {
                item {
                    BottleStatusCard(
                        tempC = tempC,
                        tds = tds,
                        batteryPct = batteryPct,
                        charging = charging,
                        volumePct = volumePct,
                        lastUpdatedMillis = lastUpdatedMillis,
                    )
                }
            }

            if (scaleConnected) {
                item {
                    ScaleTelemetryCard(
                        connectionState = scaleConnectionState,
                        sex = scaleSex,
                        age = scaleAge,
                        height = scaleHeight,
                        athlete = scaleAthlete,
                        onSexChange = onScaleSexChange,
                        onAgeChange = onScaleAgeChange,
                        onHeightChange = onScaleHeightChange,
                        onAthleteChange = onScaleAthleteChange,
                    )
                }
            }

            if (!bottleConnected && !scaleConnected) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.no_devices),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(onClick = onOpenDevices) { Text(stringResource(R.string.open_devices)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottleStatusCard(
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    volumePct: Int?,
    lastUpdatedMillis: Long?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.bottle_status),
                style = MaterialTheme.typography.titleSmall
            )
            val hasData = tempC != null || tds != null || batteryPct != null || volumePct != null
            if (!hasData) {
                Text(
                    stringResource(R.string.waiting_for_data),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                if (batteryPct != null) {
                    val battery = if (charging) {
                        stringResource(R.string.battery_charging, batteryPct)
                    } else {
                        stringResource(R.string.battery_pct, batteryPct)
                    }
                    StatusRow(stringResource(R.string.label_battery), battery)
                }
                if (volumePct != null) {
                    StatusRow(stringResource(R.string.label_water_level), stringResource(R.string.percent, volumePct))
                }
                if (tempC != null) {
                    StatusRow(stringResource(R.string.label_temperature), stringResource(R.string.temperature_c, tempC))
                }
                if (tds != null) {
                    StatusRow(stringResource(R.string.label_purity), stringResource(R.string.purity_ppm, tds))
                }
            }
            if (lastUpdatedMillis != null) {
                val time = remember(lastUpdatedMillis) { formatClockTime(lastUpdatedMillis) }
                Text(
                    stringResource(R.string.last_updated, time),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatClockTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable
private fun ScaleTelemetryCard(
    connectionState: String,
    sex: Sex,
    age: String,
    height: String,
    athlete: Boolean,
    onSexChange: (Sex) -> Unit,
    onAgeChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onAthleteChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scale_title), style = MaterialTheme.typography.titleSmall)
            Text(connectionState, style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.scale_profile), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == Sex.Male, onClick = { onSexChange(Sex.Male) }, label = { Text(stringResource(R.string.sex_male)) })
                FilterChip(selected = sex == Sex.Female, onClick = { onSexChange(Sex.Female) }, label = { Text(stringResource(R.string.sex_female)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = age,
                    onValueChange = onAgeChange,
                    label = { Text(stringResource(R.string.age)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = { Text(stringResource(R.string.height_cm)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = athlete, onCheckedChange = onAthleteChange)
                Text(stringResource(R.string.athlete), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.syncs_to_health_connect),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
