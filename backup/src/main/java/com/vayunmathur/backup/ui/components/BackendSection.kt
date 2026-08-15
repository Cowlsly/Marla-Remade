package com.vayunmathur.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/** Human-readable summary of the currently selected destination. */
fun BackupSettings.destinationLabel(): String = when (backendType) {
    BackendType.NONE -> "No destination selected"
    BackendType.SAF -> "Folder: " + (safTreeUri?.substringAfterLast(':')?.substringAfterLast('/') ?: "selected")
    BackendType.WEBDAV -> "WebDAV: $webdavUrl"
}

/** Lets the user pick a SAF folder or configure a WebDAV/Nextcloud remote. */
@Composable
fun BackendSection(
    settings: BackupSettings,
    onPickFolder: () -> Unit,
    onSetWebDav: (url: String, user: String, password: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Backup destination", style = MaterialTheme.typography.titleMedium)
            Text(settings.destinationLabel(), style = MaterialTheme.typography.bodyMedium)

            Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Choose folder (USB / SD / internal)")
            }

            HorizontalDivider()
            Text("or a WebDAV / Nextcloud remote", style = MaterialTheme.typography.labelLarge)

            var url by remember(settings.webdavUrl) { mutableStateOf(settings.webdavUrl) }
            var user by remember(settings.webdavUser) { mutableStateOf(settings.webdavUser) }
            var password by remember { mutableStateOf("") }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebDAV URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSetWebDav(url, user, password) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save WebDAV destination") }
        }
    }
}
