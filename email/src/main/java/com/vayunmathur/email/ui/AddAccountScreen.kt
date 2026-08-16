package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.email.data.PROVIDER_CUSTOM
import com.vayunmathur.email.data.PROVIDER_OUTLOOK
import com.vayunmathur.email.data.PROVIDER_PRESETS
import com.vayunmathur.email.data.ProviderPreset
import com.vayunmathur.library.ui.IconNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add-account flow: OAuth for Outlook (Microsoft), app password for others.
 * Raw IMAP/SMTP via [com.vayunmathur.email.imap]/[com.vayunmathur.email.smtp].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onBack: (() -> Unit)?,
    onAccountAdded: () -> Unit,
) {
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProvider = selectedProviderId?.let { id -> PROVIDER_PRESETS.firstOrNull { it.id == id } }

    androidx.activity.compose.BackHandler(enabled = selectedProvider != null) { selectedProviderId = null }

    AppScaffold(
        title = if (selectedProvider == null) stringResource(R.string.add_account) else selectedProvider.displayName,
        navigationIcon = {
            val backTarget: (() -> Unit)? = when {
                selectedProvider != null -> ({ selectedProviderId = null })
                else -> onBack
            }
            if (backTarget != null) { IconNavigation(backTarget) }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedProvider == null) {
                ProviderPicker(onPick = { selectedProviderId = it.id })
            } else if (selectedProvider.id == PROVIDER_OUTLOOK) {
                OutlookOAuthForm(
                    preset = selectedProvider,
                    onAccountAdded = onAccountAdded,
                )
            } else {
                PasswordForm(
                    preset = selectedProvider,
                    onAccountAdded = onAccountAdded,
                )
            }
        }
    }
}

@Composable
private fun ProviderPicker(onPick: (ProviderPreset) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(stringResource(R.string.choose_your_email_provider), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        PROVIDER_PRESETS.forEach { preset ->
            ElevatedCard(onClick = { onPick(preset) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            preset.id == PROVIDER_CUSTOM -> stringResource(R.string.enter_imap_smtp_server_details_manually)
                            preset.id == PROVIDER_OUTLOOK -> stringResource(R.string.oauth_sign_in)
                            else -> stringResource(R.string.app_password)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlookOAuthForm(
    preset: ProviderPreset,
    onAccountAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountAddedMsg = stringResource(R.string.account_added)
    var emailHint by rememberSaveable { mutableStateOf("") }
    var useAppPassword by rememberSaveable { mutableStateOf(false) }
    var appPassword by rememberSaveable { mutableStateOf("") }
    var appPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sign_in_with, preset.displayName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                preset.instructions.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                Text("Client: ${com.vayunmathur.email.BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.take(12)}… Redirect: com.vayunmathur.email://oauth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = emailHint,
            onValueChange = { emailHint = it.trim() },
            label = { Text(stringResource(R.string.email_address_optional)) },
            placeholder = { Text("Optional — pre-fills Microsoft login") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { OutlookOAuth.start(context, emailHint) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sign_in_with, preset.displayName))
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useAppPassword = !useAppPassword }) {
            Checkbox(checked = useAppPassword, onCheckedChange = { useAppPassword = it })
            Text(stringResource(R.string.use_app_password_instead), style = MaterialTheme.typography.bodyMedium)
        }

        if (useAppPassword) {
            OutlinedTextField(
                value = appPassword,
                onValueChange = { appPassword = it },
                label = { Text(stringResource(R.string.app_password)) },
                singleLine = true,
                visualTransformation = if (appPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { appPasswordVisible = !appPasswordVisible }) {
                        Text(if (appPasswordVisible) stringResource(R.string.hide) else stringResource(R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                enabled = !working && emailHint.isNotBlank() && appPassword.isNotBlank(),
                onClick = {
                    error = null; working = true
                    scope.launch {
                        val result = testAndPersistAccount(
                            context = context,
                            providerId = preset.id,
                            email = emailHint,
                            username = "",
                            password = appPassword,
                            imap = preset.imap ?: ServerConfig("outlook.office365.com", 993, true),
                            smtp = preset.smtp ?: ServerConfig("smtp-mail.outlook.com", 587, false),
                        )
                        working = false
                        if (result == null) {
                            AppMessages.show(accountAddedMsg); onAccountAdded()
                        } else error = result
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.testing_connection))
                } else Text(stringResource(R.string.test_connection_and_save))
            }
        }

        preset.appPasswordHelpUrl?.let { url ->
            TextButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.open_app_password_help, preset.displayName)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordForm(
    preset: ProviderPreset,
    onAccountAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountAddedMsg = stringResource(R.string.account_added)

    var email by rememberSaveable { mutableStateOf("") }
    var useDifferentUsername by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var imapHost by rememberSaveable { mutableStateOf(preset.imap?.host ?: "") }
    var imapPort by rememberSaveable { mutableStateOf((preset.imap?.port ?: 993).toString()) }
    var imapUseSsl by rememberSaveable { mutableStateOf(preset.imap?.useSsl ?: true) }
    var smtpHost by rememberSaveable { mutableStateOf(preset.smtp?.host ?: "") }
    var smtpPort by rememberSaveable { mutableStateOf((preset.smtp?.port ?: 465).toString()) }
    var smtpUseSsl by rememberSaveable { mutableStateOf(preset.smtp?.useSsl ?: true) }

    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preset.instructions.isNotEmpty()) { InstructionsCard(preset = preset) }

        OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text(stringResource(R.string.email_address)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        if (preset.id == PROVIDER_CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useDifferentUsername = !useDifferentUsername }) {
                Checkbox(checked = useDifferentUsername, onCheckedChange = { useDifferentUsername = it })
                Text(stringResource(R.string.username_is_not_my_email), style = MaterialTheme.typography.bodyMedium)
            }
            if (useDifferentUsername) {
                OutlinedTextField(value = username, onValueChange = { username = it.trim() }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (preset.id == PROVIDER_CUSTOM) stringResource(R.string.password) else stringResource(R.string.app_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) stringResource(R.string.hide) else stringResource(R.string.show)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (preset.id == PROVIDER_CUSTOM) {
            Text(stringResource(R.string.imap_incoming), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ServerRow(host = imapHost, onHostChange = { imapHost = it.trim() }, port = imapPort, onPortChange = { imapPort = it.filter(Char::isDigit) }, useSsl = imapUseSsl, onSslChange = { imapUseSsl = it })
            Text(stringResource(R.string.smtp_outgoing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ServerRow(host = smtpHost, onHostChange = { smtpHost = it.trim() }, port = smtpPort, onPortChange = { smtpPort = it.filter(Char::isDigit) }, useSsl = smtpUseSsl, onSslChange = { smtpUseSsl = it })
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            enabled = !working && email.isNotBlank() && password.isNotBlank() && (preset.id != PROVIDER_CUSTOM || (imapHost.isNotBlank() && smtpHost.isNotBlank() && imapPort.isNotBlank() && smtpPort.isNotBlank())),
            onClick = {
                error = null; working = true
                scope.launch {
                    val imap = preset.imap ?: ServerConfig(imapHost, imapPort.toIntOrNull() ?: 993, imapUseSsl)
                    val smtp = preset.smtp ?: ServerConfig(smtpHost, smtpPort.toIntOrNull() ?: 465, smtpUseSsl)
                    val result = testAndPersistAccount(context = context, providerId = preset.id, email = email, username = if (useDifferentUsername) username else "", password = password, imap = imap, smtp = smtp)
                    working = false
                    if (result == null) { AppMessages.show(accountAddedMsg); onAccountAdded() } else error = result
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.testing_connection))
            } else Text(stringResource(R.string.test_connection_and_save))
        }
    }
}

@Composable
private fun InstructionsCard(preset: ProviderPreset) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.how_to_get_your_app_password), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            preset.instructions.forEachIndexed { i, line -> Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall) }
            preset.appPasswordHelpUrl?.let { url ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.open_app_password_help, preset.displayName)) }
            }
        }
    }
}

@Composable
private fun ServerRow(host: String, onHostChange: (String) -> Unit, port: String, onPortChange: (String) -> Unit, useSsl: Boolean, onSslChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = host, onValueChange = onHostChange, label = { Text(stringResource(R.string.host_label)) }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text(stringResource(R.string.port_label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.security), modifier = Modifier.padding(end = 8.dp))
        FilterChip(selected = useSsl, onClick = { onSslChange(true) }, label = { Text(stringResource(R.string.ssl_tls)) })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = !useSsl, onClick = { onSslChange(false) }, label = { Text(stringResource(R.string.starttls)) })
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}

private suspend fun testAndPersistAccount(
    context: Context,
    providerId: String,
    email: String,
    username: String,
    password: String,
    imap: ServerConfig,
    smtp: ServerConfig,
): String? = withContext(Dispatchers.IO) {
    val loginUser = username.ifBlank { email }
    try {
        EmailManager().fetchFolders(server = imap, user = loginUser, auth = EmailManager.AuthType.Password(password))
    } catch (e: Exception) {
        val msg = e.message?.lowercase() ?: ""
        val isAuth = e is com.vayunmathur.email.network.imap.ImapAuthException || msg.contains("auth") && (msg.contains("failed") || msg.contains("invalid") || msg.contains("no") || msg.contains("login"))
        if (isAuth) return@withContext "Authentication failed — check your email and app password."
        return@withContext "Couldn't reach ${imap.host}:${imap.port} — ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
    }
    val (cipher, iv) = try { CredentialCrypto.encrypt(password) } catch (e: Exception) { return@withContext "Couldn't store password: ${e.message}" }
    val account = EmailAccount(
        email = email,
        username = username,
        provider = providerId,
        imapHost = imap.host,
        imapPort = imap.port,
        imapUseSsl = imap.useSsl,
        smtpHost = smtp.host,
        smtpPort = smtp.port,
        smtpUseSsl = smtp.useSsl,
        authType = "password",
        passwordEncrypted = cipher,
        passwordIv = iv,
    )
    EmailRepository.get(context).insertAccount(account)
    EmailSyncWorker.scheduleHourlyNonInboxSync(context)
    EmailSyncWorker.runOneOffSync(context)
    ImapIdleService.start(context)
    null
}
