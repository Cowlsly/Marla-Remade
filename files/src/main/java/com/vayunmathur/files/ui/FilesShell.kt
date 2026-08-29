package com.vayunmathur.files.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FileCategory
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.ui.*
import kotlinx.coroutines.launch
import java.io.File


/**
 * The chrome that is the same on every destination: the all-files permission gate, the notification
 * prompt, and the navigation drawer.
 *
 * Wraps the nav host rather than living inside it, so the drawer is not rebuilt on every navigation
 * and stays open across one. [content] is handed the callback that opens it, because the drawer state
 * belongs here but the buttons that open it are on the individual screens.
 */
@Composable
fun FilesShell(
    viewModel: FilesViewModel,
    actions: FilesActions,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val isFilesGranted by viewModel.isFilesGranted.collectAsState()
    val hasPromptedNotifications by viewModel.hasPromptedNotifications.collectAsState()
    var showNotificationDialog by remember { mutableStateOf(false) }

    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissions()
        }

    val notificationsRequest =
        rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) {
            viewModel.setNotificationsPrompted()
            showNotificationDialog = false
        }

    LaunchedEffect(isFilesGranted, hasPromptedNotifications) {
        if (isFilesGranted && !hasPromptedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                showNotificationDialog = true
            } else {
                viewModel.setNotificationsPrompted()
            }
        }
    }

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.setNotificationsPrompted()
                showNotificationDialog = false
            },
            title = { Text(stringResource(R.string.enable_notifications)) },
            text = { Text(stringResource(R.string.notification_permission_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationsRequest()
                        }
                    }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.setNotificationsPrompted()
                        showNotificationDialog = false
                    }) { Text(stringResource(R.string.skip)) }
            })
    }

    if (!isFilesGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts(
                                "package", context.packageName, null
                            )
                        }
                    filesLauncher.launch(intent)
                }) { Text(stringResource(R.string.grant_all_files_access)) }
        }
    } else {
        val bookmarks by viewModel.bookmarks.collectAsState()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    FilesDrawer(
                        bookmarks = bookmarks,
                        actions = actions,
                        closeDrawer = { scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            content { scope.launch { drawerState.open() } }
        }
    }
}


/** The slide-out navigation drawer: jump to any page without going Home first. */
@Composable
private fun FilesDrawer(
    bookmarks: List<FileBrowserItem>,
    actions: FilesActions,
    closeDrawer: () -> Unit,
) {
    fun go(action: () -> Unit) { action(); closeDrawer() }
    Column(Modifier.padding(12.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.home)) },
            icon = { IconHome() },
            selected = false,
            onClick = { go { actions.goHome() } },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.internal_storage)) },
            icon = { IconFolder() },
            selected = false,
            onClick = { go { actions.openInternalStorage() } },
        )
        HomeSectionHeader(stringResource(R.string.categories))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.cat_images)) },
            icon = { IconImage(tint = COLOR_IMAGE) },
            selected = false,
            onClick = { go { actions.openCategory(FileCategory.IMAGES) } },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.cat_videos)) },
            icon = { IconVideoCamera(tint = COLOR_VIDEO) },
            selected = false,
            onClick = { go { actions.openCategory(FileCategory.VIDEOS) } },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.cat_audio)) },
            icon = { IconLibraryMusic(tint = COLOR_AUDIO) },
            selected = false,
            onClick = { go { actions.openCategory(FileCategory.AUDIO) } },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.cat_documents)) },
            icon = { IconDescription(tint = COLOR_DOC) },
            selected = false,
            onClick = { go { actions.openCategory(FileCategory.DOCUMENTS) } },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.cat_downloads)) },
            icon = { IconDownload(tint = COLOR_APK) },
            selected = false,
            onClick = { go { actions.openCategory(FileCategory.DOWNLOADS) } },
        )
        if (bookmarks.isNotEmpty()) {
            HomeSectionHeader(stringResource(R.string.bookmarks))
            bookmarks.forEach { bm ->
                NavigationDrawerItem(
                    label = { Text(bm.name) },
                    icon = { IconStar() },
                    selected = false,
                    onClick = { go { bm.realFile?.let { actions.openBookmark(it) } } },
                )
            }
        }
    }
}

/**
 * The "install unknown apps" settings screen for this package, so the user can grant Files
 * permission to install APKs. Scoped to our package via the `package:` URI.
 */
internal fun unknownAppSourcesSettings(context: android.content.Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

internal fun fileAncestors(from: File?, upTo: File?): List<File> = buildList {
    var p = from
    while (p != null) {
        add(0, p)
        if (upTo != null && p.absolutePath == upTo.absolutePath) break
        p = p.parentFile
    }
}

internal data class Crumb(
    val displayName: String,
    val realFile: File?,
    val zipInternalPath: String?, // non-null = zip mode crumb
)

fun dropTarget(
    onDragStateChange: (Boolean) -> Unit,
    onDrop: (List<File>) -> Unit
) = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        onDragStateChange(false)
        val clipData = event.toAndroidDragEvent().clipData ?: return false
        if (clipData.itemCount == 0) return false
        val files = (0 until clipData.itemCount).mapNotNull {
            val txt = clipData.getItemAt(it).text?.toString() ?: return@mapNotNull null
            val f = File(txt)
            if (f.exists()) f else null
        }
        if (files.isEmpty()) return false
        onDrop(files)
        return true
    }
    override fun onEntered(event: DragAndDropEvent) { onDragStateChange(true) }
    override fun onExited(event: DragAndDropEvent) { onDragStateChange(false) }
    override fun onEnded(event: DragAndDropEvent) { onDragStateChange(false) }
}


