package com.vayunmathur.passwords.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.platform.PasswordEditUiState
import com.vayunmathur.passwords.platform.PasswordsActions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditScreen(
    state: PasswordEditUiState,
    actions: PasswordsActions,
    onBack: () -> Unit = {},
    /** Called with the assigned id after a brand-new entry is saved. */
    onCreated: (Long) -> Unit = {},
) {
    val draft = state.draft
    val current = draft ?: state.saved

    var websiteInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nameRequired = stringResource(R.string.name_required)
    val focusManager = LocalFocusManager.current

    fun addWebsiteFromInput() {
        val candidate = websiteInput.trim()
        if (candidate.isNotEmpty()) {
            actions.updateDraft { d ->
                if (d.websites.contains(candidate)) d
                else d.copy(websites = d.websites + candidate)
            }
        }
        websiteInput = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current.id == 0L) stringResource(R.string.add_password) else stringResource(R.string.edit_password)) },
                navigationIcon = {
                    IconNavigation(onBack)
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val d = draft ?: return@FloatingActionButton
                if (d.name.isBlank()) {
                    scope.launch { snackbarHostState.showSnackbar(nameRequired) }
                    return@FloatingActionButton
                }
                // Normalize empty TOTP to null before saving.
                actions.updateDraft { it.copy(totpSecret = it.totpSecret?.ifBlank { null }) }
                if (d.id == 0L) {
                    actions.saveDraft { newId -> onCreated(newId) }
                } else {
                    actions.saveDraft()
                    onBack()
                }
            }) {
                IconSave()
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = current.name,
                        onValueChange = { v -> actions.updateDraft { it.copy(name = v) } },
                        label = { Text(stringResource(R.string.label_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.username,
                        onValueChange = { v -> actions.updateDraft { it.copy(username = v) } },
                        label = { Text(stringResource(R.string.label_username)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.email,
                        onValueChange = { v -> actions.updateDraft { it.copy(email = v) } },
                        label = { Text(stringResource(R.string.label_email)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
            }

            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = current.password,
                        onValueChange = { v -> actions.updateDraft { it.copy(password = v) } },
                        label = { Text(stringResource(R.string.label_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) stringResource(R.string.hide) else stringResource(R.string.show)) }
                        }
                    )

                    OutlinedTextField(
                        value = current.totpSecret ?: "",
                        onValueChange = { v -> actions.updateDraft { it.copy(totpSecret = v) } },
                        label = { Text(stringResource(R.string.label_totp_secret)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default,
                    )
                }
            }

            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = current.note,
                        onValueChange = { v -> actions.updateDraft { it.copy(note = v) } },
                        label = { Text(stringResource(R.string.label_note)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                    )
                }
            }

            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Input for websites: pressing IME Done (Enter) adds to list
                    OutlinedTextField(
                        value = websiteInput,
                        onValueChange = { websiteInput = it },
                        label = { Text(stringResource(R.string.label_add_website)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            addWebsiteFromInput()
                            focusManager.clearFocus()
                        })
                    )

                    // websites preview as chips with remove X
                    if (current.websites.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((index, w) in current.websites.withIndex()) {
                                InputChip(true, {}, label = { Text(w)}, modifier = Modifier.padding(vertical = 4.dp),
                                    trailingIcon = {
                                        Box(Modifier.clickable {
                                        actions.updateDraft { d ->
                                                d.copy(websites = d.websites.filterIndexed { i, _ -> i != index })
                                            }
                                        }) {
                                            IconClose()
                                        }
                                    })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}
