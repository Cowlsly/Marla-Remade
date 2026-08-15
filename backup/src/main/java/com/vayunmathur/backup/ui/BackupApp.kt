package com.vayunmathur.backup.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.backup.platform.BackupUiState
import com.vayunmathur.backup.platform.BackupViewModel

/**
 * Root of the Backup UI: routes to onboarding until a recovery code and a destination
 * are set, then to the dashboard. Owns the SAF folder picker so both flows can select
 * a backup destination.
 */
@Composable
fun BackupApp(viewModel: BackupViewModel) {
    val context = LocalContext.current
    val state = viewModel.state

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setSafBackend(uri.toString())
        }
    }

    val mediaPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.backupFilesNow() }

    if (state.onboarded) {
        DashboardScreen(
            state = state,
            onPickFolder = { folderPicker.launch(null) },
            onSetWebDav = viewModel::setWebDavBackend,
            onAppBackupToggle = viewModel::setAppBackupEnabled,
            onFileBackupToggle = viewModel::setFileBackupEnabled,
            onBackupNow = { mediaPermissions.launch(mediaPermissionList()) },
            onRestoreNow = viewModel::restoreFilesNow,
            onDismissMessages = viewModel::dismissMessages,
        )
    } else {
        OnboardingScreen(
            state = state,
            onPickFolder = { folderPicker.launch(null) },
            onSetWebDav = viewModel::setWebDavBackend,
            onGenerate = viewModel::generateRecoveryCode,
            onConfirmNew = viewModel::confirmNewCode,
            onRestoreWithCode = viewModel::restoreWithCode,
            onDismissMessages = viewModel::dismissMessages,
        )
    }
}

private fun mediaPermissionList(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
