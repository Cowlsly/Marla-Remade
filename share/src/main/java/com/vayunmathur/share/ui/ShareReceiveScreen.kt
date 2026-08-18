package com.vayunmathur.share.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.share.R
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.ReceiveUiState
import com.vayunmathur.share.platform.TransferProgress
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
                stringResource(R.string.share_incoming_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (uiState.incomingConnections.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (uiState.isVisible) R.string.share_waiting_for_devices
                            else R.string.share_turn_on_visibility
                        ),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            items(uiState.incomingConnections, key = { it.remoteEndpoint }) { conn ->
                IncomingRequestCard(connection = conn, actions = actions)
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
                        stringResource(R.string.share_visibility_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !isVisible -> stringResource(R.string.share_visibility_off)
                            listenPort != null ->
                                stringResource(R.string.share_visibility_on_port, localName, listenPort)
                            else -> stringResource(R.string.share_visibility_on, localName)
                        },
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

/**
 * Collects a live [Connection]'s flows and hands them to [IncomingRequestContent].
 *
 * Split so the rendering half is drivable from literal data: a `Connection` owns a
 * socket and a native session handle, neither of which exists in a preview.
 */
@Composable
private fun IncomingRequestCard(connection: Connection, actions: ShareActions) {
    val state by connection.state.collectAsState()
    val pendingFiles by connection.pendingFiles.collectAsState()
    val receivedFiles by connection.receivedFiles.collectAsState()
    val error by connection.error.collectAsState()
    val bytesSent by connection.bytesSent.collectAsState()
    val bytesReceived by connection.bytesReceived.collectAsState()

    IncomingRequestContent(
        remoteEndpoint = connection.remoteEndpoint,
        progress = TransferProgress(
            state = state,
            pendingFiles = pendingFiles,
            receivedFiles = receivedFiles,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            error = error,
        ),
        actions = actions,
        onAccept = { actions.acceptIncoming(connection, true) },
        onReject = { actions.acceptIncoming(connection, false) },
        onDisconnect = { actions.disconnect(connection) },
    )
}

@Composable
fun IncomingRequestContent(
    remoteEndpoint: String,
    progress: TransferProgress,
    actions: ShareActions,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val pendingFiles = progress.pendingFiles
    val receivedFiles = progress.receivedFiles
    val error = progress.error

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                remoteEndpoint,
                style = MaterialTheme.typography.titleSmall,
            )
            if (error != null && progress.state != ShareState.Failed) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (progress.state) {
                ShareState.Handshaking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text(
                            stringResource(R.string.share_connecting),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                ShareState.AwaitingAccept -> {
                    Text(
                        stringResource(R.string.share_wants_to_send, pendingFiles.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FileList(files = pendingFiles)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAccept) { Text(stringResource(R.string.share_accept)) }
                        OutlinedButton(onClick = onReject) {
                            Text(stringResource(R.string.share_decline))
                        }
                    }
                }
                ShareState.Transferring -> {
                    Text(
                        stringResource(R.string.share_receiving),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val total = pendingFiles.sumOf { it.sizeBytes }
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (progress.bytesReceived.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.share_progress_kb,
                                progress.bytesReceived / 1024,
                                total / 1024,
                            ),
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
                        stringResource(R.string.share_received_count, receivedFiles.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ReceivedFileRows(files = receivedFiles, actions = actions)
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.share_dismiss)) }
                }
                ShareState.Failed -> {
                    Text(
                        // The native failure reason, which names the phase that broke,
                        // rather than a bare return code.
                        error ?: stringResource(R.string.share_transfer_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    ReceivedFileRows(files = receivedFiles, actions = actions)
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.share_dismiss)) }
                }
                ShareState.Unknown -> {
                    Text(
                        stringResource(R.string.share_unknown_state),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * One row per received file, each offering Share and Save.
 *
 * The directory picker is launched from here rather than the ViewModel: an
 * `ActivityResultLauncher` must be registered by a composable, and the file the user
 * tapped has to survive the trip out to DocumentsUI and back.
 */
@Composable
private fun ReceivedFileRows(files: List<ReceivedFile>, actions: ShareActions) {
    if (files.isEmpty()) return
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    val pickDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val file = pendingSave
        pendingSave = null
        if (treeUri != null && file != null) actions.saveReceivedFile(file, treeUri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { file ->
            ListItem(
                headlineContent = { Text(file.name) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.share_file_summary,
                            formatSize(file.sizeBytes),
                            file.mimeType,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { actions.shareReceivedFile(context, file) }) {
                            Text(stringResource(R.string.share_action_share))
                        }
                        TextButton(
                            onClick = {
                                pendingSave = file
                                pickDirectory.launch(null)
                            }
                        ) {
                            Text(stringResource(R.string.share_action_save))
                        }
                    }
                },
            )
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
