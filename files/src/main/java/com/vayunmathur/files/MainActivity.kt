package com.vayunmathur.files

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FileCategory
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.HomeUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.StorageInfo
import com.vayunmathur.files.platform.ViewMode
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNewFile
import com.vayunmathur.library.ui.IconNewFolder
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: FilesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent { DynamicTheme { HomeDirectoryPage(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeIf { it.type != null } ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(it) }
        }
    }
}

@Composable
fun HomeDirectoryPage(viewModel: FilesViewModel) {
    val context = LocalContext.current
    val isFilesGranted by viewModel.isFilesGranted.collectAsState()
    val hasPromptedNotifications by viewModel.hasPromptedNotifications.collectAsState()
    var showNotificationDialog by remember { mutableStateOf(false) }

    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissions()
        }

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
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
                            notificationsLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
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
        val atHome by viewModel.atHome.collectAsState()
        val bookmarks by viewModel.bookmarks.collectAsState()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    FilesDrawer(
                        bookmarks = bookmarks,
                        actions = viewModel,
                        closeDrawer = { scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            if (atHome) {
                HomeScreenBinder(viewModel) { scope.launch { drawerState.open() } }
            } else {
                DirectoryPage(viewModel) { scope.launch { drawerState.open() } }
            }
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
        SectionHeader(stringResource(R.string.categories))
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
            SectionHeader(stringResource(R.string.bookmarks))
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
private fun unknownAppSourcesSettings(context: android.content.Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

private fun fileAncestors(from: File?, upTo: File?): List<File> = buildList {
    var p = from
    while (p != null) {
        add(0, p)
        if (upTo != null && p.absolutePath == upTo.absolutePath) break
        p = p.parentFile
    }
}

private data class Crumb(
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

// ---- File type / thumbnail helpers ----

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts")
private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "mid", "amr")
private val DOC_EXTS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv", "odt")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val CODE_EXTS = setOf("kt", "java", "c", "cpp", "h", "py", "js", "ts", "html", "css", "json", "xml", "sh", "rs", "go")

private val COLOR_IMAGE = Color(0xFF4CAF50)
private val COLOR_VIDEO = Color(0xFF9C27B0)
private val COLOR_AUDIO = Color(0xFFFF9800)
private val COLOR_DOC = Color(0xFF2196F3)
private val COLOR_ARCHIVE = Color(0xFFB28500)
private val COLOR_APK = Color(0xFF009688)
private val COLOR_CODE = Color(0xFF607D8B)

/** Leading visual for a browser item: an image thumbnail, or a type-colored icon. */
@Composable
private fun FileLeading(item: FileBrowserItem, isSelected: Boolean, sizeDp: Dp) {
    val ext = item.name.substringAfterLast('.', "").lowercase()
    if (!item.isDirectory && item.realFile != null && ext in IMAGE_EXTS) {
        AsyncImage(
            model = item.realFile,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        return
    }
    val folderTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    when {
        item.isDirectory -> IconFolder(tint = folderTint)
        ext in VIDEO_EXTS -> IconVideoCamera(tint = COLOR_VIDEO)
        ext in AUDIO_EXTS -> IconLibraryMusic(tint = COLOR_AUDIO)
        ext in DOC_EXTS -> IconDescription(tint = COLOR_DOC)
        ext in ARCHIVE_EXTS -> IconArchive(tint = COLOR_ARCHIVE)
        ext == "apk" -> IconPackage(tint = COLOR_APK)
        ext in CODE_EXTS -> IconCode(tint = COLOR_CODE)
        ext in IMAGE_EXTS -> IconImage(tint = COLOR_IMAGE)
        else -> IconFile(tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

/**
 * The directory browser, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    state: FilesUiState,
    actions: FilesActions,
    /** Owned by the binder, which is where the ViewModel's messages arrive. */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** Opens the system folder picker for "unzip here"; needs an Activity. */
    onPickUnzipDestination: () -> Unit = {},
    /** Opens the navigation drawer. */
    onOpenDrawer: () -> Unit = {},
) {
    val isReadOnly = state.zipPath != null
    val isCategory = state.categoryTitle != null
    // A category view lists files gathered from across storage, so structural edits
    // (new/paste/move/rename/archive) that target "here" don't apply.
    val canModifyHere = !isReadOnly && !isCategory
    val root = state.rootDirectory

    var itemBeingRenamed by remember { mutableStateOf<FileBrowserItem?>(null) }
    var itemForProperties by remember { mutableStateOf<FileBrowserItem?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveName by remember { mutableStateOf("archive.zip") }
    var showOverflow by remember { mutableStateOf(false) }
    var showSelectionOverflow by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedPaths) {
        if (state.selectedPaths.isEmpty()) itemBeingRenamed = null
    }

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(stringResource(R.string.archive_selection)) },
            text = {
                TextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text(stringResource(R.string.zip_file_name_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.archive(archiveName)
                        showArchiveDialog = false
                    }) { Text(stringResource(R.string.archive)) }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            })
    }

    itemBeingRenamed?.let { renaming ->
        var newName by remember(renaming.key) { mutableStateOf(renaming.name) }
        AlertDialog(
            onDismissRequest = { itemBeingRenamed = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.new_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        actions.rename(renaming, newName)
                        itemBeingRenamed = null
                    })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.rename(renaming, newName)
                    itemBeingRenamed = null
                }) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = {
                TextButton(onClick = { itemBeingRenamed = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            })
    }

    if (showNewFolderDialog) {
        NameDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.folder_name_label),
            initial = "",
            confirmLabel = stringResource(R.string.create),
            onConfirm = { actions.createFolder(it); showNewFolderDialog = false },
            onDismiss = { showNewFolderDialog = false },
        )
    }
    if (showNewFileDialog) {
        NameDialog(
            title = stringResource(R.string.new_file),
            label = stringResource(R.string.file_name_label),
            initial = "untitled.txt",
            confirmLabel = stringResource(R.string.create),
            onConfirm = { actions.createFile(it); showNewFileDialog = false },
            onDismiss = { showNewFileDialog = false },
        )
    }
    itemForProperties?.let { item ->
        PropertiesDialog(item = item, onDismiss = { itemForProperties = null })
    }

    val focusManager = LocalFocusManager.current

    val breadcrumbs = remember(state.currentDirectory, state.zipPath, state.zipInternalPath, state.rootDisplayName) {
        val crumbs = mutableListOf<Crumb>()
        val zp = state.zipPath
        if (zp == null) {
            fileAncestors(state.currentDirectory, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) state.rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
        } else {
            val parent = zp.parentFile ?: root
            fileAncestors(parent, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) state.rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
            crumbs.add(Crumb(displayName = zp.name, realFile = null, zipInternalPath = ""))
            var accum = ""
            for (seg in state.zipInternalPath.split("/").filter { it.isNotEmpty() }) {
                accum = if (accum.isEmpty()) seg else "$accum/$seg"
                crumbs.add(Crumb(displayName = seg, realFile = null, zipInternalPath = accum))
            }
        }
        crumbs
    }

    BackHandler(
        state.currentDirectory.absolutePath != root.absolutePath ||
            state.selectedPaths.isNotEmpty() ||
            state.zipPath != null ||
            state.isSearchActive ||
            isCategory
    ) {
        itemBeingRenamed = null
        if (state.isSearchActive) actions.setSearchActive(false) else actions.handleBack()
    }

    val zipToUnzip = remember(state.selectedPaths) {
        state.selectedPaths.singleOrNull()?.takeIf {
            !it.isDirectory && it.realFile != null && it.name.endsWith(".zip", ignoreCase = true)
        }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                focusManager.clearFocus()
                itemBeingRenamed = null
                actions.clearSelection()
            }, snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
            TopAppBar(navigationIcon = {
                if (!state.isSearchActive && state.selectedPaths.isEmpty()) {
                    IconButton(onClick = onOpenDrawer) { IconMenu() }
                }
            }, title = {
                if (state.isSearchActive) {
                    val searchFocus = remember { FocusRequester() }
                    TextField(
                        value = state.searchQuery,
                        onValueChange = { actions.setSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.search_files)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                } else if (isCategory) {
                    Text(state.categoryTitle, style = MaterialTheme.typography.titleLarge)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        breadcrumbs.forEachIndexed { index, crumb ->
                            var isBreadcrumbDraggingOver by remember(crumb) {
                                mutableStateOf(false)
                            }

                            val canDrop = !isReadOnly && crumb.realFile != null && crumb.zipInternalPath == null

                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(
                                            alpha = 0.5f
                                        )
                                        else Color.Transparent, shape = MaterialTheme.shapes.small
                                    )
                                    .then(
                                        if (canDrop) {
                                            Modifier.dragAndDropTarget(
                                                shouldStartDragAndDrop = { event ->
                                                    event.mimeTypes().contains(
                                                        ClipDescription.MIMETYPE_TEXT_PLAIN
                                                    )
                                                },
                                                target = remember(crumb.realFile.absolutePath) {
                                                    dropTarget(
                                                        onDragStateChange = { isBreadcrumbDraggingOver = it },
                                                        onDrop = { sources -> actions.moveToBreadcrumb(sources, crumb.realFile) }
                                                    )
                                                })
                                        } else Modifier
                                    )
                                    .clickable {
                                        val rf = crumb.realFile
                                        if (rf != null) {
                                            if (state.zipPath != null) actions.navigateToZipParentRealFolder(rf)
                                            else actions.navigateTo(rf)
                                        } else {
                                            actions.navigateToZipInternalPath(crumb.zipInternalPath ?: "")
                                        }
                                    }
                                    .padding(4.dp)) {
                                Text(
                                    text = crumb.displayName, style = MaterialTheme.typography.titleLarge
                                )
                            }
                            if (index < breadcrumbs.size - 1) {
                                IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }, actions = {
                if (state.isSearchActive) {
                    IconButton(onClick = { actions.setSearchActive(false) }) { IconClose() }
                } else if (state.selectedPaths.isNotEmpty()) {
                    IconButton(onClick = { actions.clearSelection() }) { IconClose() }
                    IconButton(onClick = { actions.deleteSelection() }) { IconDelete() }
                    IconButton(onClick = { showSelectionOverflow = true }) { IconMoreVert() }
                    DropdownMenu(
                        expanded = showSelectionOverflow,
                        onDismissRequest = { showSelectionOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { IconCopy() },
                            onClick = { showSelectionOverflow = false; actions.copySelection() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cut)) },
                            leadingIcon = { IconArchive() },
                            onClick = { showSelectionOverflow = false; actions.cutSelection() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            leadingIcon = { IconShare() },
                            onClick = { showSelectionOverflow = false; actions.shareSelection() },
                        )
                        val single = state.selectedPaths.singleOrNull()
                        if (single != null && !single.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_with)) },
                                leadingIcon = { IconShare() },
                                onClick = { showSelectionOverflow = false; actions.openWith(single) },
                            )
                        }
                        if (single != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { IconEdit() },
                                onClick = {
                                    showSelectionOverflow = false
                                    itemBeingRenamed = single
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.properties)) },
                                leadingIcon = { IconInfo() },
                                onClick = { showSelectionOverflow = false; itemForProperties = single },
                            )
                        }
                        if (single != null && single.isDirectory && single.realFile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_bookmark)) },
                                leadingIcon = { IconStar() },
                                onClick = { showSelectionOverflow = false; actions.addBookmark(single) },
                            )
                        }
                        if (canModifyHere) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive)) },
                                leadingIcon = { IconArchive() },
                                onClick = {
                                    showSelectionOverflow = false
                                    archiveName =
                                        if (state.selectedPaths.size == 1) "${state.selectedPaths.first().name}.zip"
                                        else "archive.zip"
                                    showArchiveDialog = true
                                },
                            )
                        }
                        if (zipToUnzip != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive_selection)) },
                                leadingIcon = { IconUnarchive() },
                                onClick = { showSelectionOverflow = false; onPickUnzipDestination() },
                            )
                        }
                        if (state.hasIncomingUris) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_saved)) },
                                leadingIcon = { IconSave() },
                                onClick = { showSelectionOverflow = false; actions.saveIncomingUris() },
                            )
                        }
                    }
                } else {
                    if (state.hasIncomingUris && !isReadOnly) {
                        IconButton(onClick = { actions.saveIncomingUris() }) { IconSave() }
                    }
                    IconButton(onClick = { actions.setSearchActive(true) }) { IconSearch() }
                    IconButton(onClick = { showOverflow = true }) { IconMoreVert() }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        SortBy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(option)) },
                                leadingIcon = { if (state.sortBy == option) IconCheck() },
                                onClick = { showOverflow = false; actions.setSortBy(option) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_ascending)) },
                            leadingIcon = { if (state.sortAscending) IconCheck() },
                            onClick = { showOverflow = false; actions.setSortAscending(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_descending)) },
                            leadingIcon = { if (!state.sortAscending) IconCheck() },
                            onClick = { showOverflow = false; actions.setSortAscending(false) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (state.viewMode == ViewMode.LIST) R.string.grid_view
                                        else R.string.list_view
                                    )
                                )
                            },
                            leadingIcon = {
                                if (state.viewMode == ViewMode.LIST) IconGrid() else IconList()
                            },
                            onClick = { showOverflow = false; actions.toggleViewMode() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (state.showHidden) R.string.hide_hidden else R.string.show_hidden
                                    )
                                )
                            },
                            leadingIcon = { if (state.showHidden) IconVisibilityOff() else IconVisible() },
                            onClick = { showOverflow = false; actions.toggleHidden() },
                        )
                        if (!isReadOnly) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.select_all)) },
                                onClick = { showOverflow = false; actions.selectAll() },
                            )
                        }
                        if (canModifyHere) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_folder)) },
                                leadingIcon = { IconNewFolder() },
                                onClick = { showOverflow = false; showNewFolderDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_file)) },
                                leadingIcon = { IconNewFile() },
                                onClick = { showOverflow = false; showNewFileDialog = true },
                            )
                        }
                    }
                }
            })
        }) { padding ->
        val query = state.searchQuery.trim()
        fun matches(item: FileBrowserItem) =
            query.isEmpty() || item.name.contains(query, ignoreCase = true)
        val allItems = (state.directories + state.files).filter(::matches)

        Column(Modifier.fillMaxSize().padding(padding)) {
            // Paste bar: shown when the clipboard has files and we can write here.
            if (state.clipboardCount > 0 && canModifyHere && state.selectedPaths.isEmpty() && !state.isSearchActive) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.ready_to_paste, state.clipboardCount),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { actions.pasteHere() }) { Text(stringResource(R.string.paste)) }
                        IconButton(onClick = { actions.clearClipboard() }) { IconClose() }
                    }
                }
            }

            val onItemClick: (FileBrowserItem) -> Unit = { child ->
                if (state.selectedPaths.isNotEmpty()) {
                    actions.toggleSelection(child)
                } else if (child.isDirectory) {
                    if (child.realFile != null) actions.navigateTo(child.realFile)
                    else actions.navigateIntoZipDir(child.name)
                } else if (child.name.endsWith(".zip", ignoreCase = true) && child.realFile != null) {
                    actions.openZipFile(child)
                } else if (child.name.endsWith(".apk", ignoreCase = true) && child.realFile != null) {
                    actions.installApk(child)
                } else {
                    actions.openFile(child)
                }
            }
            val onItemLongClick: (FileBrowserItem) -> Unit = { child ->
                if (!isReadOnly) {
                    itemBeingRenamed = null
                    actions.toggleSelection(child)
                }
            }

            if (allItems.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.no_files),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else if (state.viewMode == ViewMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    gridItems(allItems, key = { it.key }) { child ->
                        GridItem(
                            file = child,
                            isSelected = state.selectedPaths.any { it.key == child.key },
                            onClick = { onItemClick(child) },
                            onLongClick = { onItemLongClick(child) },
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(allItems, key = { it.key }) { child ->
                        val isSelected = state.selectedPaths.any { it.key == child.key }
                        DirectoryItem(
                            file = child,
                            isSelected = isSelected,
                            isReadOnly = isReadOnly,
                            onLongClick = { onItemLongClick(child) },
                            onClick = { onItemClick(child) },
                            onMove = { sources ->
                                if (!isReadOnly && child.isDirectory && child.realFile != null) {
                                    actions.moveInto(sources, child.realFile)
                                }
                            },
                            onStartDrag = {
                                if (isReadOnly) emptyList()
                                else if (state.selectedPaths.any { it.key == child.key }) state.selectedPaths.mapNotNull { it.realFile }.toList()
                                else listOfNotNull(child.realFile)
                            })
                        HorizontalDivider(
                            thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sortLabel(sortBy: SortBy): String = stringResource(
    when (sortBy) {
        SortBy.NAME -> R.string.sort_name
        SortBy.DATE -> R.string.sort_date
        SortBy.SIZE -> R.string.sort_size
        SortBy.TYPE -> R.string.sort_type
    }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: FileBrowserItem,
    isSelected: Boolean,
    isReadOnly: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onMove: (List<File>) -> Unit,
    onStartDrag: () -> List<File>
) {
    var isDraggingOver by remember { mutableStateOf(false) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)

    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource { _ ->
                val paths = currentOnStartDrag()
                if (paths.isEmpty()) return@dragAndDropSource null

                val uris = try {
                    paths.map { path ->
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", path
                        )
                    }
                } catch (_: Exception) {
                    return@dragAndDropSource null
                }
                val mimeTypes = paths.map { path ->
                    val extension = path.name.substringAfterLast(
                        '.', ""
                    )
                    if (extension == "md") "text/markdown"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension
                    ) ?: "*/*"
                }.toMutableList().apply {
                    add(
                        ClipDescription.MIMETYPE_TEXT_PLAIN
                    )
                }.distinct().toTypedArray()

                val clipData = ClipData(
                    paths.first().name, mimeTypes, ClipData.Item(
                        paths.first().absolutePath, null, null, uris.first()
                    )
                )
                for (i in 1 until uris.size) {
                    clipData.addItem(
                        ClipData.Item(
                            paths[i].absolutePath, null, null, uris[i]
                        )
                    )
                }

                DragAndDropTransferData(
                    clipData = clipData,
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
            .then(
                if (file.isDirectory && !isReadOnly && file.realFile != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event ->
                        event.mimeTypes().contains(
                            ClipDescription.MIMETYPE_TEXT_PLAIN
                        )
                    }, target = remember(file.key) {
                        dropTarget(
                            onDragStateChange = { isDraggingOver = it },
                            onDrop = { currentOnMove(it) }
                        )
                    })
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        ListItem(
            content = { Text(file.name.ifEmpty { "/" }) },
            leadingContent = { FileLeading(file, isSelected, 40.dp) },
            supportingContent = {
                if (!file.isDirectory) {
                    file.size?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridItem(
    file: FileBrowserItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            FileLeading(file, isSelected, 56.dp)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = file.name.ifEmpty { "/" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Home screen ----

/** Binds [FilesViewModel] to the stateless [HomeScreen]. */
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

/** The home landing screen: storage meter, category shortcuts, bookmarks, and recents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    home: HomeUiState,
    actions: FilesActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onOpenDrawer) { IconMenu() } },
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            home.storage?.let { s -> item { StorageCard(s) } }

            item {
                HomeRow(
                    leading = { IconFolder(tint = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.internal_storage),
                    subtitle = home.storage?.let { Formatter.formatShortFileSize(context, it.totalBytes) },
                    onClick = { actions.openInternalStorage() },
                )
            }

            item { SectionHeader(stringResource(R.string.categories)) }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CategoryTile(stringResource(R.string.cat_images), { IconImage(tint = COLOR_IMAGE) }) { actions.openCategory(FileCategory.IMAGES) }
                    CategoryTile(stringResource(R.string.cat_videos), { IconVideoCamera(tint = COLOR_VIDEO) }) { actions.openCategory(FileCategory.VIDEOS) }
                    CategoryTile(stringResource(R.string.cat_audio), { IconLibraryMusic(tint = COLOR_AUDIO) }) { actions.openCategory(FileCategory.AUDIO) }
                    CategoryTile(stringResource(R.string.cat_documents), { IconDescription(tint = COLOR_DOC) }) { actions.openCategory(FileCategory.DOCUMENTS) }
                    CategoryTile(stringResource(R.string.cat_downloads), { IconDownload(tint = COLOR_APK) }) { actions.openCategory(FileCategory.DOWNLOADS) }
                }
            }

            if (home.bookmarks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.bookmarks)) }
                items(home.bookmarks, key = { "bm:" + it.key }) { bm ->
                    HomeRow(
                        leading = { IconFolder(tint = MaterialTheme.colorScheme.outline) },
                        title = bm.name,
                        subtitle = null,
                        onClick = { bm.realFile?.let { actions.openBookmark(it) } },
                        trailing = {
                            IconButton(onClick = { bm.realFile?.let { actions.removeBookmark(it) } }) { IconClose() }
                        },
                    )
                }
            }

            if (home.recents.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recent_files)) }
                items(home.recents, key = { "rc:" + it.key }) { r ->
                    HomeRow(
                        leading = { FileLeading(r, false, 40.dp) },
                        title = r.name,
                        subtitle = r.size?.let { Formatter.formatShortFileSize(context, it) },
                        onClick = {
                            if (r.name.endsWith(".apk", ignoreCase = true) && r.realFile != null) {
                                actions.installApk(r)
                            } else {
                                actions.openFile(r)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.internal_storage), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(10.dp))
            val fraction = if (storage.totalBytes > 0) {
                (storage.usedBytes.toFloat() / storage.totalBytes).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(10.dp))
            Text(
                stringResource(
                    R.string.storage_usage,
                    Formatter.formatShortFileSize(context, storage.usedBytes),
                    Formatter.formatShortFileSize(context, storage.totalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CategoryTile(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HomeRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// ---- Shared dialogs ----

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

@Composable
private fun PropertiesDialog(item: FileBrowserItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var childCount by remember(item.key) { mutableStateOf<Int?>(null) }
    val realFile = item.realFile
    if (item.isDirectory && realFile != null) {
        LaunchedEffect(item.key) {
            childCount = withContext(Dispatchers.IO) { realFile.listFiles()?.size ?: 0 }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name.ifEmpty { "/" }) },
        text = {
            Column {
                realFile?.let { PropRow(stringResource(R.string.prop_path), it.absolutePath) }
                PropRow(
                    stringResource(R.string.prop_type),
                    stringResource(if (item.isDirectory) R.string.type_folder else R.string.type_file),
                )
                if (item.isDirectory) {
                    PropRow(
                        stringResource(R.string.prop_items),
                        childCount?.let { stringResource(R.string.items_count, it) } ?: "…",
                    )
                } else {
                    item.size?.let {
                        PropRow(stringResource(R.string.prop_size), Formatter.formatShortFileSize(context, it))
                    }
                }
                if (item.lastModified > 0) {
                    PropRow(
                        stringResource(R.string.prop_modified),
                        DateFormat.getDateTimeInstance().format(Date(item.lastModified)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PropRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
