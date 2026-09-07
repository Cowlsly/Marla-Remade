package com.vayunmathur.library.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.library.util.DbBackupCodec
import com.vayunmathur.library.util.ZipBackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

@Composable
fun BackupButtons(
    dbConfigs: List<Pair<String, String>> = emptyList(),
    datastoreNames: List<String> = emptyList(),
    prefNames: List<String> = emptyList(),
    extraFiles: List<File> = emptyList(),
    extraFilesMapping: Map<String, File> = extraFiles.associateBy { it.name },
    dbCodec: DbBackupCodec? = null,
) {
    BackupButtons(
        format = ZipBackupFormat(dbConfigs, datastoreNames, prefNames, extraFiles, extraFilesMapping, dbCodec)
    )
}

@Composable
fun BackupButtons(format: BackupFormat) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showPasswordDialog by remember { mutableStateOf<PasswordDialogAction?>(null) }
    var passwordText by remember { mutableStateOf("") }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format.mimeType)
    ) { uri ->
        uri?.let {
            if (format.needsPassword) {
                pendingExportUri = it
                passwordText = ""
                showPasswordDialog = PasswordDialogAction.EXPORT
            } else {
                scope.launch(Dispatchers.IO) {
                    runExport(context, format, null, it)
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (format.needsPassword) {
                pendingImportUri = it
                passwordText = ""
                showPasswordDialog = PasswordDialogAction.IMPORT
            } else {
                scope.launch(Dispatchers.IO) {
                    runImport(context, format, null, it)
                }
            }
        }
    }

    if (showPasswordDialog != null) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = null },
            title = { Text(if (showPasswordDialog == PasswordDialogAction.EXPORT) stringResource(R.string.backup_dialog_title_export) else stringResource(R.string.backup_dialog_title_import)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_password_prompt))
                    TextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pw = passwordText
                        val action = showPasswordDialog
                        showPasswordDialog = null
                        scope.launch(Dispatchers.IO) {
                            when (action) {
                                PasswordDialogAction.EXPORT -> pendingExportUri?.let { uri ->
                                    runExport(context, format, pw, uri)
                                    pendingExportUri = null
                                }
                                PasswordDialogAction.IMPORT -> pendingImportUri?.let { uri ->
                                    runImport(context, format, pw, uri)
                                    pendingImportUri = null
                                }
                                null -> {}
                            }
                        }
                    },
                    enabled = passwordText.isNotEmpty()
                ) { Text(stringResource(R.string.backup_dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = null }) { Text(stringResource(R.string.link_action_cancel)) }
            }
        )
    }

    IconButton(onClick = { exportLauncher.launch(format.defaultFileName) }) {
        IconBackup()
    }
    IconButton(onClick = { importLauncher.launch(arrayOf(format.mimeType)) }) {
        IconRestore()
    }
}

private enum class PasswordDialogAction { EXPORT, IMPORT }

/** Long enough for the import message to be read before the process goes away. */
private const val RESTART_DELAY_MS = 1500L

private fun relaunch(context: android.content.Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
    }
    Runtime.getRuntime().exit(0)
}

private suspend fun runExport(
    context: android.content.Context,
    format: BackupFormat,
    password: String?,
    uri: android.net.Uri
) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            format.export(context, password, os)
        }
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(R.string.backup_export_success))
        }
    } catch (e: Exception) {
        Log.e("BackupButtons", "Export FAILED", e)
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(R.string.backup_export_failed_format, e.message))
        }
    }
}

private suspend fun runImport(
    context: android.content.Context,
    format: BackupFormat,
    password: String?,
    uri: android.net.Uri
) {
    try {
        context.contentResolver.openInputStream(uri)?.use { isStream ->
            format.import(context, password, isStream)
        }
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(R.string.backup_import_success))
        }
        // DataStore and Room assume exclusive ownership of their files and keep the contents
        // cached, so a live process neither picks up the files the import just replaced nor stops
        // writing the pre-restore state back over them - which silently undid the restore before
        // the user got round to restarting (#548). Give the message a moment, then relaunch.
        delay(RESTART_DELAY_MS)
        withContext(Dispatchers.Main) { relaunch(context) }
    } catch (e: Exception) {
        Log.e("BackupButtons", "Import FAILED", e)
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(R.string.backup_import_failed_format, e.message))
        }
    }
}
