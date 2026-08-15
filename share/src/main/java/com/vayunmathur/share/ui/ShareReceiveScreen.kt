package com.vayunmathur.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.ReceiveUiState
import com.vayunmathur.share.network.transport.Connection

@Composable
fun ShareReceiveScreen(
    viewModel: ShareViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.receiveUiState.collectAsState()
    ShareReceiveContent(
        uiState = uiState,
        actions = viewModel,
        modifier = modifier,
    )
}

@Composable
fun ShareReceiveContent(
    uiState: ReceiveUiState,
    actions: ShareActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VisibilityCard(
                isVisible = uiState.isVisible,
                localName = uiState.localName,
                listenPort = uiState.listenPort,
                onToggle = actions::setVisible,
            )
        }
        item {
            Text(
                "Incoming requests",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (uiState.incomingConnections.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (uiState.isVisible) "Waiting for nearby devices…"
                        else "Turn on visibility to receive files.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            items(uiState.incomingConnections, key = { it.remoteEndpoint }) { conn ->
                IncomingRequestCard(
                    connection = conn,
                    onAccept = { actions.acceptIncoming(conn, true) },
                    onReject = { actions.acceptIncoming(conn, false) },
                    onDisconnect = { actions.disconnect(conn) },
                )
            }
        }
    }
}

@Composable
private fun VisibilityCard(
    isVisible: Boolean,
    localName: String,
    listenPort: Int?,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Device visible to nearby devices",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isVisible) "Visible as \"$localName\"" + (listenPort?.let { " · port $it" } ?: "")
                        else "Turn on to appear in Quick Share nearby lists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = isVisible, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun IncomingRequestCard(
    connection: Connection,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by connection.state.collectAsState()
    val pendingFiles by connection.pendingFiles.collectAsState()
    val error by connection.error.collectAsState()
    val bytesReceived by connection.bytesReceived.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                connection.remoteEndpoint,
                style = MaterialTheme.typography.titleSmall,
            )
            if (error != null) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (state) {
                ShareState.Handshaking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ShareState.AwaitingAccept -> {
                    Text(
                        "Wants to send ${pendingFiles.size} file(s)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FileList(files = pendingFiles)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAccept) { Text("Accept") }
                        OutlinedButton(onClick = onReject) { Text("Decline") }
                    }
                }
                ShareState.Transferring -> {
                    Text("Receiving…", style = MaterialTheme.typography.bodyMedium)
                    val total = pendingFiles.sumOf { it.sizeBytes }
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (bytesReceived.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${bytesReceived / 1024} KB / ${total / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    FileList(files = pendingFiles)
                }
                ShareState.Completed -> {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FileList(files = pendingFiles)
                    Button(onClick = onDisconnect) { Text("Dismiss") }
                }
                ShareState.Failed -> {
                    Text(
                        error ?: "Transfer failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDisconnect) { Text("Dismiss") }
                }
                ShareState.Unknown -> {
                    Text("Unknown state", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FileList(files: List<PendingFile>) {
    if (files.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            Text(
                "• ${f.name} (${formatSize(f.sizeBytes)}) · ${f.mimeType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
