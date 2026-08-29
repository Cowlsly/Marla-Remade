package com.vayunmathur.backup

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.backup.platform.BackupViewModel
import com.vayunmathur.backup.ui.DashboardScreen
import com.vayunmathur.backup.ui.OnboardingScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: BackupViewModel) {
    val context = LocalContext.current
    val state = viewModel.state
    val start = if (state.onboarded) Route.Dashboard else Route.Onboarding
    val backStack = rememberNavBackStack<Route>(start)

    // Onboarding ends because the ViewModel now has a key and a destination, not because anything
    // navigated - so the stack follows the flag rather than the other way round. reset() rather than
    // add(): there is no going back to onboarding once a recovery code and a backend exist, and
    // leaving it on the stack would let Back return to it.
    LaunchedEffect(state.onboarded) {
        if (backStack.last() != start) backStack.reset(start)
    }

    // Both screens can choose a backup destination, so the picker is owned here rather than by
    // either one of them.
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
    val mediaPermissionList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    MainNavigation(backStack) {
        entry<Route.Onboarding> {
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
        entry<Route.Dashboard> {
            DashboardScreen(
                state = state,
                onPickFolder = { folderPicker.launch(null) },
                onSetWebDav = viewModel::setWebDavBackend,
                onAppBackupToggle = viewModel::setAppBackupEnabled,
                onFileBackupToggle = viewModel::setFileBackupEnabled,
                onBackupNow = { mediaPermissions.launch(mediaPermissionList) },
                onRestoreNow = viewModel::restoreFilesNow,
                onDismissMessages = viewModel::dismissMessages,
            )
        }
    }
}
