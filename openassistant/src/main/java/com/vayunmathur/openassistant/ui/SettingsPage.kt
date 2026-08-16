package com.vayunmathur.openassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.util.AssistantViewModel
import com.vayunmathur.openassistant.util.InferenceService
import com.vayunmathur.openassistant.util.SettingsActions
import com.vayunmathur.openassistant.util.SettingsUiState
import kotlinx.coroutines.launch

/** Binds [AssistantViewModel] and the nav back stack to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AssistantViewModel) {
    val memories by viewModel.memories.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ds = remember(context) { DataStoreUtils.getInstance(context) }

    var systemPrompt by remember {
        mutableStateOf(
            ds.getString(InferenceService.KEY_SYSTEM_PROMPT)
                ?: InferenceService.DEFAULT_SYSTEM_PROMPT
        )
    }

    SettingsScreen(
        state = SettingsUiState(memories = memories, systemPrompt = systemPrompt),
        actions = object : SettingsActions {
            override fun back() = backStack.pop()
            override fun deleteMemory(memory: Memory) = viewModel.deleteMemory(memory)
            override fun setSystemPrompt(prompt: String) {
                systemPrompt = prompt
                scope.launch { ds.setString(InferenceService.KEY_SYSTEM_PROMPT, prompt) }
            }
            override fun resetSystemPrompt() {
                systemPrompt = InferenceService.DEFAULT_SYSTEM_PROMPT
                scope.launch {
                    ds.setString(
                        InferenceService.KEY_SYSTEM_PROMPT,
                        InferenceService.DEFAULT_SYSTEM_PROMPT
                    )
                }
            }
        },
        // Passed in rather than built inside the screen: the backup buttons need the
        // database passphrase, which only exists on a real device.
        backupButtons = {
            val pass = remember { DatabaseHelper(context).getPassphrase() }
            BackupButtons(
                dbConfigs = listOf("passwords-db" to pass),
                dbCodec = SqlCipherDbCodec,
                extraFiles = emptyList()
            )
        },
    )
}

/**
 * The memories screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    /** Top-bar backup/restore buttons; empty in a preview, which has no database. */
    backupButtons: @Composable RowScope.() -> Unit = {},
) {
    val memories = state.memories

    DetailLazyColumn(
        title = stringResource(R.string.settings_title),
        onNavigateBack = { actions.back() },
        actions = backupButtons,
    ) {
            item {
                Text(
                    text = stringResource(R.string.system_prompt),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = { actions.setSystemPrompt(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.system_prompt)) },
                    minLines = 5,
                    maxLines = 12,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { actions.resetSystemPrompt() },
                        enabled = state.systemPrompt != InferenceService.DEFAULT_SYSTEM_PROMPT
                    ) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.memories),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            if (memories.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_memories_yet), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(memories, key = { it.id }) { memory ->
                    MemoryItem(memory, onDelete = { actions.deleteMemory(memory) })
                }
            }
    }
}

@Composable
fun MemoryItem(memory: Memory, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = memory.content,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onDelete) {
                IconDelete()
            }
        }
    }
}
