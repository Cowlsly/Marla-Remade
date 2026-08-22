package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.MessageStatus
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConversationScreen(
    threadId: Long,
    address: String,
    line: CommunicateLine,
    remoteId: String?,
    subscriptionId: Int? = null,
    isGroup: Boolean = false,
    participants: List<String> = emptyList(),
    groupTitle: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(threadId) { mutableStateOf("") }
    var selectedAttachments by remember(threadId) { mutableStateOf<List<CommunicateAttachment>>(emptyList()) }
    // Bumped after a send to re-fetch the thread.
    var refresh by remember(threadId) { mutableIntStateOf(0) }
    val lineChoices = rememberLineChoices()
    val fixedLineChoice = remember(line, subscriptionId, lineChoices) {
        when (line) {
            CommunicateLine.GoogleVoice -> LineChoice.GoogleVoice
            CommunicateLine.WhatsApp -> LineChoice.WhatsApp
            CommunicateLine.Signal -> LineChoice.Signal
            CommunicateLine.Sim -> lineChoices
                .filterIsInstance<LineChoice.Sim>()
                .firstOrNull { subscriptionId == null || it.subscriptionId == subscriptionId }
                ?: LineChoice.Sim(subscriptionId ?: -1, context.getString(R.string.line_sim))
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedAttachments = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            CommunicateAttachment(
                contentUri = uri.toString(),
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
            )
        }
    }
    val thread = remember(threadId) {
        SmsThread(
            threadId = threadId,
            address = address,
            displayName = null,
            snippet = "",
            timestampMillis = 0,
            unreadCount = 0,
            line = line,
            remoteId = remoteId,
            isGroup = isGroup,
            participants = participants,
            groupTitle = groupTitle,
        )
    }
    val title = produceState(initialValue = address.ifBlank { context.getString(R.string.conversation_title) }, address) {
        value = withContext(Dispatchers.IO) {
            CommunicateRepository.findContactName(context, address)
                ?: address.ifBlank { context.getString(R.string.conversation_title) }
        }
    }
    // For groups, resolve a "Alice, Bob +N" subtitle from the participant addresses (contact names
    // where available). Cheap: runs once off the main thread.
    val groupSubtitle = produceState<String?>(initialValue = null, isGroup, participants) {
        if (!isGroup || participants.isEmpty()) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                val names = participants.take(3).map { p ->
                    CommunicateRepository.findContactName(context, p) ?: p
                }
                val extra = participants.size - names.size
                if (extra > 0) names.joinToString(", ") + " +$extra" else names.joinToString(", ")
            }
        }
    }

    // Opening a SIM thread clears the provider's unread flags. Nothing else writes them back, and
    // the badge is recomputed from the provider, so imported rows would stay unread forever (#562).
    androidx.compose.runtime.LaunchedEffect(threadId, line) {
        if (line == CommunicateLine.Sim) {
            CommunicateRepository.markSimThreadRead(context, threadId)
        }
    }
    // Opening a Google Voice thread marks it read server-side via batchupdateattributes.
    androidx.compose.runtime.LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            CommunicateRepository.updateGoogleVoiceThread(
                context, remoteId, com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.MarkRead,
            )
        }
    }
    // Foreground polling for the open GV thread (no realtime channel yet).
    androidx.compose.runtime.LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                refresh++
            }
        }
    }
    // WhatsApp messages land in local Room via the socket→event-processor; poll the cache so inbound
    // (and our own outgoing echo) appear live while the conversation is open. Cheap local reads.
    androidx.compose.runtime.LaunchedEffect(line) {
        if (line == CommunicateLine.WhatsApp) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                refresh++
            }
        }
    }
    // Send WhatsApp read receipts (and clear the unread badge) for the open conversation. Re-runs on
    // each poll tick; the repository guards against re-sending for an already-read message.
    var waLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(line, remoteId, refresh) {
        if (line == CommunicateLine.WhatsApp) {
            waLastReadId = CommunicateRepository.markWhatsAppRead(context, remoteId, address, waLastReadId)
        }
    }
    // Signal messages land in local Room via the socket→event-processor; poll so inbound
    // (and our own outgoing echo) appear live while the conversation is open.
    androidx.compose.runtime.LaunchedEffect(line) {
        if (line == CommunicateLine.Signal) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                refresh++
            }
        }
    }
    // Send Signal read receipts for the open conversation (mirrors WhatsApp).
    var sigLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(line, remoteId, refresh) {
        if (line == CommunicateLine.Signal) {
            sigLastReadId = try {
                CommunicateRepository.markSignalRead(context, remoteId, address, sigLastReadId)
            } catch (_: Throwable) {
                sigLastReadId
            }
        }
    }

    AppScaffold(
        title = {
            val displayTitle = if (isGroup) (groupTitle ?: title.value) else title.value
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LineBadge(line, modifier = Modifier.padding(start = 8.dp))
                }
                if (isGroup) {
                    groupSubtitle.value?.let { subtitle ->
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (address.isNotBlank() && title.value != address) {
                    Text(
                        address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onNavigateBack = onBack,
        actions = {
            var showDeleteConfirm by remember { mutableStateOf(false) }
            if (showDeleteConfirm) {
                com.vayunmathur.library.ui.ConfirmDialog(
                    title = stringResource(com.vayunmathur.communicate.R.string.delete_conversation_title),
                    message = stringResource(com.vayunmathur.communicate.R.string.delete_conversation_message),
                    confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
                    dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
                    destructive = true,
                    onConfirm = {
                        showDeleteConfirm = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CommunicateRepository.deleteConversation(
                                    context,
                                    SmsThread(
                                        threadId = threadId,
                                        address = address,
                                        displayName = null,
                                        snippet = "",
                                        timestampMillis = 0L,
                                        unreadCount = 0,
                                        line = line,
                                        remoteId = remoteId,
                                        isGroup = isGroup,
                                        participants = participants,
                                        groupTitle = groupTitle,
                                    ),
                                )
                            }
                            if (ok) onBack() else AppMessages.show(context.getString(com.vayunmathur.communicate.R.string.delete_failed))
                        }
                    },
                    onDismiss = { showDeleteConfirm = false },
                )
            }
            if (line == CommunicateLine.GoogleVoice && remoteId != null) {
                IconButton(onClick = {
                    scope.launch {
                        val ok = CommunicateRepository.updateGoogleVoiceThread(
                            context, remoteId,
                            com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.Archive,
                        )
                        if (ok) onBack() else AppMessages.show(context.getString(R.string.gv_action_failed))
                    }
                }) { IconArchive() }
            }
            com.vayunmathur.library.ui.OverflowMenu(icon = { com.vayunmathur.library.ui.IconMoreVert() }) {
                Item(
                    text = stringResource(com.vayunmathur.library.ui.R.string.delete),
                    leadingIcon = { com.vayunmathur.library.ui.IconDelete() },
                    onClick = { showDeleteConfirm = true },
                )
            }
        },
        bottomBar = {
            ComposeSmsRow(
                draft = draft,
                onDraftChange = { draft = it },
                attachments = selectedAttachments,
                onAttach = { attachmentPicker.launch(arrayOf("image/*", "video/*")) },
                onRemoveAttachment = { attachment ->
                    selectedAttachments = selectedAttachments.filterNot { it.contentUri == attachment.contentUri }
                },
                onSend = {
                    val text = draft.trim()
                    val attachments = selectedAttachments
                    if (text.isEmpty() && attachments.isEmpty()) return@ComposeSmsRow
                    draft = ""
                    selectedAttachments = emptyList()
                    scope.launch {
                        val ok = CommunicateRepository.sendMessage(
                            context,
                            fixedLineChoice,
                            address,
                            text,
                            if (fixedLineChoice is LineChoice.Sim) null else remoteId,
                            attachments,
                            participants = if (isGroup) participants else emptyList(),
                        )
                        if (ok) refresh++ else AppMessages.show(context.getString(R.string.gv_send_failed))
                    }
                },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Google Voice threads don't require the default-SMS role or READ_SMS; only SIM does.
        if (line == CommunicateLine.GoogleVoice) {
            MessagesList(padding, refresh) {
                CommunicateRepository.loadSmsMessagesMerged(context, thread)
            }
            return@AppScaffold
        }
        DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_SMS,
                message = stringResource(R.string.permission_sms_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val messages = produceState<List<SmsMessage>?>(initialValue = null, threadId, roleRevision, permissionRevision, refresh) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsMessagesMerged(context, thread) }
                }
                MessagesContent(padding, messages.value)
            }
        }
    }
}

@Composable
private fun MessageAttachment(
    attachment: CommunicateAttachment,
    bubbleColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    shape: RoundedCornerShape,
) {
    val context = LocalContext.current
    val openAttachment = {
        ExternalIntents.launch(
            context,
            Intent(Intent.ACTION_VIEW, attachment.contentUri.toUri()),
        )
        Unit
    }
    if (attachment.mimeType.startsWith("image/")) {
        var showFallback by remember(attachment.contentUri) { mutableStateOf(false) }
        if (!showFallback) {
            Surface(
                shape = shape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 260.dp)
                    .padding(top = 2.dp)
                    .clickable(onClick = openAttachment),
            ) {
                AsyncImage(
                    model = attachment.contentUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state -> showFallback = state is AsyncImageState.Error },
                    modifier = Modifier
                        .widthIn(min = 180.dp, max = 320.dp)
                        .heightIn(min = 120.dp, max = 260.dp),
                )
            }
        }
        if (showFallback) {
            AttachmentFallbackRow(
                attachment = attachment,
                bubbleColor = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                onClick = openAttachment,
            )
        }
    } else {
        AttachmentFallbackRow(
            attachment = attachment,
            bubbleColor = bubbleColor,
            contentColor = contentColor,
            shape = shape,
            onClick = openAttachment,
        )
    }
}

@Composable
private fun AttachmentFallbackRow(
    attachment: CommunicateAttachment,
    bubbleColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        color = bubbleColor,
        contentColor = contentColor,
        shape = shape,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAttachment()
            Spacer(Modifier.size(6.dp))
            Text(attachment.mimeType, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessagesList(padding: PaddingValues, refresh: Int, load: suspend () -> List<SmsMessage>) {
    val messages = produceState<List<SmsMessage>?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { load() }
    }
    MessagesContent(padding, messages.value)
}

@Composable
private fun MessagesContent(padding: PaddingValues, rows: List<SmsMessage>?) {
    when (rows) {
        null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
        emptyList<SmsMessage>() -> EmptyState(
            title = stringResource(R.string.empty_messages),
            icon = { IconSms() },
            modifier = Modifier.padding(padding),
        )
        else -> LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun ComposeSmsRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<CommunicateAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (CommunicateAttachment) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { attachment ->
                        SelectedAttachmentPreview(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttach) {
                    IconAttachment()
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = onSend, enabled = draft.isNotBlank() || attachments.isNotEmpty()) {
                    IconSend()
                }
            }
        }
    }
}

@Composable
private fun SelectedAttachmentPreview(
    attachment: CommunicateAttachment,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 160.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.mimeType.startsWith("image/")) {
                AsyncImage(
                    model = Uri.parse(attachment.contentUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                IconAttachment()
            }
            Spacer(Modifier.size(6.dp))
            Text(
                attachment.mimeType,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onRemove) {
                IconClose(Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsMessage) {
    val context = LocalContext.current
    val alignment = if (message.outgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.outgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (message.outgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (message.outgoing) 20.dp else 4.dp,
        bottomEnd = if (message.outgoing) 4.dp else 20.dp,
    )

    val waSd = if (message.line == CommunicateLine.WhatsApp) {
        com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData.parse(message.serviceData)
    } else {
        null
    }
    val sigSd = if (message.line == CommunicateLine.Signal) {
        com.vayunmathur.communicate.data.signal.SignalServiceData.parse(message.serviceData)
    } else {
        null
    }
    // Canonical name used for bubble extras.
    val sd: Any? = waSd ?: sigSd
    val sdIsRevoked: Boolean = waSd?.isRevoked == true || sigSd?.isRevoked == true
    val sdIsEdited: Boolean = waSd?.isEdited == true || sigSd?.isEdited == true
    val sdQuotedBody: String? = waSd?.quotedBody ?: sigSd?.quotedBody
    val sdQuotedSender: String? = waSd?.quotedSender ?: sigSd?.quotedSender
    val sdPollQuestion: String? = waSd?.pollQuestion ?: sigSd?.pollQuestion
    val sdSenderName: String? = waSd?.senderName ?: sigSd?.senderName
    val bodyText = when {
        sdIsRevoked -> "🚫 This message was deleted"
        else -> message.body
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        // Group sender name (incoming group messages). WhatsApp carries it in serviceData;
        // other lines (SIM/GV groups) resolve it from the per-message sender address.
        val senderLabel = produceState<String?>(initialValue = null, message.id, sdSenderName, message.senderAddress) {
            value = when {
                message.outgoing -> null
                sdSenderName != null -> sdSenderName
                message.senderAddress != null -> withContext(Dispatchers.IO) {
                    CommunicateRepository.findContactName(context, message.senderAddress) ?: message.senderAddress
                }
                else -> null
            }
        }
        senderLabel.value?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        // Quoted reply preview.
        if (sdQuotedBody != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                Text(
                    (sdQuotedSender?.let { "$it: " } ?: "") + sdQuotedBody,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (bodyText.isNotBlank()) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(bodyText, fontSize = 15.sp)
                    // Poll rendering.
                    if (sdPollQuestion != null) {
                        Spacer(Modifier.size(4.dp))
                        Text("📊 $sdPollQuestion", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        (waSd?.pollOptions ?: emptyList()).forEach { opt ->
                            Text("• ${opt.name} (${opt.voteCount})", fontSize = 13.sp)
                        }
                        (sigSd?.pollOptions ?: emptyList()).forEach { opt ->
                            Text("• ${opt.name} (${opt.voteCount})", fontSize = 13.sp)
                        }
                    }
                    if (sdIsEdited) {
                        Text(
                            "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        // Reaction chips (WhatsApp + Signal).
        val reactions: List<Pair<String, Int>> = when {
            waSd != null && waSd.reactions.isNotEmpty() -> waSd.reactions.map { it.emoji to it.count }
            sigSd != null && sigSd.reactions.isNotEmpty() -> sigSd.reactions.map { it.emoji to it.count }
            else -> emptyList()
        }
        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                reactions.forEach { (emoji, count) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            if (count > 1) "$emoji $count" else emoji,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        message.attachments.forEach { attachment ->
            MessageAttachment(
                attachment = attachment,
                bubbleColor = bubbleColor,
                contentColor = contentColor,
                shape = shape,
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDateTime(context, message.timestampMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.outgoing) {
                Spacer(Modifier.size(4.dp))
                MessageStatusTicks(message.status)
            }
        }
    }
}

/**
 * Renders WhatsApp-style delivery ticks for an outgoing message:
 * grey ✓ (Sent), grey ✓✓ (Delivered), blue ✓✓ (Read). [MessageStatus.Failed] shows a red "!".
 * [MessageStatus.None] renders nothing (lines without receipts).
 */
@Composable
private fun MessageStatusTicks(status: MessageStatus) {
    when (status) {
        MessageStatus.None -> Unit
        MessageStatus.Failed -> Text(
            "!",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        else -> {
            val glyph = if (status == MessageStatus.Sent) "✓" else "✓✓"
            val color = if (status == MessageStatus.Read) {
                // WhatsApp's read-receipt blue (not theme-tinted).
                Color(0xFF34B7F1)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                glyph,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
