package com.vayunmathur.passwords.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.vayunmathur.passwords.R
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.passwords.sync.KdbxPasswordHelper
import com.vayunmathur.passwords.sync.KdbxSyncScheduler
import com.vayunmathur.passwords.sync.KdbxSyncSettings
import com.vayunmathur.passwords.util.ImportSource
import com.vayunmathur.passwords.util.KdbxBackupFormat
import com.vayunmathur.passwords.util.PasswordsViewModel
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.passwords.Route>,
    passwordsViewModel: PasswordsViewModel,
    passphrase: String,
) {
    val importing by passwordsViewModel.importing.collectAsState()
    val message by passwordsViewModel.importMessage.collectAsState()

    var selectedSource by remember { mutableStateOf<ImportSource?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedSource?.let { source ->
                passwordsViewModel.importCsv(uri, source)
            }
        }
        selectedSource = null
    }

    Scaffold(Modifier, {
        TopAppBar(
            { Text(stringResource(R.string.title_settings)) },
            navigationIcon = { IconNavigation(backStack) },
            actions = {
                BackupButtons(
                    format = passwordsViewModel.buildBackupFormat(),
                )
            },
        )
    }) { paddingValues ->
        Column(Modifier
            .padding(paddingValues)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp), Arrangement.Top
        ) {

            Text(stringResource(R.string.import_csv_warning))
            Spacer(Modifier.height(16.dp))

            var dropdownExpanded by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { dropdownExpanded = true },
                    enabled = !importing,
                ) {
                    Text(stringResource(R.string.import_passwords))
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    ImportSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(stringResource(source.label)) },
                            onClick = {
                                dropdownExpanded = false
                                selectedSource = source
                                pickLauncher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "text/comma-separated-values"))
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (importing) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }

            KdbxSyncSection()
        }
    }
}

@Composable
private fun KdbxSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by KdbxSyncSettings.enabledFlow(context).collectAsState(false)
    val documentUri by KdbxSyncSettings.documentUriFlow(context).collectAsState("")
    val lastSync by KdbxSyncSettings.lastSyncFlow(context).collectAsState(0L)
    val status by KdbxSyncSettings.statusFlow(context).collectAsState("")
    val error by KdbxSyncSettings.errorFlow(context).collectAsState("")

    // Re-read on every recomposition rather than remembering: the dialog below writes it.
    var passwordSaved by remember { mutableStateOf(KdbxPasswordHelper(context).isKeyGenerated()) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var fileMenuExpanded by remember { mutableStateOf(false) }

    fun persistPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        scope.launch { KdbxSyncSettings.setDocumentUri(context, uri.toString()) }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::persistPermission)
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(::persistPermission) }

    Spacer(Modifier.height(24.dp))

    SettingsSection(title = stringResource(R.string.sync_section)) {
        SettingsSwitchRow(
            title = stringResource(R.string.sync_enable),
            supportingText = stringResource(R.string.sync_enable_description),
            checked = enabled,
            onCheckedChange = { checked ->
                scope.launch {
                    KdbxSyncSettings.setEnabled(context, checked)
                    if (checked) {
                        KdbxSyncScheduler.schedulePeriodic(context)
                        KdbxSyncScheduler.syncNow(context)
                    } else {
                        KdbxSyncScheduler.cancel(context)
                    }
                }
            },
        )
        SettingsDivider()

        Box {
            SettingsRow(
                title = stringResource(R.string.sync_file),
                supportingText = documentUri.ifBlank { stringResource(R.string.sync_file_none) },
                onClick = { fileMenuExpanded = true },
            )
            DropdownMenu(
                expanded = fileMenuExpanded,
                onDismissRequest = { fileMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_choose_file)) },
                    onClick = {
                        fileMenuExpanded = false
                        openLauncher.launch(arrayOf("application/octet-stream", "application/x-keepass", "*/*"))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_create_file)) },
                    onClick = {
                        fileMenuExpanded = false
                        createLauncher.launch("passwords.kdbx")
                    },
                )
            }
        }
        SettingsDivider()

        SettingsRow(
            title = stringResource(R.string.sync_vault_password),
            supportingText = stringResource(
                if (passwordSaved) R.string.sync_vault_password_set else R.string.sync_vault_password_unset,
            ),
            onClick = { showPasswordDialog = true },
        )
        SettingsDivider()

        SettingsRow(
            title = stringResource(R.string.sync_last),
            supportingText = syncStatusText(lastSync, status, error),
        )
        SettingsDivider()

        SettingsRow(
            title = stringResource(R.string.sync_now),
            enabled = enabled,
            onClick = { KdbxSyncScheduler.syncNow(context) },
        )
    }

    if (showPasswordDialog) {
        VaultPasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onSave = { value ->
                KdbxPasswordHelper(context).storePassphrase(value)
                passwordSaved = true
                showPasswordDialog = false
            },
        )
    }
}

@Composable
private fun syncStatusText(lastSync: Long, status: String, error: String): String {
    val context = LocalContext.current
    val when_ = if (lastSync == 0L) {
        stringResource(R.string.sync_never)
    } else {
        DateFormat.getMediumDateFormat(context).format(Date(lastSync)) + " " +
            DateFormat.getTimeFormat(context).format(Date(lastSync))
    }
    if (status != KdbxSyncSettings.STATUS_ERROR) return when_
    val reason = when (error) {
        "file_missing" -> stringResource(R.string.sync_error_file_missing)
        "wrong_password" -> stringResource(R.string.sync_error_wrong_password)
        "verify_failed" -> stringResource(R.string.sync_error_verify_failed)
        else -> stringResource(R.string.sync_error_generic, error)
    }
    return "$when_\n$reason"
}

@Composable
private fun VaultPasswordDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_vault_password)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.sync_vault_password)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotEmpty()) {
                Text(stringResource(R.string.sync_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_cancel)) }
        },
    )
}
