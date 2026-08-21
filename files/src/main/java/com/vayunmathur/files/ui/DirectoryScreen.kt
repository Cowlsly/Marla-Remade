package com.vayunmathur.files.ui

import android.content.ClipData
import android.content.ClipDescription
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.ViewMode
import com.vayunmathur.files.ui.components.DirectoryItem
import com.vayunmathur.files.ui.components.GridItem
import com.vayunmathur.files.ui.dialogs.PropertiesDialog
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.vayunmathur.library.ui.R as UiR

// ---- File type / thumbnail helpers ----

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts")
private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "mid", "amr")
private val DOC_EXTS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv", "odt")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val CODE_EXTS = setOf("kt", "java", "c", "cpp", "h", "py", "js", "ts", "html", "css", "json", "xml", "sh", "rs", "go")

internal val COLOR_IMAGE = Color(0xFF4CAF50)
internal val COLOR_VIDEO = Color(0xFF9C27B0)
internal val COLOR_AUDIO = Color(0xFFFF9800)
internal val COLOR_DOC = Color(0xFF2196F3)
internal val COLOR_ARCHIVE = Color(0xFFB28500)
internal val COLOR_APK = Color(0xFF009688)
internal val COLOR_CODE = Color(0xFF607D8B)

/** Leading visual for a browser item: an image thumbnail, or a type-colored icon. */
@Composable
internal fun FileLeading(item: FileBrowserItem, isSelected: Boolean, sizeDp: Dp) {
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

    AppScaffold(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                focusManager.clearFocus()
                itemBeingRenamed = null
                actions.clearSelection()
            },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        navigationIcon = {
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
            },
            scrollBehavior = appBarScrollBehavior(),
        ) { padding ->
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
                            onMove = { sources: List<File> ->
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
internal fun sortLabel(sortBy: SortBy): String = stringResource(
    when (sortBy) {
        SortBy.NAME -> R.string.sort_name
        SortBy.DATE -> R.string.sort_date
        SortBy.SIZE -> R.string.sort_size
        SortBy.TYPE -> R.string.sort_type
    }
)

@Composable
internal fun NameDialog(
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
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}


