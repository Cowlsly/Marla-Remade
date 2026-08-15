package com.vayunmathur.files.ui

import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.ui.SnackbarHostState
import java.io.File

/** Binds [FilesViewModel] to the stateless [DirectoryScreen]. */
@Composable
fun DirectoryPage(viewModel: FilesViewModel, onOpenDrawer: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentDirectory by viewModel.currentDirectory.collectAsState()
    val zipPath by viewModel.zipPath.collectAsState()
    val zipInternalPath by viewModel.zipInternalPath.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val incomingUris by viewModel.incomingUris.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()
    val clipboardIsCut by viewModel.clipboardIsCut.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val categoryTitle by viewModel.categoryTitle.collectAsState()

    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
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

    val zipToUnzip = remember(selectedPaths) {
        selectedPaths.singleOrNull()?.takeIf {
            !it.isDirectory && it.realFile != null && it.name.endsWith(".zip", ignoreCase = true)
        }
    }

    // The "unzip here" folder picker needs an Activity, so it is launched from here rather
    // than from the screen.
    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && zipToUnzip != null) {
                val path = uri.path?.split(":")?.lastOrNull()?.let {
                    File(Environment.getExternalStorageDirectory(), it)
                } ?: currentDirectory
                viewModel.unzip(zipToUnzip, path)
            }
        }

    val (directories, files) = entries

    DirectoryScreen(
        state = FilesUiState(
            rootDirectory = viewModel.rootDirectory,
            rootDisplayName = Build.MODEL,
            currentDirectory = currentDirectory,
            zipPath = zipPath,
            zipInternalPath = zipInternalPath,
            directories = directories,
            files = files,
            selectedPaths = selectedPaths,
            hasIncomingUris = incomingUris != null,
            sortBy = sortBy,
            sortAscending = sortAscending,
            viewMode = viewMode,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
            clipboardCount = clipboard.size,
            clipboardIsCut = clipboardIsCut,
            showHidden = showHidden,
            categoryTitle = categoryTitle,
        ),
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onPickUnzipDestination = { treeLauncher.launch(null) },
        onOpenDrawer = onOpenDrawer,
    )
}


