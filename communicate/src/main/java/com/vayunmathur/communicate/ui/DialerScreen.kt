package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.T9
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.telephony.GoogleVoiceTelecom
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.IconPaste
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DialerScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    val lineChoices = rememberLineChoices()
    var selectedLine by remember { mutableStateOf<LineChoice?>(null) }
    var userSelectedLine by remember { mutableStateOf(false) }

    LaunchedEffect(lineChoices) {
        val current = selectedLine
        if (current == null || current !in lineChoices) {
            selectedLine = lineChoices.firstOrNull { it is LineChoice.GoogleVoice } ?: lineChoices.firstOrNull()
            userSelectedLine = false
        } else if (!userSelectedLine && lineChoices.any { it is LineChoice.GoogleVoice }) {
            selectedLine = lineChoices.first { it is LineChoice.GoogleVoice }
        }
    }

    fun place(target: String) {
        if (target.isBlank()) return
        val choice = selectedLine
        if (choice is LineChoice.GoogleVoice) {
            GoogleVoiceCallManager.init(context)
        }
        CommunicateRepository.placeCall(context, choice, target)
    }

    AppScaffold(
        title = stringResource(R.string.dialer_title),
        actions = {
            val sel = selectedLine
            if (lineChoices.size > 1 && sel != null) {
                LineSelector(
                    choices = lineChoices,
                    selected = sel,
                    onSelect = {
                        selectedLine = it
                        userSelectedLine = true
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        DefaultDialerGate(modifier = Modifier.padding(padding)) { roleRevision ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                PermissionGate(
                    permission = Manifest.permission.READ_CONTACTS,
                    message = stringResource(R.string.permission_contacts_message),
                    modifier = Modifier.weight(1f),
                ) { permissionRevision ->
                    val contacts = produceState<List<CommunicateContact>?>(initialValue = null, roleRevision, permissionRevision) {
                        value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                    }
                    // Keyed on the contact list only: the query must never re-run the provider query.
                    val rows = contacts.value
                    val entries = remember(rows) { rows.orEmpty().map { T9.entryFor(it.name, it.phoneNumber) } }
                    val query = T9.normalizeQuery(number)
                    val filtered = remember(rows, query) {
                        val all = rows.orEmpty()
                        if (query.isEmpty()) all
                        else all.filterIndexed { index, _ -> T9.matches(entries[index], query) }
                    }
                    when {
                        rows == null -> com.vayunmathur.library.ui.LoadingState(Modifier.weight(1f))
                        rows.isEmpty() -> EmptyState(
                            title = stringResource(R.string.empty_contacts),
                            icon = { IconContacts() },
                            modifier = Modifier.weight(1f),
                        )
                        filtered.isEmpty() -> EmptyState(
                            title = stringResource(R.string.empty_contacts_for_digits),
                            icon = { IconContacts() },
                            modifier = Modifier.weight(1f),
                        )
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.contacts),
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            items(filtered, key = { "${it.id}-${it.phoneNumber}" }) { contact ->
                                ContactRow(contact) {
                                    place(contact.phoneNumber)
                                }
                            }
                        }
                    }
                }
                DialPad(
                    number = number,
                    onAppend = { number += it },
                    onBackspace = { number = number.dropLast(1) },
                    onClear = { number = "" },
                    onPaste = {
                        scope.launch {
                            val pasted = clipboard.getClipEntry()
                                ?.clipData
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                                .filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                            if (pasted.isNotBlank()) number = pasted
                        }
                    },
                    onCall = { place(number) },
                )
            }
        }
    }
}

@Composable
private fun DialPad(
    number: String,
    onAppend: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onCall: () -> Unit,
) {
    // Letters are the physical keycap markings from ITU E.161, not translatable copy.
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "", "#" to ""),
    )
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = number.ifEmpty { stringResource(R.string.phone_number) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = onPaste) {
                    IconPaste()
                }
            }
            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit,
                            letters = letters,
                            onClick = { onAppend(digit) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClear, enabled = number.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.clear))
                }
                FilledIconButton(onClick = onCall, enabled = number.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    IconCall()
                }
                IconButton(onClick = onBackspace, enabled = number.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    IconBackspace()
                }
            }
        }
    }
}

@Composable
private fun DialKey(digit: String, letters: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(52.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(digit, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ContactRow(contact: CommunicateContact, onClick: () -> Unit) {
    ListItem(
        content = {
            Text(
                contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = { Text("${contact.label}  ${contact.phoneNumber}", maxLines = 1) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialsFor(contact.name),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        trailingContent = { IconCall() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
