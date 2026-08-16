package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CallLogsScreen() {
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.call_logs_title),
    ) { padding ->
        DefaultDialerGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_CALL_LOG,
                message = stringResource(R.string.permission_call_logs_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val callLogs = produceState<List<CommunicateCallLogEntry>?>(initialValue = null, roleRevision, permissionRevision) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadCallLogsMerged(context) }
                }
                when (val rows = callLogs.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
                    emptyList<CommunicateCallLogEntry>() -> EmptyState(
                        title = stringResource(R.string.empty_call_logs),
                        icon = { IconHistory() },
                        modifier = Modifier.padding(padding),
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(rows, key = { it.id }) { entry ->
                            CallLogRow(entry) {
                                CommunicateRepository.placeCall(context, entry.phoneNumber)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallLogRow(entry: CommunicateCallLogEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = entry.displayName ?: entry.phoneNumber
    ListItem(
        content = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (entry.type == CommunicateCallType.Missed) FontWeight.Bold else FontWeight.Medium,
                )
                LineBadge(entry.line, entry.subscriptionId, modifier = Modifier.padding(start = 6.dp))
            }
        },
        supportingContent = {
            Column {
                Text(entry.phoneNumber, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.type.label()} · ${formatDateTime(context, entry.timestampMillis)} · " +
                        stringResource(R.string.duration_seconds, entry.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.type == CommunicateCallType.Missed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        leadingContent = { IconForCallType(entry.type) },
        trailingContent = { IconCall() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun IconForCallType(type: CommunicateCallType) {
    val tint = if (type == CommunicateCallType.Missed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    IconCall(tint = tint)
}

@Composable
private fun CommunicateCallType.label(): String = when (this) {
    CommunicateCallType.Incoming -> stringResource(R.string.incoming_call)
    CommunicateCallType.Outgoing -> stringResource(R.string.outgoing_call)
    CommunicateCallType.Missed -> stringResource(R.string.missed_call)
    CommunicateCallType.Rejected -> stringResource(R.string.rejected_call)
    CommunicateCallType.Blocked -> stringResource(R.string.blocked_call)
    CommunicateCallType.Voicemail -> stringResource(R.string.voicemail_call)
    CommunicateCallType.Unknown -> stringResource(R.string.unknown_call)
}
