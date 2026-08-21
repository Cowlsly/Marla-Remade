package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.ui.components.EuiccSection
import com.vayunmathur.euicc.ui.components.NotificationsSection
import com.vayunmathur.euicc.ui.components.ProfilesSection
import com.vayunmathur.euicc.ui.dialogs.DownloadDialog
import com.vayunmathur.euicc.ui.dialogs.RenameDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

@Composable
fun EuiccScreen(viewModel: EuiccViewModel) {
    EuiccScreen(
        state = viewModel.state,
        onReload = viewModel::reload,
        onDownload = viewModel::downloadProfile,
        onEnable = { viewModel.enable(it.iccid) },
        onDisable = { viewModel.disable(it.iccid) },
        onRename = { profile, name -> viewModel.rename(profile.iccid, name) },
        onDelete = { viewModel.delete(it.iccid) },
        onRemoveNotification = { viewModel.removeNotification(it.seqNumber) },
    )
}

@Composable
fun EuiccScreen(
    state: EuiccScreenState,
    onReload: () -> Unit,
    onDownload: (String) -> Unit,
    onEnable: (Profile) -> Unit,
    onDisable: (Profile) -> Unit,
    onRename: (Profile, String) -> Unit,
    onDelete: (Profile) -> Unit,
    onRemoveNotification: (Notification) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    AppScaffold(
        title = "EUICC",
        actions = {
            IconButton(onClick = { showDownload = true }, enabled = !state.loading) { IconAdd() }
            IconButton(onClick = onReload, enabled = !state.loading) { IconRefresh() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            ProfilesSection(profiles = state.profiles, onEnable = onEnable, onDisable = onDisable, onRename = { renameTarget = it }, onDelete = { deleteTarget = it })
            NotificationsSection(notifications = state.notifications, onRemove = onRemoveNotification)
            EuiccSection(eid = state.eid, info = state.info)
        }
    }
    renameTarget?.let { profile ->
        RenameDialog(profile = profile, onConfirm = { name -> onRename(profile, name); renameTarget = null }, onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { profile ->
        ConfirmDialog(title = "Delete " + profile.displayName + "?", message = "This permanently removes the profile from the eUICC.", confirmLabel = "Delete", destructive = true, onConfirm = { onDelete(profile); deleteTarget = null }, onDismiss = { deleteTarget = null })
    }
    if (showDownload) {
        DownloadDialog(onDownload = { onDownload(it); showDownload = false }, onDismiss = { showDownload = false })
    }
}
