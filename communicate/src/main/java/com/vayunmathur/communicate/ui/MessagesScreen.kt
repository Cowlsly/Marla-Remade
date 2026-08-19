package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.heightIn
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.communicate.data.CommunicateContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(onOpenThread: (SmsThread) -> Unit, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    val lineChoices = rememberLineChoices()
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        NewMessagePicker(
            choices = lineChoices,
            onDismiss = { showPicker = false },
            onCompose = { choice, number ->
                showPicker = false
                val sim = choice as? com.vayunmathur.communicate.data.LineChoice.Sim
                val threadId = if (sim != null) {
                    CommunicateRepository.getOrCreateSmsThreadId(context, number)
                        ?: CommunicateRepository.stableThreadId(number)
                } else {
                    CommunicateRepository.stableThreadId(number)
                }
                onOpenThread(
                    SmsThread(
                        threadId = threadId,
                        address = number,
                        displayName = null,
                        snippet = "",
                        timestampMillis = System.currentTimeMillis(),
                        unreadCount = 0,
                        line = choice.category,
                        remoteId = null,
                        subscriptionId = sim?.subscriptionId,
                    ),
                )
            },
            onCreateGroup = { choice, subject, contacts ->
                showPicker = false
                scope.launch {
                    when (choice.category) {
                        CommunicateLine.WhatsApp -> {
                            if (!CommunicateRepository.isWhatsAppConnected()) {
                                AppMessages.show("WhatsApp isn't connected — the number may be logged out or banned. Re-register in Accounts.")
                                return@launch
                            }
                            val groupJid = withContext(Dispatchers.IO) {
                                CommunicateRepository.createWhatsAppGroup(context, subject, contacts)
                            }
                            if (groupJid == null) {
                                AppMessages.show("Couldn't create the group (server rejected it)")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = CommunicateRepository.stableThreadId(groupJid),
                                        address = groupJid,
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.WhatsApp,
                                        remoteId = groupJid,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        CommunicateLine.Signal -> {
                            val groupId = withContext(Dispatchers.IO) {
                                CommunicateRepository.createSignalGroup(context, subject, contacts)
                            }
                            if (groupId == null) {
                                AppMessages.show("Couldn't create the Signal group (server rejected it)")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = CommunicateRepository.stableThreadId(groupId),
                                        address = groupId,
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.Signal,
                                        remoteId = groupId,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        CommunicateLine.Sim -> {
                            val sim = choice as? com.vayunmathur.communicate.data.LineChoice.Sim
                            val groupThreadId = withContext(Dispatchers.IO) {
                                CommunicateRepository.getOrCreateSmsGroupThreadId(context, contacts)
                            }
                            if (groupThreadId == null) {
                                AppMessages.show("Couldn't create the group thread")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = groupThreadId,
                                        address = contacts.joinToString(", "),
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.Sim,
                                        remoteId = null,
                                        subscriptionId = sim?.subscriptionId,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        else -> AppMessages.show("Groups aren't supported on this line")
                    }
                }
            },
        )
    }

    AppScaffold(
        title = stringResource(R.string.messages_title),
        actions = {
            IconButton(onClick = onOpenAccounts) { IconPerson() }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // Open the contact picker to choose a recipient + line.
                showPicker = true
            }) {
                IconAdd()
            }
        },
    ) { padding ->
        DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_SMS,
                message = stringResource(R.string.permission_sms_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                // Foreground polling: Google Voice has no cheap realtime channel wired up yet,
                // so while this screen is shown we re-fetch the merged inbox on an interval.
                // (The Punctual/WebChannel realtime upgrade is noted as future work.)
                var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.LaunchedEffect(roleRevision, permissionRevision) {
                    while (true) {
                        kotlinx.coroutines.delay(15_000)
                        tick++
                    }
                }
                val threads = produceState<List<SmsThread>?>(initialValue = null, roleRevision, permissionRevision, tick) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsThreadsMerged(context) }
                }

                when (val rows = threads.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
                    emptyList<SmsThread>() -> EmptyState(
                        title = stringResource(R.string.empty_messages),
                        icon = { IconSms() },
                        modifier = Modifier.padding(padding),
                    )
                    else -> {
                        var pendingDelete by remember { mutableStateOf<SmsThread?>(null) }
                        LazyColumn(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(rows, key = { it.threadId }) { thread ->
                                MessageThreadRow(
                                    thread = thread,
                                    onClick = { onOpenThread(thread) },
                                    onDelete = { pendingDelete = thread },
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                        val toDelete = pendingDelete
                        if (toDelete != null) {
                            com.vayunmathur.library.ui.ConfirmDialog(
                                title = stringResource(R.string.delete_conversation_title),
                                message = stringResource(R.string.delete_conversation_message),
                                confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
                                dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
                                destructive = true,
                                onConfirm = {
                                    pendingDelete = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            CommunicateRepository.deleteConversation(context, toDelete)
                                        }
                                        if (ok) tick++ else AppMessages.show(context.getString(R.string.delete_failed))
                                    }
                                },
                                onDismiss = { pendingDelete = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageThreadRow(thread: SmsThread, onClick: () -> Unit, onDelete: () -> Unit = {}) {
    val context = LocalContext.current
    val title = when {
        thread.isGroup -> thread.groupTitle
            ?: thread.displayName
            ?: groupTitleFromParticipants(thread.participants)
            ?: thread.address.ifBlank { stringResource(R.string.conversation_title) }
        else -> thread.displayName ?: thread.address.ifBlank { stringResource(R.string.conversation_title) }
    }
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LineBadge(thread.line, thread.subscriptionId, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDateTime(context, thread.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Text(
                thread.snippet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (thread.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingContent = { ThreadAvatar(title = title, isGroup = thread.isGroup) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.unreadCount > 0) UnreadBadge(thread.unreadCount)
                com.vayunmathur.library.ui.OverflowMenu(icon = { IconMoreVert() }) {
                    Item(
                        text = stringResource(com.vayunmathur.library.ui.R.string.delete),
                        leadingIcon = { IconDelete() },
                        onClick = onDelete,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Alice, Bob +N" from a group's participant addresses; null if there are none. */
private fun groupTitleFromParticipants(participants: List<String>): String? {
    if (participants.isEmpty()) return null
    val shown = participants.take(2)
    val extra = participants.size - shown.size
    return if (extra > 0) shown.joinToString(", ") + " +$extra" else shown.joinToString(", ")
}

@Composable
private fun ThreadAvatar(title: String, isGroup: Boolean = false) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            IconGroup(tint = MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            Text(
                initialsFor(title),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * FAB flow: pick a line + recipient to start a 1:1 conversation, or flip the "Group" switch to
 * multi-select recipients (chips) + an optional name and create a group (WhatsApp, or SIM MMS).
 */
@Composable
private fun NewMessagePicker(
    choices: List<com.vayunmathur.communicate.data.LineChoice>,
    onDismiss: () -> Unit,
    onCompose: (com.vayunmathur.communicate.data.LineChoice, String) -> Unit,
    onCreateGroup: (com.vayunmathur.communicate.data.LineChoice, String, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val region = remember { deviceRegion(context) }
    var query by remember { mutableStateOf("") }
    var groupMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    // Selected recipients for group mode, keyed by phone number (value = display label).
    val selectedContacts = remember { mutableStateListOf<Pair<String, String>>() }
    // Lines that support group chats: WhatsApp, Signal, and SIM (MMS). GV is 1:1 only.
    val groupChoices = remember(choices) {
        choices.filter {
            it.category == CommunicateLine.WhatsApp ||
                it.category == CommunicateLine.Signal ||
                it.category == CommunicateLine.Sim
        }
    }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    val activeChoices = if (groupMode) groupChoices else choices

    // Keep the selected line valid when toggling into group mode.
    androidx.compose.runtime.LaunchedEffect(groupMode) {
        if (groupMode && (selected == null || selected !in groupChoices)) {
            selected = groupChoices.firstOrNull()
        }
    }

    fun toggleContact(phone: String, label: String) {
        val idx = selectedContacts.indexOfFirst { it.first == phone }
        if (idx >= 0) selectedContacts.removeAt(idx) else selectedContacts.add(phone to label)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (groupMode) "New group" else stringResource(R.string.new_message)) },
        text = {
            Column {
                if (groupChoices.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        Text("Group", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        com.vayunmathur.library.ui.Switch(
                            checked = groupMode,
                            onCheckedChange = { groupMode = it },
                        )
                    }
                }
                val sel = selected
                if (sel != null && activeChoices.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.choose_line),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        LineSelector(choices = activeChoices, selected = sel, onSelect = { selected = it })
                    }
                }
                if (groupMode) {
                    androidx.compose.material3.OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Group name (optional)") },
                        singleLine = true,
                    )
                    Spacer(Modifier.size(6.dp))
                    if (selectedContacts.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        ) {
                            items(selectedContacts.toList(), key = { it.first }) { (phone, label) ->
                                FilterChip(
                                    selected = true,
                                    onClick = { toggleContact(phone, label) },
                                    label = { Text(label, maxLines = 1) },
                                    trailingIcon = { IconClose(Modifier.size(16.dp)) },
                                )
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name or number") },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                PermissionGate(
                    permission = Manifest.permission.READ_CONTACTS,
                    message = "Allow contacts access to pick a recipient.",
                ) { rev ->
                    val contacts by produceState(initialValue = emptyList<CommunicateContact>(), rev) {
                        value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                    }
                    val q = query.trim()
                    val qDigits = q.filter { it.isDigit() }
                    val filtered = if (q.isEmpty()) {
                        contacts
                    } else {
                        contacts.filter { c ->
                            c.name.contains(q, ignoreCase = true) ||
                                (qDigits.isNotEmpty() && c.phoneNumber.filter { it.isDigit() }.contains(qDigits))
                        }
                    }
                    val exactExists = qDigits.isNotEmpty() &&
                        contacts.any { it.phoneNumber.filter { c -> c.isDigit() }.endsWith(qDigits) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        // Fallback: a raw number not in contacts (shown formatted).
                        if (qDigits.length >= 4 && !exactExists) {
                            item {
                                val label = formatNumber(q, region)
                                ContactPickRow(title = "Send to $label", subtitle = null, selected = null) {
                                    if (groupMode) toggleContact(q, label) else selected?.let { onCompose(it, q) }
                                }
                            }
                        }
                        items(filtered, key = { it.id }) { c ->
                            val isSel = if (groupMode) selectedContacts.any { it.first == c.phoneNumber } else null
                            ContactPickRow(title = c.name, subtitle = c.phoneNumber, selected = isSel) {
                                if (groupMode) toggleContact(c.phoneNumber, c.name) else selected?.let { onCompose(it, c.phoneNumber) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (groupMode) {
                TextButton(
                    onClick = {
                        val choice = selected
                        if (choice != null && selectedContacts.isNotEmpty()) {
                            onCreateGroup(choice, groupName.trim(), selectedContacts.map { it.first })
                        }
                    },
                    enabled = selected != null && selectedContacts.size >= 1,
                ) { Text("Create") }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.clear)) }
            }
        },
        dismissButton = if (groupMode) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}

@Composable
private fun ContactPickRow(title: String, subtitle: String?, selected: Boolean? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Device region (SIM > network > locale) for phone-number formatting/parsing. */
private fun deviceRegion(context: Context): String {
    val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    return (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
}

/** Human-friendly display of a typed number (national format), falling back to the raw input. */
private fun formatNumber(raw: String, region: String): String = runCatching {
    val util = PhoneNumberUtil.getInstance()
    util.format(util.parse(raw, region), PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
}.getOrDefault(raw)
