package com.vayunmathur.email.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.ui.composer.EmailHtmlEditor
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.email.ui.composer.InlineAttachment
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ComposerScreen(
    viewModel: EmailViewModel,
    initialTo: String = "",
    initialSubject: String = "",
    initialBody: String = "",
    inReplyTo: String? = null,
    references: String? = null,
    draftId: Long? = null,
    onBack: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccount by viewModel.selectedAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    var fromAccount by remember(selectedAccount, accounts) {
        mutableStateOf(selectedAccount ?: accounts.firstOrNull())
    }

    var to by remember { mutableStateOf(initialTo) }
    var cc by remember { mutableStateOf("") }
    var bcc by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(initialSubject) }
    val bodyController = remember { com.vayunmathur.email.ui.composer.EmailHtmlEditorController(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    var pickTarget by remember { mutableStateOf(0) }
    var pickTick by remember { mutableStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val email = result.data?.data?.let { contactEmail(context, it) }
            if (!email.isNullOrBlank()) {
                when (pickTarget) {
                    0 -> to = appendRecipient(to, email)
                    1 -> cc = appendRecipient(cc, email)
                    2 -> bcc = appendRecipient(bcc, email)
                }
                pickTick++
            }
        }
    }
    LaunchedEffect(pickTick) {
        if (pickTick > 0) {
            contactPicker.launch(
                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI)
            )
        }
    }
    val pickContact = { target: Int ->
        pickTarget = target
        pickTick++
    }

    var currentDraftId by remember { mutableStateOf(draftId) }
    var draftLoaded by remember { mutableStateOf(draftId == null) }
    LaunchedEffect(draftId) {
        if (draftId != null) {
            viewModel.loadDraft(draftId)?.let { d ->
                to = d.to; cc = d.cc; bcc = d.bcc
                if (d.cc.isNotBlank() || d.bcc.isNotBlank()) showCcBcc = true
                subject = d.subject; bodyController.setHtml(d.body)
                accounts.firstOrNull { it.email == d.accountEmail }?.let { fromAccount = it }
            }
            draftLoaded = true
        }
    }

    var appliedSignature by remember { mutableStateOf("") }
    LaunchedEffect(fromAccount) {
        if (draftId != null) return@LaunchedEffect
        val block = signatureBlockHtml(fromAccount)
        if (block != appliedSignature) {
            val t = bodyController.html
            val newText = when {
                appliedSignature.isEmpty() -> t + block
                t.endsWith(appliedSignature) -> t.removeSuffix(appliedSignature) + block
                else -> t + block
            }
            bodyController.setHtml(newText)
            appliedSignature = block
        }
    }

    LaunchedEffect(fromAccount, draftLoaded) {
        val acc = fromAccount
        if (!draftLoaded || acc == null) return@LaunchedEffect
        snapshotFlow { listOf(to, cc, bcc, subject, bodyController.html) }
            .debounce(800)
            .collect {
                val hasContent = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() ||
                    subject.isNotBlank() || bodyController.html.isNotBlank()
                if (hasContent) {
                    viewModel.saveDraft(currentDraftId, acc.email, to, cc, bcc, subject, bodyController.html) { id ->
                        currentDraftId = id
                    }
                }
            }
    }

    // Clean up orphan inline images on text change
    LaunchedEffect(bodyController.html) {
        bodyController.inlineCleanupOrphans()
    }

    // Attachment launchers
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) attachments = attachments + uris
    }

    // Inline image pickers: use PickVisualMedia + fallback GetContent image/*
    val inlineFallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val name = uriName(context, uri)
            // Copy to cache/inline for stable uri
            val cached = copyInlineToCache(context, uri, name)
            val finalUri = cached ?: uri
            bodyController.insertInlineImage(context, finalUri, mime, name)
        }
    }

    // Try to use PickVisualMedia if available
    var visualMediaLauncher: Any? = null
    val pickVisualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val name = uriName(context, uri)
            val cached = copyInlineToCache(context, uri, name)
            val finalUri = cached ?: uri
            bodyController.insertInlineImage(context, finalUri, mime, name)
        }
    }

    val pickMultipleVisualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val name = uriName(context, uri)
                val cached = copyInlineToCache(context, uri, name)
                val finalUri = cached ?: uri
                bodyController.insertInlineImage(context, finalUri, mime, name)
            }
        }
    }

    AppScaffold(
        title = stringResource(R.string.compose),
        onNavigateBack = onBack,
        actions = {
            IconButton(onClick = { attachmentLauncher.launch("*/*") }) {
                IconAttachment()
            }
            IconButton(onClick = {
                // Prefer new photo picker
                try {
                    pickVisualLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    inlineFallbackLauncher.launch("image/*")
                }
            }) {
                com.vayunmathur.library.ui.IconImage()
            }
            Box {
                TextButton(onClick = { showSchedule = true }, enabled = fromAccount != null) {
                    Text(stringResource(R.string.later))
                }
                DropdownMenu(expanded = showSchedule, onDismissRequest = { showSchedule = false }) {
                    val schedule = { at: Long ->
                        showSchedule = false
                        fromAccount?.let { acc ->
                            viewModel.scheduleSend(
                                account = acc, to = to, subject = subject,
                                body = bodyController.html,
                                asHtml = true,
                                cc = cc.ifBlank { null }, bcc = bcc.ifBlank { null },
                                attachments = attachments,
                                inlineImages = bodyController.toInlineAttachments(),
                                inReplyTo = inReplyTo,
                                references = references, scheduledAt = at,
                            ) { currentDraftId?.let { viewModel.deleteDraft(it) } }
                            AppMessages.show(resources.getString(R.string.scheduled))
                            onBack()
                        }
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_1_hour)) }, onClick = { schedule(System.currentTimeMillis() + 3_600_000L) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.this_evening_6_pm)) }, onClick = { schedule(scheduleTime(18, sameDay = true)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tomorrow_8_am)) }, onClick = { schedule(scheduleTime(8, sameDay = false)) })
                }
            }
            IconButton(onClick = {
                val acc = fromAccount ?: return@IconButton
                sending = true
                viewModel.sendEmailFrom(
                    account = acc,
                    to = to,
                    subject = subject,
                    body = bodyController.html,
                    asHtml = true,
                    cc = cc.ifBlank { null },
                    bcc = bcc.ifBlank { null },
                    attachments = attachments,
                    inlineImages = bodyController.toInlineAttachments(),
                    inReplyTo = inReplyTo,
                    references = references,
                    onSuccess = {
                        sending = false
                        currentDraftId?.let { viewModel.deleteDraft(it) }
                        AppMessages.show(resources.getString(R.string.message_sent))
                        onBack()
                    },
                    onError = { err ->
                        sending = false
                        AppMessages.show(resources.getString(R.string.saved_to_outbox, err))
                        onBack()
                    }
                )
            }, enabled = !sending && fromAccount != null) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else IconSend()
            }
        },
        bottomBar = {
            // Always show formatting toolbar? Per plan, only when focused, but now scrollable and more buttons
            if (bodyController.focused) {
                EmailComposerFormatToolbar(controller = bodyController,
                    onInsertImage = {
                        try {
                            pickVisualLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: Exception) {
                            inlineFallbackLauncher.launch("image/*")
                        }
                    })
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                onClick = { showAccountPicker = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${stringResource(R.string.from_label)}: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        fromAccount?.email ?: stringResource(R.string.select_account),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconChevronRight()
                }
            }

            if (showAccountPicker) {
                AlertDialog(
                    onDismissRequest = { showAccountPicker = false },
                    confirmButton = {},
                    title = { Text(stringResource(R.string.select_sender)) },
                    text = {
                        Column {
                            accounts.forEach { acc ->
                                ListItem(
                                    content = { Text(acc.email) },
                                    modifier = Modifier.clickable {
                                        fromAccount = acc
                                        showAccountPicker = false
                                    }
                                )
                            }
                        }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = to, onValueChange = { to = it },
                    label = { Text(stringResource(R.string.to_label)) },
                    trailingIcon = { IconButton(onClick = { pickContact(0) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCcBcc = !showCcBcc }) { Text(stringResource(R.string.cc_bcc)) }
            }
            if (showCcBcc) {
                OutlinedTextField(
                    value = cc, onValueChange = { cc = it }, label = { Text(stringResource(R.string.cc)) },
                    trailingIcon = { IconButton(onClick = { pickContact(1) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bcc, onValueChange = { bcc = it }, label = { Text(stringResource(R.string.bcc)) },
                    trailingIcon = { IconButton(onClick = { pickContact(2) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = subject, onValueChange = { subject = it },
                label = { Text(stringResource(R.string.subject_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            com.vayunmathur.email.ui.composer.EmailHtmlEditor(
                controller = bodyController,
                placeholder = stringResource(R.string.body_label),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // Inline images thumbnail row (text-based to avoid heavy deps; WYSIWYG preview already in editor)
            if (bodyController.inlineImages.isNotEmpty()) {
                val inlineTotal = bodyController.inlineImages.sumOf { uriSize(context, it.localUri) }
                Text(stringResource(R.string.inline_images, bodyController.inlineImages.size, android.text.format.Formatter.formatShortFileSize(context, inlineTotal)), style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(bodyController.inlineImages.size) { idx ->
                        val img = bodyController.inlineImages[idx]
                        Card(modifier = Modifier.size(96.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    com.vayunmathur.library.ui.IconImage(modifier = Modifier.size(24.dp))
                                    Text(img.fileName.take(16), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { bodyController.removeInlineImage(img.cid) }, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                                    com.vayunmathur.library.ui.IconClose(modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (attachments.isNotEmpty() || bodyController.inlineImages.isNotEmpty()) {
                val totalBytes = attachments.sumOf { uriSize(context, it) } + bodyController.inlineImages.sumOf { uriSize(context, it.localUri) }
                Text(stringResource(R.string.attachments_1, android.text.format.Formatter.formatShortFileSize(context, totalBytes)), style = MaterialTheme.typography.labelLarge)
                if (totalBytes > 25L * 1024 * 1024) {
                    Text(
                        stringResource(R.string.total_attachment_size_exceeds_25_mb_many),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (attachments.isNotEmpty()) {
                attachments.forEach { uri ->
                    val attachmentLabel = remember(uri) {
                        "${uriName(context, uri)} · " + android.text.format.Formatter.formatShortFileSize(context, uriSize(context, uri))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAttachment(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            attachmentLabel,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { attachments = attachments - uri }) {
                            com.vayunmathur.library.ui.IconClose(modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
