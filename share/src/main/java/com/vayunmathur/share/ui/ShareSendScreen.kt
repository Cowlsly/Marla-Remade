package com.vayunmathur.share.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.SendUiState

@Composable
fun ShareSendScreen(
    viewModel: ShareViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.sendUiState.collectAsState()
    ShareSendContent(
        uiState = uiState,
        actions = viewModel,
        modifier = modifier,
    )
}

@Composable
fun ShareSendContent(
    uiState: SendUiState,
    actions: ShareActions,
    modifier: Modifier = Modifier,
) {
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) actions.setOutgoingUris(uris)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutgoingFilesCard(
                uris = uiState.outgoingUris,
                displayNames = uiState.outgoingDisplayNames,
                onPickFiles = { pickerLauncher.launch(arrayOf("*/*")) },
                onClear = actions::clearOutgoing,
            )
        }
        if (uiState.activeConnection != null) {
            item {
                val conn = uiState.activeConnection
                val state by conn.state.collectAsState()
                val error by conn.error.collectAsState()
                val sent by conn.bytesSent.collectAsState()
                TransferCard(
                    endpoint = conn.remoteEndpoint,
                    state = state,
                    bytesSent = sent,
                    error = error,
                    onDisconnect = { actions.disconnect(conn) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Nearby devices",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.isScanning) {
                    Button(onClick = actions::stopScan) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = actions::startScan) { Text("Scan") }
                }
            }
        }
        if (uiState.isScanning && uiState.discoveredDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Scanning for nearby devices…", modifier = Modifier.weight(1f))
                    }
                }
            }
        } else if (uiState.discoveredDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No devices found. Tap Scan while the other device is visible.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            items(uiState.discoveredDevices, key = { it.endpointId }) { device ->
                DeviceRow(
                    device = device,
                    onTap = { actions.connectToDevice(device) },
                )
            }
        }
        if (uiState.activeConnection == null && uiState.outgoingUris.isNotEmpty() && uiState.discoveredDevices.isNotEmpty()) {
            item {
                Text(
                    "Select a device above to send ${uiState.outgoingUris.size} file(s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (uiState.activeConnection == null && uiState.outgoingUris.isEmpty() && uiState.discoveredDevices.isEmpty()) {
            item {
                Text(
                    "Tip: share from any app via the system share sheet — Share will appear as a target. " +
                        "Or pick files here and then Scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun OutgoingFilesCard(
    uris: List<Uri>,
    displayNames: List<String>,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (uris.isEmpty()) "No files selected"
                    else "${uris.size} file(s) selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFiles) { Text(if (uris.isEmpty()) "Pick files" else "Add files") }
                    if (uris.isNotEmpty()) {
                        OutlinedButton(onClick = onClear) { Text("Clear") }
                    }
                }
            }
            if (uris.isNotEmpty()) {
                displayNames.forEach { name ->
                    Text(
                        "• $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: NearbyDevice, onTap: () -> Unit) {
    val canConnect = device.host != null && device.port != null
    val subtitle = when {
        canConnect -> "${device.host}:${device.port}"
        device.source == DiscoverySource.Ble -> "BLE peer — TCP endpoint via mDNS once visible"
        else -> device.extra ?: device.serviceName ?: ""
    }.ifBlank { device.endpointId }
    val sourceLabel = when (device.source) {
        DiscoverySource.Nsd -> "Wi-Fi"
        DiscoverySource.Ble -> "Bluetooth"
        DiscoverySource.Both -> "Wi-Fi • Bluetooth"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canConnect, onClick = onTap),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.endpointName, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (!canConnect) {
                Text(
                    "Waiting for network address — keep the peer visible and scanning.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    endpoint: String,
    state: ShareState,
    bytesSent: Long,
    error: String?,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sending to $endpoint", style = MaterialTheme.typography.titleSmall)
            when (state) {
                ShareState.Handshaking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("Connecting…")
                    }
                }
                ShareState.AwaitingAccept -> {
                    Text("Waiting for recipient to accept…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Transferring -> {
                    Text("Sending… ${bytesSent / 1024} KB")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Completed -> {
                    Text("Sent!", color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onDisconnect) { Text("Done") }
                }
                ShareState.Failed -> {
                    Text(error ?: "Send failed", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDisconnect) { Text("Dismiss") }
                }
                ShareState.Unknown -> {
                    Text("Unknown state")
                }
            }
            if (error != null && state != ShareState.Failed) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
