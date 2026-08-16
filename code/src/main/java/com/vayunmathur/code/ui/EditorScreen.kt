package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.extractSymbols
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconFindReplace
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconWrapText
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDrawerState
import kotlinx.coroutines.launch

/**
 * Binds [EditorViewModel] to the stateless [EditorScreen].
 *
 * The two document pickers stay here: they need an activity result launcher, which is
 * exactly what a `@Preview` cannot provide.
 */
@Composable
fun EditorPage(
    viewModel: EditorViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
) {
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::openExternal) }
    val activity = LocalContext.current as? android.app.Activity

    EditorScreen(
        state = viewModel.uiState,
        actions = viewModel,
        onOpenFolder = onOpenFolder,
        onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onOpenGit = onOpenGit,
        onOpenTerminal = onOpenTerminal,
        onOpenPreview = onOpenPreview,
        onExitApp = { activity?.finish() },
    )
}

/**
 * Top-level editor scaffold: a navigation drawer holding the [FileTreePane], a top bar to
 * open it, then a tab strip, toolbar, optional find bar and the [CodeEditor] itself. Opening a
 * folder navigates to the in-app folder browser; single files still use the system file picker.
 *
 * No dependency on the ViewModel, so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun EditorScreen(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onExitApp: () -> Unit = {},
    /**
     * Seeds for the screen's own UI-only state (is the drawer showing, is the find bar open
     * and on what query). The app always takes the defaults; previews set them so a given
     * screen can be captured without driving the UI to get there.
     */
    initialDrawerOpen: Boolean = false,
    initialFind: String? = null,
) {
    val drawerState = rememberDrawerState(
        if (initialDrawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()
    var showFind by remember { mutableStateOf(initialFind != null) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showQuickOpen by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showMergeResolver by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    var showExitGuard by remember { mutableStateOf(false) }
    val anyDirty = state.tabs.any { it.isDirty }

    // Guard back/close when there are unsaved changes; offer Save all / Discard / Cancel.
    BackHandler(enabled = anyDirty) { showExitGuard = true }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.P -> {
                        if (event.isShiftPressed) {
                            showPalette = true
                        } else {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        }
                        true
                    }
                    Key.O -> {
                        if (event.isShiftPressed) {
                            showOutline = true
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FileTreePane(
                    state = state,
                    actions = actions,
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile,
                    onFileOpened = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        AppScaffold(
            title = state.currentTab?.name ?: "Code",
            navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) { IconMenu() }
            },
            actions = {
                IconButton(onClick = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                }) { IconSearch() }
                IconButton(onClick = onOpenSettings) { IconSettings() }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val tab = state.currentTab
                if (tab == null) {
                    EmptyEditorState(onOpenFolder = onOpenFolder, onOpenFile = onOpenFile)
                } else {
                    TabStrip(state, actions)
                    HorizontalDivider()
                    EditorToolbar(
                        state = state,
                        actions = actions,
                        onToggleFind = { showFind = !showFind },
                        onGoToLine = { showGoToLine = true },
                        onOpenSearch = onOpenSearch,
                        onOpenGit = onOpenGit,
                        onOpenTerminal = onOpenTerminal,
                        onOpenPreview = onOpenPreview,
                        onOpenQuickOpen = {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        },
                        onOpenPalette = { showPalette = true },
                        onOpenOutline = { showOutline = true },
                        onResolveConflicts = { showMergeResolver = true },
                        onOpenProblems = { showProblems = true },
                    )
                    HorizontalDivider()
                    if (tab.changedOnDisk) {
                        DiskChangedBanner(
                            onReload = { actions.reloadFromDisk() },
                            onKeep = { actions.dismissDiskChange() },
                        )
                        HorizontalDivider()
                    }
                    val secondaryTab = state.secondaryTab
                    if (secondaryTab != null) {
                        SplitEditors(
                            state = state,
                            actions = actions,
                            primary = tab,
                            secondary = secondaryTab,
                            showFind = showFind,
                            onCloseFind = { showFind = false },
                            initialQuery = initialFind.orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        if (state.experimentalEditor) {
                            CodeEditorView(
                                tab = tab,
                                actions = actions,
                                fontSize = state.fontSize,
                                editorTheme = state.editorTheme,
                                modifier = Modifier.weight(1f),
                                tabWidth = state.tabWidth,
                                softWrap = state.softWrap,
                                showWhitespace = state.showWhitespace,
                                showIndentGuides = state.showIndentGuides,
                                showMinimap = state.showMinimap,
                                showFind = showFind,
                                onCloseFind = { showFind = false },
                                initialQuery = initialFind.orEmpty(),
                                completions = state.completions,
                                showCompletions = state.showCompletions,
                                diagnostics = state.diagnostics,
                            )
                        } else {
                            CodeEditor(
                                tab = tab,
                                actions = actions,
                                softWrap = state.softWrap,
                                fontSize = state.fontSize,
                                showFind = showFind,
                                onCloseFind = { showFind = false },
                                modifier = Modifier.weight(1f),
                                initialQuery = initialFind.orEmpty(),
                                completions = state.completions,
                                showCompletions = state.showCompletions,
                                editorTheme = state.editorTheme,
                                diagnostics = state.diagnostics,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQuickOpen) {
        FuzzyPickerDialog(
            title = stringResource(R.string.quick_open),
            placeholder = stringResource(R.string.quick_open_hint),
            items = quickOpenItems(state) { actions.openPath(it) },
            emptyQueryItems = recentOpenItems(state) { actions.openPath(it) },
            onDismiss = { showQuickOpen = false },
        )
    }
    if (showPalette) {
        FuzzyPickerDialog(
            title = stringResource(R.string.command_palette),
            placeholder = stringResource(R.string.command_palette_hint),
            items = editorCommands(
                actions = actions,
                onToggleFind = { showFind = true },
                onGoToLine = { showGoToLine = true },
                onQuickOpen = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                },
                onOutline = { showOutline = true },
                onResolveConflicts = { showMergeResolver = true },
                onProblems = { showProblems = true },
                onOpenSearch = onOpenSearch,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onOpenPreview = onOpenPreview,
                onOpenSettings = onOpenSettings,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
            ),
            onDismiss = { showPalette = false },
        )
    }
    if (showGoToLine) {
        GoToLineDialog(
            onGo = { actions.goToLine(it) },
            onDismiss = { showGoToLine = false },
        )
    }
    val outlineTab = state.activeTab
    if (showOutline && outlineTab != null) {
        val symbols = remember(outlineTab.value.text, outlineTab.language) {
            extractSymbols(outlineTab.value.text, outlineTab.language)
        }
        FuzzyPickerDialog(
            title = stringResource(R.string.go_to_symbol),
            placeholder = stringResource(R.string.go_to_symbol_hint),
            items = symbols.map { symbol ->
                PickerItem(
                    primary = symbol.name,
                    secondary = "${symbol.kind.name.lowercase()} \u00B7 ${symbol.line}",
                    matchKey = symbol.name,
                ) { actions.goToLine(symbol.line) }
            },
            onDismiss = { showOutline = false },
        )
    }
    val conflictTab = state.activeTab
    if (showMergeResolver && conflictTab != null) {
        MergeResolverDialog(
            text = conflictTab.value.text,
            onResolve = { actions.resolveConflicts(it) },
            onDismiss = { showMergeResolver = false },
        )
    }
    if (showProblems) {
        FuzzyPickerDialog(
            title = stringResource(R.string.problems),
            placeholder = stringResource(R.string.problems),
            items = state.diagnostics.map { d ->
                PickerItem(
                    primary = d.message,
                    secondary = "${d.severity.name.lowercase()} \u00B7 ${d.line + 1}",
                    matchKey = d.message,
                ) { actions.goToLine(d.line + 1) }
            },
            onDismiss = { showProblems = false },
        )
    }
    if (showExitGuard) {
        AlertDialog(
            onDismissRequest = { showExitGuard = false },
            title = { Text(stringResource(R.string.exit_unsaved_title)) },
            text = { Text(stringResource(R.string.exit_unsaved_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = { actions.saveAll(); showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.save_all))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitGuard = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    }
}

/** Shown when nothing is open: prompts the user to open a folder or a single file. */
@Composable
private fun EmptyEditorState(onOpenFolder: () -> Unit, onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconCode(Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.no_file_open), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.open_a_folder_to_browse_your_project_or),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenFolder) { Text(stringResource(R.string.open_folder)) }
            Button(onClick = onOpenFile) { Text(stringResource(R.string.open_file)) }
        }
    }
}

/**
 * Two editor panes side by side (or stacked when narrow). Each pane edits its own tab and reports
 * focus via [CodeActions.focusPane], so the shared toolbar/find/navigation act on the focused pane.
 * The find bar is shown in whichever pane currently holds focus.
 */
@Composable
private fun SplitEditors(
    state: CodeUiState,
    actions: CodeActions,
    primary: TabUiState,
    secondary: TabUiState,
    showFind: Boolean,
    onCloseFind: () -> Unit,
    initialQuery: String,
    modifier: Modifier = Modifier,
) {
    val focusSecondary = state.focusedSecondary
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 640.dp
        val primaryPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                PaneHeader(primary.name, onClose = null)
                CodeEditor(
                    tab = primary,
                    actions = actions,
                    softWrap = state.softWrap,
                    fontSize = state.fontSize,
                    showFind = showFind && !focusSecondary,
                    onCloseFind = onCloseFind,
                    modifier = Modifier.weight(1f),
                    initialQuery = initialQuery,
                    completions = state.completions,
                    showCompletions = state.showCompletions && !focusSecondary,
                    editorTheme = state.editorTheme,
                    secondaryPane = false,
                    diagnostics = state.diagnostics,
                )
            }
        }
        val secondaryPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                PaneHeader(secondary.name, onClose = { actions.toggleSplit() })
                CodeEditor(
                    tab = secondary,
                    actions = actions,
                    softWrap = state.softWrap,
                    fontSize = state.fontSize,
                    showFind = showFind && focusSecondary,
                    onCloseFind = onCloseFind,
                    modifier = Modifier.weight(1f),
                    editorTheme = state.editorTheme,
                    secondaryPane = true,
                    onValueChangeOverride = actions::onSecondaryEditorChange,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                primaryPane(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                secondaryPane(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                primaryPane(Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider()
                secondaryPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A thin header above a split pane: the file name, plus an optional close (secondary) button. */
@Composable
private fun PaneHeader(name: String, onClose: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                IconClose(Modifier.size(16.dp))
            }
        }
    }
}

/** Horizontally scrollable strip of open tabs, each with a dirty indicator and close button. */
@Composable
private fun TabStrip(state: CodeUiState, actions: CodeActions) {
    var pendingCloseIndex by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        state.tabs.forEachIndexed { index, tab ->
            val selected = index == state.currentIndex
            val background =
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            Row(
                modifier = Modifier
                    .background(background)
                    .clickable { actions.selectTab(index) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                if (tab.isDirty) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                IconButton(
                    onClick = {
                        if (tab.isDirty) pendingCloseIndex = index else actions.closeTab(index)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    IconClose(Modifier.size(16.dp))
                }
            }
        }
    }

    pendingCloseIndex?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.discard_changes_title),
            confirmLabel = stringResource(R.string.discard),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = { actions.closeTab(index) },
            onDismiss = { pendingCloseIndex = null },
        )
    }
}

/** Undo/redo, save, find, soft-wrap, tab-insert, an overflow menu and a language indicator. */
@Composable
private fun EditorToolbar(
    state: CodeUiState,
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onOpenQuickOpen: () -> Unit = {},
    onOpenPalette: () -> Unit = {},
    onOpenOutline: () -> Unit = {},
    onResolveConflicts: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
) {
    val tab = state.activeTab ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.undo() }, enabled = tab.canUndo) { IconUndo() }
        IconButton(onClick = { actions.redo() }, enabled = tab.canRedo) { IconRedo() }
        IconButton(onClick = { actions.save() }, enabled = tab.isDirty) { IconSave() }
        IconButton(onClick = onToggleFind) { IconFindReplace() }
        IconButton(onClick = { actions.toggleSoftWrap() }) {
            IconWrapText(
                tint = if (state.softWrap) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.insertText(" ".repeat(state.tabWidth)) }) { IconFormatIndentIncrease() }
        OverflowMenu(icon = { IconMoreVert() }) {
            Item(text = stringResource(R.string.command_palette)) { onOpenPalette() }
            Item(text = stringResource(R.string.quick_open)) { onOpenQuickOpen() }
            Item(text = stringResource(R.string.go_to_symbol)) { onOpenOutline() }
            Item(text = stringResource(R.string.go_to_line)) { onGoToLine() }
            Item(text = stringResource(R.string.toggle_comment)) { actions.toggleComment() }
            Item(text = stringResource(R.string.duplicate_line)) { actions.duplicateLine() }
            Item(text = stringResource(R.string.move_line_up)) { actions.moveLineUp() }
            Item(text = stringResource(R.string.move_line_down)) { actions.moveLineDown() }
            Item(text = stringResource(R.string.delete_line)) { actions.deleteLine() }
            Item(text = stringResource(R.string.search_in_project)) { onOpenSearch() }
            Item(text = stringResource(R.string.source_control)) { onOpenGit() }
            Item(text = stringResource(R.string.terminal)) { onOpenTerminal() }
            Item(text = stringResource(R.string.preview)) { onOpenPreview() }
            Item(text = stringResource(R.string.format_document)) { actions.formatDocument() }
            Item(text = stringResource(R.string.resolve_conflicts)) { onResolveConflicts() }
            Item(text = stringResource(R.string.problems)) { onOpenProblems() }
            Item(text = stringResource(R.string.split_view)) { actions.toggleSplit() }
        }
        Spacer(Modifier.width(8.dp))
        if (state.diagnostics.isNotEmpty()) {
            val errorColor = MaterialTheme.colorScheme.error
            val warnColor = Color(0xFFFFB300)
            TextButton(onClick = onOpenProblems) {
                Text(
                    text = "\u26A0 ${state.errorCount}/${state.warningCount}",
                    color = if (state.errorCount > 0) errorColor else warnColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            text = tab.language.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            text = "${tab.charsetName} \u00B7 ${tab.lineEndingName}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** A banner shown when the open file changed on disk under unsaved edits: reload or keep. */
@Composable
private fun DiskChangedBanner(onReload: () -> Unit, onKeep: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.disk_changed),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload) { Text(stringResource(R.string.reload)) }
        TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_mine)) }
    }
}

/** A small dialog that reads a line number and jumps the caret to it. */
@Composable
private fun GoToLineDialog(onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val line = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_line)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { new -> value = new.filter { it.isDigit() } },
                singleLine = true,
                label = { Text(stringResource(R.string.line)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { line?.let(onGo); onDismiss() },
                enabled = line != null && line > 0,
            ) { Text(stringResource(R.string.go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The command-palette registry: named actions mapped to editor callbacks and nav destinations. */
@Composable
private fun editorCommands(
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit,
    onQuickOpen: () -> Unit,
    onOutline: () -> Unit,
    onResolveConflicts: () -> Unit,
    onProblems: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
): List<PickerItem> = listOf(
    PickerItem(stringResource(R.string.quick_open)) { onQuickOpen() },
    PickerItem(stringResource(R.string.save)) { actions.save() },
    PickerItem(stringResource(R.string.save_all)) { actions.saveAll() },
    PickerItem(stringResource(R.string.find)) { onToggleFind() },
    PickerItem(stringResource(R.string.go_to_line)) { onGoToLine() },
    PickerItem(stringResource(R.string.go_to_symbol)) { onOutline() },
    PickerItem(stringResource(R.string.toggle_comment)) { actions.toggleComment() },
    PickerItem(stringResource(R.string.duplicate_line)) { actions.duplicateLine() },
    PickerItem(stringResource(R.string.move_line_up)) { actions.moveLineUp() },
    PickerItem(stringResource(R.string.move_line_down)) { actions.moveLineDown() },
    PickerItem(stringResource(R.string.delete_line)) { actions.deleteLine() },
    PickerItem(stringResource(R.string.format_document)) { actions.formatDocument() },
    PickerItem(stringResource(R.string.resolve_conflicts)) { onResolveConflicts() },
    PickerItem(stringResource(R.string.problems)) { onProblems() },
    PickerItem(stringResource(R.string.split_view)) { actions.toggleSplit() },
    PickerItem(stringResource(R.string.soft_wrap)) { actions.toggleSoftWrap() },
    PickerItem(stringResource(R.string.undo)) { actions.undo() },
    PickerItem(stringResource(R.string.redo)) { actions.redo() },
    PickerItem(stringResource(R.string.search_in_project)) { onOpenSearch() },
    PickerItem(stringResource(R.string.source_control)) { onOpenGit() },
    PickerItem(stringResource(R.string.terminal)) { onOpenTerminal() },
    PickerItem(stringResource(R.string.preview)) { onOpenPreview() },
    PickerItem(stringResource(R.string.settings)) { onOpenSettings() },
    PickerItem(stringResource(R.string.open_folder)) { onOpenFolder() },
    PickerItem(stringResource(R.string.open_file)) { onOpenFile() },
)
