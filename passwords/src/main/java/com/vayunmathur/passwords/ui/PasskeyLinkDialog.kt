package com.vayunmathur.passwords.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.Password

/** Picks the password entry a passkey should be linked to. */
@Composable
fun PasskeyLinkDialog(
    passwords: List<Password>,
    onDismiss: () -> Unit,
    onSelect: (Password) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkey_link_dialog_title)) },
        text = {
            if (passwords.isEmpty()) {
                Text(stringResource(R.string.passkey_link_dialog_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(passwords, key = { it.id }) { password ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(password) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                password.name.ifBlank { stringResource(R.string.no_name) },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = password.username.ifBlank { password.email }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
