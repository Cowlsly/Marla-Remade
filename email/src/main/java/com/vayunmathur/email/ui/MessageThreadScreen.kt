package com.vayunmathur.email.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailSettings
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.detectUnsubscribe
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.EmlUtils
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.email.data.UnsubscribeMethod
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.HtmlText
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMarkRead
import com.vayunmathur.library.ui.IconMarkUnread
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.rememberModalBottomSheetState
import com.vayunmathur.library.util.AppMessages

@Composable
fun MessageThreadPage(
    viewModel: EmailViewModel,
    accountEmail: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit
) {
    val messages by viewModel.getThread(accountEmail, threadId).collectAsStateWithLifecycle(emptyList())
    MessageThreadScreen(messages = messages, actions = viewModel, threadId = threadId, onBack = onBack, onReply = onReply, onForward = onForward, onCompose = onCompose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    messages: List<EmailMessage>,
    actions: MessageThreadActions,
    threadId: String = "",
    onBack: () -> Unit = {},
    onReply: (String, String, String?) -> Unit = { _, _, _ -> },
    onForward: (String, String?) -> Unit = { _, _ -> },
    onCompose: (String, String) -> Unit = { _, _ -> },
) {
    var hasMarkedAsRead by remember(threadId) { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<EmailMessage?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("message/rfc822")) { targetUri ->
        val msg = pendingExport
        pendingExport = null
        if (targetUri != null && msg != null) {
            actions.exportEml(msg.accountEmail, msg.folderName, msg.id, targetUri) { ok, err ->
                AppMessages.show(if (ok) "Saved as .eml" else "Save failed: ${err ?: "unknown"}")
            }
        }
    }
    LaunchedEffect(messages) {
        if (!hasMarkedAsRead && messages.isNotEmpty()) {
            messages.filter { !it.isRead }.forEach { msg -> actions.markAsRead(msg.accountEmail, msg.folderName, msg.id, true) }
            hasMarkedAsRead = true
        }
    }
    AppScaffold(
        title = messages.firstOrNull()?.subject ?: stringResource(R.string.conversation),
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { "${it.accountEmail}|${it.folderName}|${it.id}" }) { msg ->
                MessageItem(msg = msg, actions = actions, onBack = onBack, onReply = onReply, onForward = onForward, onCompose = onCompose, onExportEml = { toExport ->
                    pendingExport = toExport
                    val fileName = EmlUtils.sanitizeFileName(toExport.subject.ifBlank { "email_${toExport.id}" })
                    exportLauncher.launch(fileName)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageItem(
    msg: EmailMessage,
    actions: MessageThreadActions,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit,
    onExportEml: (EmailMessage) -> Unit = {},
) {
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val resources = LocalResources.current
    var showOverflow by remember { mutableStateOf(false) }
    LaunchedEffect(msg.id) {
        attachments = actions.getAttachments(msg.accountEmail, msg.id)
        if (msg.body == null) actions.fetchBodyIfNeeded(msg)
    }
    val senderName = senderDisplayName(msg.from).ifEmpty { msg.from }
    val senderEmail = msg.from.substringAfter("<").substringBefore(">").trim()
    val initial = senderName.take(1).uppercase()
    val avatarColor = Color(accountColor(msg.accountEmail))
    Column {
        Row(modifier = Modifier.fillMaxWidth().clickable { showDetails = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = avatarColor, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(text = initial, color = contentColorOn(avatarColor), style = MaterialTheme.typography.titleMedium) }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(text = "  \u2022  ${msg.date.substringBeforeLast(":")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(text = stringResource(R.string.to_me), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var showSnooze by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showSnooze = true }) { Text(stringResource(R.string.snooze)) }
                    DropdownMenu(expanded = showSnooze, onDismissRequest = { showSnooze = false }) {
                        val snooze = { at: Long ->
                            showSnooze = false
                            actions.snoozeMessage(msg.accountEmail, msg.folderName, msg.id, at)
                            AppMessages.show(resources.getString(R.string.snoozed))
                            onBack()
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.later_today_6_pm)) }, onClick = { snooze(scheduleTime(18, sameDay = true)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tomorrow_8_am)) }, onClick = { snooze(scheduleTime(8, sameDay = false)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.in_1_week)) }, onClick = { snooze(System.currentTimeMillis() + 7L * 24 * 3600_000) })
                    }
                }
                IconButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }) { IconUndo() }
                IconButton(onClick = { actions.markAsRead(msg.accountEmail, msg.folderName, msg.id, !msg.isRead); if (msg.isRead) onBack() }) {
                    if (msg.isRead) IconMarkUnread() else IconMarkRead()
                }
                Box {
                    IconButton(onClick = { showOverflow = true }) { IconMoreVert() }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as_eml)) }, leadingIcon = { IconSave(modifier = Modifier.size(20.dp)) }, onClick = { showOverflow = false; onExportEml(msg) })
                    }
                }
            }
        }
        if (msg.isHtml && msg.body != null) {
            val settings = remember(context) { EmailSettings.get(context) }
            val loadRemoteByDefault by settings.loadRemoteImages.collectAsStateWithLifecycle()
            var loadImages by remember(msg.id, loadRemoteByDefault) { mutableStateOf(loadRemoteByDefault) }
            var showQuotes by remember(msg.id) { mutableStateOf(false) }
            var cidMap by remember(msg.id) { mutableStateOf<Map<String, java.io.File>>(emptyMap()) }
            val bodyHasCid = remember(msg.body) { msg.body.contains("cid:", ignoreCase = true) }
            LaunchedEffect(msg.id, msg.body) { if (bodyHasCid) { val map = actions.loadCidMap(context, msg); if (map.isNotEmpty()) cidMap = map } }
            val hasQuotes = remember(msg.body) { listOf("gmail_quote", "yahoo_quoted", "moz-cite-prefix", "<blockquote").any { msg.body.contains(it, ignoreCase = true) } }
            val remoteBlocked = !loadImages && cidMap.isEmpty() && !bodyHasCid
            if (remoteBlocked) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.remote_images_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { loadImages = true }) { Text(stringResource(R.string.load_images)) }
                }
            }
            HtmlText(html = msg.body, blockRemoteImages = remoteBlocked, hideQuotes = hasQuotes && !showQuotes, cidMap = cidMap, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            if (hasQuotes) {
                TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showQuotes) stringResource(R.string.hide_quoted_text) else stringResource(R.string.show_quoted_text)) }
            }
        } else {
            var showQuotes by remember(msg.id) { mutableStateOf(false) }
            val (mainText, quotedText) = remember(msg.body) { splitQuotedText(msg.body ?: "(No Content)") }
            Text(text = if (showQuotes || quotedText.isEmpty()) (msg.body ?: stringResource(R.string.no_content)) else mainText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (quotedText.isNotEmpty()) {
                TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showQuotes) stringResource(R.string.hide_quoted_text) else stringResource(R.string.show_quoted_text)) }
            }
        }
        if (attachments.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.attachments_2), style = MaterialTheme.typography.labelLarge)
                attachments.forEach { att -> AttachmentItem(att, actions) }
            }
        }
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }, modifier = Modifier.weight(1f)) {
                IconUndo(modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.reply))
            }
            OutlinedButton(onClick = { onForward(msg.subject, msg.body) }, modifier = Modifier.weight(1f)) {
                IconForward(modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.forward))
            }
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val unsubscribe = remember(msg.listUnsubscribe, msg.listUnsubscribePost, msg.body, msg.isHtml) { msg.detectUnsubscribe() }
            if (unsubscribe != null) {
                var showConfirm by remember(msg.id) { mutableStateOf(false) }
                TextButton(onClick = { showConfirm = true }) { Text(stringResource(R.string.unsubscribe)) }
                if (showConfirm) {
                    UnsubscribeDialog(method = unsubscribe, onDismiss = { showConfirm = false }, onConfirm = { showConfirm = false; performUnsubscribe(unsubscribe, context, actions, onCompose) })
                }
            }
            TextButton(onClick = { actions.blockSender(msg.from); AppMessages.show(resources.getString(R.string.sender_blocked)); onBack() }) { Text(stringResource(R.string.block_sender)) }
        }
        HorizontalDivider()
    }
    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(text = msg.date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                DetailItem(label = stringResource(R.string.from_label), name = senderName, email = senderEmail, avatarColor = avatarColor)
                DetailItem(label = stringResource(R.string.to_label), name = "me", email = msg.to ?: "", avatarColor = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconInbox(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(text = msg.folderName, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun splitQuotedText(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val onWrote = Regex("^On .+ wrote:\\s*$")
    val origMsg = Regex("^-{2,}\\s*Original Message\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val line = lines[i].trim()
        val isBoundary = onWrote.matches(line) || origMsg.matches(line) || line.startsWith(">")
        if (isBoundary && i > 0) {
            val main = lines.subList(0, i).joinToString("\n").trimEnd()
            val quoted = lines.subList(i, lines.size).joinToString("\n")
            return main to quoted
        }
    }
    return body to ""
}

@Composable
private fun UnsubscribeDialog(method: UnsubscribeMethod, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (message, confirmLabel) = when (method) {
        is UnsubscribeMethod.OneClickPost -> "Send an unsubscribe request to the sender?" to "Unsubscribe"
        is UnsubscribeMethod.OpenWeb -> "Open the unsubscribe page in your browser?" to "Open"
        is UnsubscribeMethod.SendMail -> "Compose an unsubscribe email to ${method.address}?" to "Compose"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.unsubscribe)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) } })
}

private fun performUnsubscribe(method: UnsubscribeMethod, context: android.content.Context, actions: MessageThreadActions, onCompose: (String, String) -> Unit) {
    when (method) {
        is UnsubscribeMethod.OneClickPost -> {
            AppMessages.show(context.getString(R.string.unsubscribing))
            actions.oneClickUnsubscribe(method.url) { ok -> AppMessages.show(if (ok) "Unsubscribed" else "Unsubscribe failed") }
        }
        is UnsubscribeMethod.OpenWeb -> {
            val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, method.url.toUri())) }.isSuccess
            if (!opened) AppMessages.show(context.getString(R.string.couldn_t_open_unsubscribe_page))
        }
        is UnsubscribeMethod.SendMail -> onCompose(method.address, "Unsubscribe")
    }
}
