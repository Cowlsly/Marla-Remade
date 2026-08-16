package com.vayunmathur.passwords.ui

import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.platform.PasswordsViewModel
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeyPage(
    backStack: NavBackStack<Route>,
    id: Long,
    viewModel: PasswordsViewModel,
) {
    val passkeys by viewModel.passkeys.collectAsState()
    val passkey = passkeys.firstOrNull { it.id == id }

    if (passkey == null) {
        return
    }

    val context = LocalContext.current
    val mediumDate = DateFormat.getMediumDateFormat(context)
    val shortTime = DateFormat.getTimeFormat(context)
    val dateFormat = { ms: Long -> mediumDate.format(Date(ms)) + " " + shortTime.format(Date(ms)) }

    DetailScaffold(
        title = passkey.rpName.ifBlank { stringResource(R.string.passkey_detail_title) },
        backStack = backStack,
        actions = {
            IconButton(onClick = { viewModel.deletePasskey(passkey); backStack.pop() }) {
                IconDelete()
            }
        },
    ) {
        DetailCard(stringResource(R.string.passkey_rp_name), passkey.rpName)
        DetailCard(stringResource(R.string.passkey_rp_id), passkey.rpId)
        DetailCard(stringResource(R.string.passkey_user_name), passkey.userName)
        DetailCard(stringResource(R.string.passkey_user_display_name), passkey.userDisplayName)
        DetailCard(
            stringResource(R.string.passkey_credential_id),
            passkey.credentialId.let { if (it.length > 20) it.take(20) + "…" else it }
        )
        DetailCard(stringResource(R.string.passkey_created), dateFormat(passkey.creationTime))
        DetailCard(stringResource(R.string.passkey_last_used), dateFormat(passkey.lastUsedTime))
    }
}

@Composable
private fun DetailCard(label: String, value: String) {
    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
