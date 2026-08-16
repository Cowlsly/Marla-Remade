package com.vayunmathur.contacts.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val hiddenAccounts by viewModel.hiddenAccounts.collectAsStateWithLifecycle()
    val isCalendarSyncEnabled by viewModel.isCalendarSyncEnabled.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val simLabels by viewModel.simAccountLabels.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var renameTarget by remember { mutableStateOf<ContactAccount?>(null) }
    var deleteTarget by remember { mutableStateOf<ContactAccount?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            viewModel.setCalendarSyncEnabled(true)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/vcard"),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            VcfUtils.exportContacts(contacts, outputStream)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SettingsPage", "Error exporting contacts", e)
                    }
                }
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            backStack.add(Route.ImportVcf(uris.map { it.toString() }))
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.AddAccountDialog) }) {
                IconAdd()
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.calendar_sync),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val hasCalendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

                ListItem(
                    content = { Text(stringResource(R.string.sync_contacts_calendar)) },
                    trailingContent = {
                        if (hasCalendarPermissions) {
                            Switch(
                                checked = isCalendarSyncEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setCalendarSyncEnabled(enabled)
                                }
                            )
                        } else {
                            Button(onClick = {
                                calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                            }) {
                                Text(stringResource(R.string.grant_calendar_permissions))
                            }
                        }
                    }
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.display),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val showAccountLabels by viewModel.showAccountLabels.collectAsStateWithLifecycle()
                ListItem(
                    content = { Text(stringResource(R.string.show_account_labels)) },
                    trailingContent = {
                        Switch(
                            checked = showAccountLabels,
                            onCheckedChange = { viewModel.setShowAccountLabels(it) }
                        )
                    }
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.backup_and_export),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ListItem(
                    content = { Text(stringResource(R.string.export_contacts)) },
                    trailingContent = {
                        IconButton(onClick = { exportLauncher.launch("contacts.vcf") }) {
                            IconDownload()
                        }
                    }
                )
                HorizontalDivider()
                ListItem(
                    content = { Text(stringResource(R.string.import_vcf_file)) },
                    supportingContent = { Text(stringResource(R.string.import_contacts_from_vcf_files)) },
                    modifier = Modifier.clickable {
                        importLauncher.launch(arrayOf(
                            "text/vcard",
                            "text/x-vcard",
                            "text/directory",
                            "text/x-vcard",
                            "application/vcard",
                            "*/*"
                        ))
                    },
                    trailingContent = {
                        IconArrowDropDown()
                    }
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.visible_accounts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(accounts, key = { "${it.type}|${it.name}" }) { account ->
                val key = "${account.type}|${account.name}"
                val legacyKey = account.name
                val isHidden = key in hiddenAccounts || legacyKey in hiddenAccounts
                val isVisible = !isHidden
                val onDevice = stringResource(R.string.on_device)
                val simLabel = simLabels[key]
                val displayName = simLabel ?: account.name.ifEmpty { onDevice }
                ListItem(
                    content = { Text(displayName) },
                    supportingContent = { Text(account.type) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLocalAccountType(account.type)) {
                                OverflowMenu(icon = { IconMoreVert() }) {
                                    Item(
                                        text = stringResource(UiR.string.rename),
                                        leadingIcon = { IconEdit() },
                                        onClick = { renameTarget = account }
                                    )
                                    Item(
                                        text = stringResource(UiR.string.delete),
                                        leadingIcon = { IconDelete() },
                                        onClick = { deleteTarget = account }
                                    )
                                }
                            }
                            Checkbox(
                                checked = isVisible,
                                onCheckedChange = { viewModel.setAccountVisibility(account, it) }
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        renameTarget?.let { target ->
            var newName by remember(target) { mutableStateOf(target.name) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text(stringResource(R.string.rename_account)) },
                text = {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.account_name)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.renameLocalAccount(target, name) { ok, err ->
                                if (!ok && err == "collision") {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.account_name_exists),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            renameTarget = null
                        }
                    }) { Text(stringResource(UiR.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text(stringResource(UiR.string.cancel))
                    }
                }
            )
        }

        deleteTarget?.let { target ->
            ConfirmDialog(
                title = stringResource(R.string.delete_account),
                message = stringResource(R.string.delete_local_account_confirm),
                confirmLabel = stringResource(UiR.string.delete),
                dismissLabel = stringResource(UiR.string.cancel),
                destructive = true,
                onConfirm = {
                    viewModel.deleteLocalAccount(target)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}
