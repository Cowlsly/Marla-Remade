package com.vayunmathur.things.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.ScaleBleManager

/**
 * Device management: scan for, connect to, and disconnect both BLE devices, plus the Health Connect
 * permission setup. Shows a back arrow only when it was pushed over [HomePage] (i.e. a device is
 * connected); as the cold-start root it shows none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesPage(
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    scaleConnectionState: String,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onScaleDisconnectClick: () -> Unit,
    onHealthConnectClick: () -> Unit,
    onNavigateBack: (() -> Unit)?,
) {
    AppScaffold(
        title = stringResource(R.string.devices),
        onNavigateBack = onNavigateBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bottle
            item { Text(stringResource(R.string.bottle_status), style = MaterialTheme.typography.titleMedium) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(connectionState, style = MaterialTheme.typography.titleSmall)
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

            item { HorizontalDivider() }

            // Scale
            item { Text(stringResource(R.string.scale_title), style = MaterialTheme.typography.titleMedium) }
            item { Text(stringResource(R.string.scale_subtitle), style = MaterialTheme.typography.bodySmall) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(scaleConnectionState, style = MaterialTheme.typography.titleSmall)
                    if (scaleConnected(scaleConnectionState)) {
                        OutlinedButton(onClick = onScaleDisconnectClick) { Text(stringResource(R.string.disconnect)) }
                    } else {
                        Button(onClick = onScaleScanClick, enabled = !scaleScanning) { Text(stringResource(R.string.scan_scale)) }
                    }
                }
            }
            if (scaleDevices.isNotEmpty() && !scaleConnected(scaleConnectionState)) {
                item { Text(stringResource(R.string.devices_found), style = MaterialTheme.typography.labelLarge) }
                items(scaleDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onScaleDeviceClick(device) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                OutlinedButton(onClick = onHealthConnectClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.health_connect))
                }
            }
        }
    }
}

private fun scaleConnected(state: String): Boolean =
    state.startsWith("Scale: ") || state.contains("step on") || state.startsWith("Weighing")
