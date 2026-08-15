package com.vayunmathur.files.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.HomeUiState
import com.vayunmathur.library.ui.SnackbarHostState

@Composable
fun HomeScreenBinder(viewModel: FilesViewModel, onOpenDrawer: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val storage by viewModel.storage.collectAsState()
    val recents by viewModel.recents.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()

    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.intents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                viewModel.showMessage(resources.getString(R.string.no_app_found_to_open_file))
            }
        }
    }

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onInstallPermissionResult()
        }
    LaunchedEffect(Unit) {
        viewModel.installPermissionRequests.collect {
            installPermissionLauncher.launch(unknownAppSourcesSettings(context))
        }
    }

    HomeScreen(
        home = HomeUiState(
            rootDisplayName = Build.MODEL,
            storage = storage,
            recents = recents,
            bookmarks = bookmarks,
        ),
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onOpenDrawer = onOpenDrawer,
    )
}


