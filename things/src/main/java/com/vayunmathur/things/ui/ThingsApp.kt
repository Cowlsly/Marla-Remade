package com.vayunmathur.things.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.BodyMetrics
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.Sex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThingsApp(
    totalMl: Int,
    goalMl: Int,
    messages: List<String>,
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    scaleWeight: Double?,
    scaleRealtimeWeight: Double?,
    scaleR50: Int?,
    scaleConnectionState: String,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    scaleMetrics: BodyMetrics?,
    scaleSex: Sex,
    scaleAge: String,
    scaleHeight: String,
    scaleAthlete: Boolean,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onScaleDisconnectClick: () -> Unit,
    onScaleSexChange: (Sex) -> Unit,
    onScaleAgeChange: (String) -> Unit,
    onScaleHeightChange: (String) -> Unit,
    onScaleAthleteChange: (Boolean) -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.hydration),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HydrationCard(totalMl = totalMl, goalMl = goalMl) }

            if (connectionState == "Connected" && (tempC != null || tds != null || batteryPct != null)) {
                item { BottleStatusCard(tempC = tempC, tds = tds, batteryPct = batteryPct, charging = charging) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(connectionState, style = MaterialTheme.typography.titleMedium)
                    if (connectionState == "Connected") {
                        OutlinedButton(onClick = onDisconnectClick) { Text(stringResource(R.string.disconnect)) }
                    } else {
                        Button(onClick = onScanClick, enabled = !scanning) { Text(stringResource(R.string.scan)) }
                    }
                }
            }

            if (discoveredDevices.isNotEmpty() && connectionState != "Connected") {
                item { Text(stringResource(R.string.devices_found), style = MaterialTheme.typography.labelLarge) }
                items(discoveredDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceClick(device) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (messages.isNotEmpty()) {
                item { HorizontalDivider() }
                item { Text(stringResource(R.string.today_s_drinks), style = MaterialTheme.typography.titleSmall) }
                items(messages) { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            item { HorizontalDivider() }
            item { ScaleSection(
                weight = scaleWeight,
                realtimeWeight = scaleRealtimeWeight,
                r50 = scaleR50,
                connectionState = scaleConnectionState,
                scanning = scaleScanning,
                devices = scaleDevices,
                metrics = scaleMetrics,
                sex = scaleSex,
                age = scaleAge,
                height = scaleHeight,
                athlete = scaleAthlete,
                onScanClick = onScaleScanClick,
                onDeviceClick = onScaleDeviceClick,
                onDisconnectClick = onScaleDisconnectClick,
                onSexChange = onScaleSexChange,
                onAgeChange = onScaleAgeChange,
                onHeightChange = onScaleHeightChange,
                onAthleteChange = onScaleAthleteChange,
            ) }
        }
    }
}

@Composable
private fun ScaleSection(
    weight: Double?,
    realtimeWeight: Double?,
    r50: Int?,
    connectionState: String,
    scanning: Boolean,
    devices: List<ScaleBleManager.ScaleBleDevice>,
    metrics: BodyMetrics?,
    sex: Sex,
    age: String,
    height: String,
    athlete: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    onSexChange: (Sex) -> Unit,
    onAgeChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onAthleteChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scale_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.scale_subtitle), style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }

        if (realtimeWeight != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.weighing), style = MaterialTheme.typography.titleSmall)
                    Text("%.1f kg".format(realtimeWeight), style = MaterialTheme.typography.displaySmall)
                }
            }
        } else if (weight != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.weight), style = MaterialTheme.typography.titleSmall)
                    Text("%.1f kg".format(weight), style = MaterialTheme.typography.displaySmall)
                    if (r50 != null) {
                        Text(stringResource(R.string.impedance, r50), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.no_impedance), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (metrics != null) {
            BodyMetricsCard(metrics = metrics)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(connectionState, style = MaterialTheme.typography.titleSmall)
            if (connectionState.startsWith("Scale: ") || connectionState.contains("step on")) {
                OutlinedButton(onClick = onDisconnectClick) { Text(stringResource(R.string.disconnect)) }
            } else {
                Button(onClick = onScanClick, enabled = !scanning) { Text(stringResource(R.string.scan_scale)) }
            }
        }

        if (devices.isNotEmpty() && !connectionState.startsWith("Scale:") && !connectionState.contains("step on")) {
            Text(stringResource(R.string.devices_found), style = MaterialTheme.typography.labelLarge)
            devices.forEach { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceClick(device) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyMetricsCard(metrics: BodyMetrics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.body_composition), style = MaterialTheme.typography.titleSmall)
            MetricRow(stringResource(R.string.bmi), "%.1f".format(metrics.bmi))
            if (metrics.bodyFatPercent != 0.0) {
                MetricRow(stringResource(R.string.body_fat), "%.1f%%".format(metrics.bodyFatPercent))
                MetricRow(stringResource(R.string.fat_mass), "%.1f kg".format(metrics.fatMassKg))
                MetricRow(stringResource(R.string.lbm), "%.1f kg".format(metrics.lbmKg))
                MetricRow(stringResource(R.string.water), "%.1f%%".format(metrics.waterPercent))
                MetricRow(stringResource(R.string.muscle), "%.1f%% (%.1f kg)".format(metrics.musclePercent, metrics.muscleMassKg))
                MetricRow(stringResource(R.string.bone), "%.1f kg".format(metrics.boneKg))
                MetricRow(stringResource(R.string.protein), "%.1f%% (%.1f kg)".format(metrics.proteinPercent, metrics.proteinKg))
                MetricRow(stringResource(R.string.bmr), "%d kcal".format(metrics.bmrKcal))
                MetricRow(stringResource(R.string.visceral), "%d".format(metrics.visceralLevel))
                MetricRow(stringResource(R.string.body_age), "%d".format(metrics.bodyAge))
                MetricRow(stringResource(R.string.score), "%d".format(metrics.score))
                if (metrics.segmental != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.segmental), style = MaterialTheme.typography.labelMedium)
                    MetricRow(stringResource(R.string.seg_arms), "%.1f / %.1f kg fat".format(metrics.segmental.fatRh, metrics.segmental.fatLh))
                    MetricRow(stringResource(R.string.seg_legs), "%.1f / %.1f kg fat".format(metrics.segmental.fatRf, metrics.segmental.fatLf))
                    MetricRow(stringResource(R.string.seg_trunk), "%.1f kg fat".format(metrics.segmental.fatT))
                }
            } else {
                Text(stringResource(R.string.bmi_only), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.bmi_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BottleStatusCard(tempC: Int?, tds: Int?, batteryPct: Int?, charging: Boolean) {
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
            if (tempC != null) {
                Text(
                    stringResource(R.string.temperature_c, tempC),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (tds != null) {
                Text(
                    stringResource(R.string.purity_ppm, tds),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (batteryPct != null) {
                val label = if (charging) {
                    stringResource(R.string.battery_charging, batteryPct)
                } else {
                    stringResource(R.string.battery_pct, batteryPct)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HydrationCard(totalMl: Int, goalMl: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ml, totalMl),
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(12.dp))
            val progress = (totalMl.toFloat() / goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.goal_ml, goalMl),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
