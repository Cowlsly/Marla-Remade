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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.share.R
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.SendUiState

/**
 * The whole in-app surface: sending.
 *
 * Receiving has no screen. It is driven by notifications and turned on and off from a Quick
 * Settings tile, so it works when the app has never been opened.
 */
@Composable
fun ShareSendPage(viewModel: ShareViewModel) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        ShareSendScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

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
                TransferCardHost(conn = conn, onDisconnect = { actions.disconnect(conn) })
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.share_nearby_devices),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.isScanning) {
                    Button(onClick = actions::stopScan) { Text(stringResource(R.string.share_stop)) }
                } else {
                    OutlinedButton(onClick = actions::startScan) { Text(stringResource(R.string.share_scan)) }
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
                        Text(stringResource(R.string.share_scanning), modifier = Modifier.weight(1f))
                    }
                }
            }
        } else if (uiState.discoveredDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.share_no_devices),
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
                    stringResource(R.string.share_select_device, uiState.outgoingUris.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (uiState.activeConnection == null && uiState.outgoingUris.isEmpty() && uiState.discoveredDevices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.share_send_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
fun OutgoingFilesCard(
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
                    if (uris.isEmpty()) stringResource(R.string.share_no_files_selected)
                    else stringResource(R.string.share_files_selected, uris.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFiles) {
                        Text(
                            stringResource(
                                if (uris.isEmpty()) R.string.share_pick_files else R.string.share_add_files
                            )
                        )
                    }
                    if (uris.isNotEmpty()) {
                        OutlinedButton(onClick = onClear) { Text(stringResource(R.string.share_clear)) }
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
fun DeviceRow(device: NearbyDevice, onTap: () -> Unit) {
    val canConnect = device.host != null && device.port != null
    val subtitle = when {
        canConnect -> "${device.host}:${device.port}"
        device.source == DiscoverySource.Ble -> stringResource(R.string.share_ble_only)
        else -> device.extra ?: device.serviceName ?: ""
    }.ifBlank { device.endpointId }
    val sourceLabel = stringResource(
        when (device.source) {
            DiscoverySource.Nsd -> R.string.share_source_wifi
            DiscoverySource.Ble -> R.string.share_source_bluetooth
            DiscoverySource.Both -> R.string.share_source_both
        }
    )
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
                    stringResource(R.string.share_waiting_for_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Collects a live [Connection]'s flows and hands them to [TransferCard].
 *
 * Split so the rendering half is drivable from literal data: a `Connection` owns a TCP socket
 * and a native session handle, and Layoutlib can load neither.
 */
@Composable
internal fun TransferCardHost(conn: Connection, onDisconnect: () -> Unit) {
    val state by conn.state.collectAsState()
    val error by conn.error.collectAsState()
    val sent by conn.bytesSent.collectAsState()
    val peerName by conn.peerName.collectAsState()
    TransferCard(
        endpoint = peerName ?: conn.remoteEndpoint,
        state = state,
        bytesSent = sent,
        error = error,
        onDisconnect = onDisconnect,
    )
}

@Composable
fun TransferCard(
    endpoint: String,
    state: ShareState,
    bytesSent: Long,
    error: String?,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.share_sending_to, endpoint),
                style = MaterialTheme.typography.titleSmall,
            )
            when (state) {
                ShareState.Handshaking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.share_connecting))
                    }
                }
                ShareState.AwaitingAccept -> {
                    Text(stringResource(R.string.share_waiting_for_accept))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Transferring -> {
                    Text(stringResource(R.string.share_sending_kb, bytesSent / 1024))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Completed -> {
                    Text(stringResource(R.string.share_sent), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.share_done)) }
                }
                ShareState.Failed -> {
                    Text(
                        // The native failure reason, which names the phase that broke, rather
                        // than a bare return code.
                        error ?: stringResource(R.string.share_send_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.share_dismiss)) }
                }
                ShareState.Unknown -> {
                    Text(stringResource(R.string.share_unknown_state))
                }
            }
            if (error != null && state != ShareState.Failed) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
