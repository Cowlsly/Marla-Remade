package com.vayunmathur.code.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.EditorThemes
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext

/** One expandable row in the file-tree pane; the tree is stored as a flat, ordered list. */
class TreeNode(val entry: FileEntry, val depth: Int) {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
}

/**
 * One open file. Editor content lives in [value]; [savedText] is the last persisted text so
 * [isDirty] can drive the unsaved-dot. Undo/redo are plain deques (not observed directly);
 * [canUndo]/[canRedo] mirror their emptiness as state so the toolbar buttons stay reactive.
 *
 * Most tabs are backed by a real [file]. Files opened through a VIEW/EDIT intent from another
 * app arrive as a `content://` [externalUri] instead and are [readOnly] (no `save` target).
 */
class OpenTab(
    file: File?,
    externalUri: Uri? = null,
    val readOnly: Boolean = false,
    initialName: String,
    initialText: String,
    language: Language,
) {
    var file by mutableStateOf(file)
    var externalUri by mutableStateOf(externalUri)
    var name by mutableStateOf(initialName)
    var language by mutableStateOf(language)
    var value by mutableStateOf(TextFieldValue(initialText))
    var savedText by mutableStateOf(initialText)

    /** Fold header lines currently collapsed in this tab (persisted per file path). */
    var foldedHeaders by mutableStateOf<Set<Int>>(emptySet())
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // Fidelity metadata so a save round-trips the file byte-for-byte (see [TextEncoding]).
    var charset: Charset = Charsets.UTF_8
    var lineEnding: LineEnding = LineEnding.LF
    var hadBom: Boolean = false

    // Snapshot of the file on disk when it was last opened/saved, for external-change detection.
    var diskModified: Long = 0L
    var diskLength: Long = 0L

    /** Set when the file changed on disk while this tab held unsaved edits (drives the banner). */
    var changedOnDisk by mutableStateOf(false)

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    /** Stable identity used to dedup tabs: the file path, or the external URI string. */
    val key: String get() = file?.absolutePath ?: externalUri.toString()

    val isDirty: Boolean get() = value.text != savedText

    fun pushUndo(previous: TextFieldValue) {
        undoStack.addLast(previous)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = previous
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
        canRedo = redoStack.isNotEmpty()
        canUndo = true
    }

    private companion object {
        const val UNDO_LIMIT = 100
    }
}

/**
 * Activity-scoped state for the editor: the open folder tree, the set of open tabs and the
 * editor preferences. An [AndroidViewModel] (obtained via `by viewModels()`) so it can reach
 * the ContentResolver and DataStore through the application context.
 *
 * It implements [CodeActions] and projects itself into a [CodeUiState] so the screens can be
 * rendered without it; reading [uiState] inside a composable subscribes to the same
 * `mutableStateOf`/`mutableStateListOf` members the screens used to read directly.
 *
 * The file backend is real [File] paths (the app holds `MANAGE_EXTERNAL_STORAGE`), so folder,
 * tree and file operations all go through [FileFiles].
 */
class EditorViewModel(application: Application) : AndroidViewModel(application), CodeActions {

    private val context get() = getApplication<Application>()
    private val prefs = EditorPrefs(context)

    // ---- File tree ----
    var rootDir by mutableStateOf<File?>(null)
        private set
    var rootName by mutableStateOf<String?>(null)
        private set
    val nodes = mutableStateListOf<TreeNode>()

    // ---- Tabs ----
    val tabs = mutableStateListOf<OpenTab>()
    var currentIndex by mutableStateOf(-1)
        private set
    val currentTab: OpenTab? get() = tabs.getOrNull(currentIndex)

    /** When set, a second pane shows this tab beside the current one (split view). */
    var secondaryIndex by mutableStateOf<Int?>(null)
        private set

    /** True when the secondary split pane holds focus, so shared actions target it instead. */
    var focusedSecondary by mutableStateOf(false)
        private set

    /** The tab shared toolbar/find/navigation actions target: the focused pane's tab. */
    private val activeTab: OpenTab?
        get() = if (focusedSecondary) secondaryIndex?.let { tabs.getOrNull(it) } else currentTab

    // ---- Preferences ----
    var softWrap by mutableStateOf(false)
        private set

    // These five are read as `fontSize`/`tabWidth`/... but written through the CodeActions
    // `setFontSize`/`setTabWidth`/... methods. Backing them by a private MutableState (rather than
    // a `var ... private set`) avoids a JVM signature clash between the generated property setter
    // and the same-named interface method.
    private val _fontSize = mutableStateOf(EditorPrefs.DEFAULT_FONT_SIZE)
    val fontSize: Int get() = _fontSize.value
    private val _tabWidth = mutableStateOf(EditorPrefs.DEFAULT_TAB_WIDTH)
    val tabWidth: Int get() = _tabWidth.value
    private val _themeMode = mutableStateOf(EditorPrefs.THEME_SYSTEM)
    val themeMode: String get() = _themeMode.value
    private val _autoIndent = mutableStateOf(true)
    val autoIndent: Boolean get() = _autoIndent.value
    private val _autoCloseBrackets = mutableStateOf(true)
    val autoCloseBrackets: Boolean get() = _autoCloseBrackets.value
    private val _autoSave = mutableStateOf(false)
    val autoSave: Boolean get() = _autoSave.value
    private val _trimTrailingOnSave = mutableStateOf(false)
    val trimTrailingOnSave: Boolean get() = _trimTrailingOnSave.value
    private val _finalNewlineOnSave = mutableStateOf(false)
    val finalNewlineOnSave: Boolean get() = _finalNewlineOnSave.value
    private val _editorTheme = mutableStateOf(EditorThemes.DEFAULT)
    val editorTheme: String get() = _editorTheme.value
    private val _experimentalEditor = mutableStateOf(false)
    val experimentalEditor: Boolean get() = _experimentalEditor.value
    private val _showWhitespace = mutableStateOf(false)
    val showWhitespace: Boolean get() = _showWhitespace.value
    private val _showIndentGuides = mutableStateOf(false)
    val showIndentGuides: Boolean get() = _showIndentGuides.value
    private val _showMinimap = mutableStateOf(false)
    val showMinimap: Boolean get() = _showMinimap.value
    private var autoSaveJob: Job? = null

    // ---- Project search ----
    val searchResults = mutableStateListOf<SearchResult>()
    var isSearching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    // ---- Autocomplete ----
    val completions = mutableStateListOf<Completion>()
    var showCompletions by mutableStateOf(false)
        private set
    private var completionsJob: Job? = null

    // ---- Diagnostics ----
    val diagnostics = mutableStateListOf<Diagnostic>()
    private var diagnosticsJob: Job? = null

    // ---- User snippets ----
    val userSnippets = mutableStateListOf<UserSnippet>()

    /** Persisted per-file collapsed fold headers (absolute path -> header lines). */
    private val foldStateByPath = HashMap<String, Set<Int>>()

    // ---- Git ----
    var gitIsRepo by mutableStateOf(false)
        private set
    var gitStatus by mutableStateOf<GitStatus?>(null)
        private set
    val gitLog = mutableStateListOf<GitCommitInfo>()
    val gitBranches = mutableStateListOf<String>()
    var gitBusy by mutableStateOf(false)
        private set
    var gitMessage by mutableStateOf<String?>(null)
        private set
    var gitDiff by mutableStateOf<String?>(null)
        private set
    var gitDiffRows by mutableStateOf<List<DiffRow>?>(null)
        private set

    private val _gitUsername = mutableStateOf("")
    val gitUsername: String get() = _gitUsername.value
    private val _gitToken = mutableStateOf("")
    val gitToken: String get() = _gitToken.value
    private val _gitAuthorName = mutableStateOf("")
    val gitAuthorName: String get() = _gitAuthorName.value
    private val _gitAuthorEmail = mutableStateOf("")
    val gitAuthorEmail: String get() = _gitAuthorEmail.value

    // ---- Terminal ----
    val terminalLines = mutableStateListOf<String>()
    var terminalRunning by mutableStateOf(false)
        private set
    private var terminal: TerminalSession? = null

    // ---- Quick-open ----
    val projectFiles = mutableStateListOf<ProjectFileEntry>()
    private val recentPaths = mutableStateListOf<String>()
    private var projectFilesJob: Job? = null

    /** Snapshot of everything the screens draw; rebuilt on every read, as Compose expects. */
    val uiState: CodeUiState
        get() = CodeUiState(
            tabs = tabs.map {
                TabUiState(
                    name = it.name,
                    value = it.value,
                    language = it.language,
                    isDirty = it.isDirty,
                    canUndo = it.canUndo,
                    canRedo = it.canRedo,
                    changedOnDisk = it.changedOnDisk,
                    charsetName = it.charset.name(),
                    lineEndingName = it.lineEnding.name,
                    foldedHeaders = it.foldedHeaders,
                )
            },
            currentIndex = currentIndex,
            secondaryIndex = secondaryIndex ?: -1,
            focusedSecondary = focusedSecondary,
            softWrap = softWrap,
            rootName = rootName,
            folderOpen = rootDir != null,
            nodes = nodes.map {
                TreeRowUiState(
                    name = it.entry.name,
                    depth = it.depth,
                    isDirectory = it.entry.isDirectory,
                    expanded = it.expanded,
                )
            },
            fontSize = fontSize,
            tabWidth = tabWidth,
            autoIndent = autoIndent,
            autoCloseBrackets = autoCloseBrackets,
            searchResults = searchResults.toList(),
            isSearching = isSearching,
            completions = completions.toList(),
            showCompletions = showCompletions,
            editorTheme = editorTheme,
            experimentalEditor = experimentalEditor,
            showWhitespace = showWhitespace,
            showIndentGuides = showIndentGuides,
            showMinimap = showMinimap,
            projectFiles = projectFiles.toList(),
            recentFiles = recentPaths.map { toProjectEntry(File(it)) },
            diagnostics = diagnostics.toList(),
        )

    init {
        viewModelScope.launch { softWrap = prefs.softWrap.first() }
        viewModelScope.launch { _fontSize.value = prefs.fontSize.first() }
        viewModelScope.launch { _tabWidth.value = prefs.tabWidth.first() }
        viewModelScope.launch { _themeMode.value = prefs.themeMode.first() }
        viewModelScope.launch { _autoIndent.value = prefs.autoIndent.first() }
        viewModelScope.launch { _autoCloseBrackets.value = prefs.autoCloseBrackets.first() }
        viewModelScope.launch { _autoSave.value = prefs.autoSave.first() }
        viewModelScope.launch { _trimTrailingOnSave.value = prefs.trimTrailingOnSave.first() }
        viewModelScope.launch { _finalNewlineOnSave.value = prefs.finalNewlineOnSave.first() }
        viewModelScope.launch { _editorTheme.value = prefs.editorTheme.first() }
        viewModelScope.launch { _experimentalEditor.value = prefs.experimentalEditor.first() }
        viewModelScope.launch { _showWhitespace.value = prefs.showWhitespace.first() }
        viewModelScope.launch { _showIndentGuides.value = prefs.showIndentGuides.first() }
        viewModelScope.launch { _showMinimap.value = prefs.showMinimap.first() }
        viewModelScope.launch { _gitUsername.value = prefs.gitUsername.first() }
        viewModelScope.launch { _gitToken.value = prefs.gitToken.first() }
        viewModelScope.launch { _gitAuthorName.value = prefs.gitAuthorName.first() }
        viewModelScope.launch { _gitAuthorEmail.value = prefs.gitAuthorEmail.first() }
        viewModelScope.launch { recentPaths.addAll(prefs.recentFiles.first()) }
        viewModelScope.launch { userSnippets.addAll(prefs.userSnippets.first()) }
        viewModelScope.launch {
            prefs.foldState.first().forEach { (path, lines) -> foldStateByPath[path] = lines.toSet() }
            // Apply to any tabs already restored before the fold state finished loading.
            for (tab in tabs) {
                val path = tab.file?.absolutePath ?: continue
                foldStateByPath[path]?.let { tab.foldedHeaders = it }
            }
        }
        viewModelScope.launch {
            val stored = prefs.folderPath.first() ?: return@launch
            val dir = File(stored)
            if (dir.isDirectory) {
                runCatching { loadFolder(dir) }.onFailure { prefs.clearFolderPath() }
            } else {
                prefs.clearFolderPath()
            }
        }
        viewModelScope.launch { restoreSession() }
    }

    /** Reopens the tabs from the previous session (files that still exist), off the main thread. */
    private suspend fun restoreSession() {
        val paths = prefs.sessionPaths.first()
        if (paths.isEmpty()) return
        val current = prefs.sessionCurrent.first()
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) continue
            if (tabs.any { it.key == path }) continue
            tabs.add(makeFileTab(file))
        }
        currentIndex = tabs.indexOfFirst { it.file?.absolutePath == current }
            .takeIf { it >= 0 } ?: if (tabs.isEmpty()) -1 else 0
        scheduleDiagnostics()
    }

    /** Persists the current set of file-backed tabs and the foreground tab for session restore. */
    private fun saveSession() {
        val paths = tabs.mapNotNull { it.file?.absolutePath }
        val current = currentTab?.file?.absolutePath
        viewModelScope.launch { prefs.setSession(paths, current) }
    }

    // ---- Folder handling ----

    /** Opens [dir] as the project root and loads its top level, persisting it for relaunch. */
    fun openFolder(dir: File) {
        viewModelScope.launch {
            prefs.setFolderPath(dir.absolutePath)
            runCatching { loadFolder(dir) }
        }
    }

    fun closeFolder() {
        rootDir = null
        rootName = null
        nodes.clear()
        viewModelScope.launch { prefs.clearFolderPath() }
    }

    private suspend fun loadFolder(dir: File) {
        require(dir.isDirectory) { "Not a directory: $dir" }
        val children = withContext(Dispatchers.IO) { FileFiles.listChildren(dir) }
        rootDir = dir
        rootName = dir.name
        nodes.clear()
        nodes.addAll(children.map { TreeNode(it, depth = 0) })
        refreshGit()
    }

    /** Expands/collapses a directory row, or opens a file row in a tab. */
    override fun toggleNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        if (!node.entry.isDirectory) {
            openFile(node.entry.file)
            return
        }

        if (node.expanded) {
            node.expanded = false
            removeDescendants(index)
        } else {
            node.expanded = true
            node.loading = true
            viewModelScope.launch {
                val children = withContext(Dispatchers.IO) { FileFiles.listChildren(node.entry.file) }
                node.loading = false
                // The row may have been collapsed again while loading; only insert if still open.
                val at = nodes.indexOf(node)
                if (at >= 0 && node.expanded) {
                    nodes.addAll(at + 1, children.map { TreeNode(it, node.depth + 1) })
                }
            }
        }
    }

    // ---- File operations ----

    /** Removes the rows that are descendants of the row at [index] (depth strictly greater). */
    private fun removeDescendants(index: Int) {
        val depth = nodes[index].depth
        while (index + 1 < nodes.size && nodes[index + 1].depth > depth) {
            nodes.removeAt(index + 1)
        }
    }

    /**
     * Re-lists the children of a folder and rebuilds that subtree in [nodes]. Expansion state
     * and already-loaded descendant rows of immediate child folders are preserved by path.
     * [parentIndex] null refreshes the tree root.
     */
    private suspend fun refreshChildren(parentIndex: Int?) {
        val parentFile = if (parentIndex == null) rootDir ?: return
        else nodes.getOrNull(parentIndex)?.entry?.file ?: return
        val parentDepth = if (parentIndex == null) -1 else nodes[parentIndex].depth
        val childDepth = parentDepth + 1

        val blockStart = (parentIndex ?: -1) + 1
        var blockEnd = blockStart
        while (blockEnd < nodes.size && nodes[blockEnd].depth > parentDepth) blockEnd++

        // Preserve existing immediate children (and their loaded subtrees) by path.
        val preservedNode = HashMap<String, TreeNode>()
        val preservedSubtree = HashMap<String, List<TreeNode>>()
        var i = blockStart
        while (i < blockEnd) {
            val child = nodes[i]
            if (child.depth == childDepth) {
                var j = i + 1
                while (j < blockEnd && nodes[j].depth > childDepth) j++
                val key = child.entry.file.absolutePath
                preservedNode[key] = child
                preservedSubtree[key] = nodes.subList(i + 1, j).toList()
                i = j
            } else {
                i++
            }
        }

        val entries = withContext(Dispatchers.IO) { FileFiles.listChildren(parentFile) }

        val rebuilt = ArrayList<TreeNode>()
        for (entry in entries) {
            val key = entry.file.absolutePath
            val existing = preservedNode[key]
            if (existing != null) {
                rebuilt.add(existing)
                rebuilt.addAll(preservedSubtree[key].orEmpty())
            } else {
                rebuilt.add(TreeNode(entry, childDepth))
            }
        }

        for (k in blockEnd - 1 downTo blockStart) nodes.removeAt(k)
        nodes.addAll(blockStart, rebuilt)
    }

    /** Resolves the create target directory: the tree root, or a directory row. */
    private fun parentFileFor(parentIndex: Int?): File? =
        if (parentIndex == null) rootDir else nodes.getOrNull(parentIndex)?.entry?.file

    override fun createFile(parentIndex: Int?, name: String) {
        val parent = parentFileFor(parentIndex) ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { FileFiles.createFile(parent, name) } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
            openFile(file)
        }
    }

    override fun createFolder(parentIndex: Int?, name: String) {
        val parent = parentFileFor(parentIndex) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FileFiles.createDirectory(parent, name) } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
        }
    }

    override fun renameNode(index: Int, newName: String) {
        val node = nodes.getOrNull(index) ?: return
        val oldFile = node.entry.file
        viewModelScope.launch {
            val newFile = withContext(Dispatchers.IO) { FileFiles.rename(oldFile, newName) } ?: return@launch
            val at = nodes.indexOf(node)
            if (at >= 0) {
                // A renamed directory's descendant paths shift; drop them so a re-expand re-lists.
                if (node.entry.isDirectory) removeDescendants(at)
                nodes[at] = TreeNode(FileEntry(newFile, newName, node.entry.isDirectory), node.depth)
            }
            // Repoint open tabs backed by the renamed file (or living under a renamed directory).
            val oldPath = oldFile.absolutePath
            val newPath = newFile.absolutePath
            for (tab in tabs) {
                val p = tab.file?.absolutePath ?: continue
                if (p == oldPath) {
                    tab.file = newFile
                    tab.name = newName
                    tab.language = Language.fromFileName(newName)
                } else if (p.startsWith(oldPath + File.separator)) {
                    tab.file = File(newPath + p.substring(oldPath.length))
                }
            }
            saveSession()
        }
    }

    override fun deleteNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        val file = node.entry.file
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FileFiles.delete(file) }
            if (!ok) return@launch
            val at = nodes.indexOf(node)
            if (at >= 0) {
                removeDescendants(at)
                nodes.removeAt(at)
            }
            closeTabsUnder(file)
        }
    }

    /** Closes any open tab whose file is [file] or lives beneath it (for a deleted directory). */
    private fun closeTabsUnder(file: File) {
        val target = file.absolutePath
        val prefix = target + File.separator
        for (i in tabs.indices.reversed()) {
            val p = tabs[i].file?.absolutePath ?: continue
            if (p == target || p.startsWith(prefix)) closeTab(i)
        }
    }

    // ---- Tab handling ----

    /** Opens [file] in a tab (focusing it if already open), reading its text off the main thread. */
    fun openFile(file: File) {
        addRecentFile(file.absolutePath)
        val key = file.absolutePath
        val existing = tabs.indexOfFirst { it.key == key }
        if (existing >= 0) {
            currentIndex = existing
            scheduleDiagnostics()
            return
        }
        viewModelScope.launch {
            tabs.add(makeFileTab(file))
            currentIndex = tabs.lastIndex
            saveSession()
            scheduleDiagnostics()
        }
    }
    fun openExternal(uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { openFile(File(it)) }
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val key = uri.toString()
        val existing = tabs.indexOfFirst { it.key == key }
        if (existing >= 0) {
            currentIndex = existing
            return
        }
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { FileFiles.queryDisplayName(context, uri) } ?: "untitled"
            val text = withContext(Dispatchers.IO) {
                runCatching { FileFiles.readTextFromUri(context, uri) }.getOrDefault("")
            }
            tabs.add(
                OpenTab(
                    file = null,
                    externalUri = uri,
                    readOnly = true,
                    initialName = displayName,
                    initialText = text,
                    language = Language.fromFileName(displayName),
                )
            )
            currentIndex = tabs.lastIndex
            saveSession()
        }
    }

    /** Reads + decodes [file] off the main thread into a fully-populated tab (empty on failure). */
    private suspend fun makeFileTab(file: File, name: String = file.name): OpenTab {
        val loaded = loadFile(file)
        return OpenTab(
            file = file,
            initialName = name,
            initialText = loaded.decoded.text,
            language = Language.fromFileName(name),
        ).apply {
            charset = loaded.decoded.charset
            lineEnding = loaded.decoded.lineEnding
            hadBom = loaded.decoded.hadBom
            diskModified = loaded.modified
            diskLength = loaded.length
            foldStateByPath[file.absolutePath]?.let { foldedHeaders = it }
        }
    }

    /** Reads [file]'s bytes and decodes them, capturing the on-disk snapshot; safe on failure. */
    private suspend fun loadFile(file: File): LoadedFile = withContext(Dispatchers.IO) {
        runCatching {
            LoadedFile(TextEncoding.decode(FileFiles.readBytes(file)), file.lastModified(), file.length())
        }.getOrDefault(
            LoadedFile(DecodedText("", Charsets.UTF_8, false, LineEnding.LF), file.lastModified(), file.length()),
        )
    }

    private class LoadedFile(val decoded: DecodedText, val modified: Long, val length: Long)

    override fun selectTab(index: Int) {
        if (index in tabs.indices) {
            currentIndex = index
            if (secondaryIndex == index) secondaryIndex = null // never show the same tab in both panes
            focusedSecondary = false
            dismissCompletions()
            saveSession()
            scheduleDiagnostics()
        }
    }

    override fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removingCurrent = index == currentIndex
        tabs.removeAt(index)
        currentIndex = when {
            tabs.isEmpty() -> -1
            index < currentIndex -> currentIndex - 1
            removingCurrent -> index.coerceAtMost(tabs.lastIndex)
            else -> currentIndex
        }
        secondaryIndex = secondaryIndex?.let { s ->
            when {
                s == index -> null
                s > index -> s - 1
                else -> s
            }
        }?.takeIf { it in tabs.indices && it != currentIndex }
        if (secondaryIndex == null) focusedSecondary = false
        saveSession()
    }

    // ---- Editing ----

    override fun onEditorChange(new: TextFieldValue) {
        editTab(currentTab ?: return, new, isPrimary = true)
    }

    override fun onSecondaryEditorChange(new: TextFieldValue) {
        val tab = secondaryIndex?.let { tabs.getOrNull(it) } ?: return
        editTab(tab, new, isPrimary = false)
    }

    /** Applies smart input to [tab] as a single undo step; only the primary pane drives completions. */
    private fun editTab(tab: OpenTab, new: TextFieldValue, isPrimary: Boolean) {
        val indentUnit = " ".repeat(tabWidth)
        val processed = applyEditorInput(tab.value, new, indentUnit, autoIndent, autoCloseBrackets)
        if (processed.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = processed
        if (autoSave) scheduleAutoSave()
        if (isPrimary) updateCompletions()
        scheduleDiagnostics()
    }

    /** Opens/closes the second editor pane, choosing an adjacent tab as the secondary. */
    override fun toggleSplit() {
        if (secondaryIndex != null) {
            secondaryIndex = null
            focusedSecondary = false
            return
        }
        if (tabs.size < 2 || currentIndex < 0) return
        val other = (currentIndex + 1).takeIf { it in tabs.indices } ?: (currentIndex - 1)
        secondaryIndex = other.takeIf { it in tabs.indices && it != currentIndex }
    }

    /** Records which split pane holds focus, so shared toolbar/find/nav actions target it. */
    override fun focusPane(secondary: Boolean) {
        focusedSecondary = secondary && secondaryIndex != null
    }

    // ---- Autocomplete ----

    override fun requestCompletions() = updateCompletions()

    /**
     * Recomputes the completion list from the caret's word prefix and the open buffers.
     *
     * Debounced and computed off the main thread: scanning every open buffer for identifiers is
     * O(all open documents), so doing it inline on each keystroke stalled typing in a large file.
     */
    private fun updateCompletions() {
        completionsJob?.cancel()
        val tab = currentTab
        if (tab == null || !tab.value.selection.collapsed) {
            dismissCompletions()
            return
        }
        val prefix = currentWordPrefix(tab.value.text, tab.value.selection.start)
        if (prefix.length < MIN_COMPLETION_PREFIX) {
            dismissCompletions()
            return
        }
        val buffers = tabs.map { it.value.text }.filter { it.length <= MAX_COMPLETION_BUFFER_CHARS }
        val language = tab.language
        val snippets = userSnippets.toList()
        completionsJob = viewModelScope.launch {
            delay(COMPLETIONS_DELAY_MS)
            val list = withContext(Dispatchers.Default) {
                computeCompletions(prefix, language, buffers, MAX_COMPLETIONS, snippets)
            }
            completions.clear()
            completions.addAll(list)
            showCompletions = list.isNotEmpty()
        }
    }

    override fun acceptCompletion(item: Completion) {
        val tab = currentTab ?: return
        val v = tab.value
        val caret = v.selection.start
        val prefix = currentWordPrefix(v.text, caret)
        val start = caret - prefix.length
        val newText = v.text.substring(0, start) + item.insertText + v.text.substring(caret)
        val newCaret = (start + item.caretOffset).coerceIn(0, newText.length)
        tab.pushUndo(v)
        tab.value = TextFieldValue(newText, TextRange(newCaret))
        dismissCompletions()
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun dismissCompletions() {
        completionsJob?.cancel()
        if (completions.isNotEmpty()) completions.clear()
        showCompletions = false
    }

    // ---- User snippets ----

    fun addSnippet(snippet: UserSnippet) {
        userSnippets.add(snippet)
        persistSnippets()
    }

    fun updateSnippet(index: Int, snippet: UserSnippet) {
        if (index in userSnippets.indices) {
            userSnippets[index] = snippet
            persistSnippets()
        }
    }

    fun deleteSnippet(index: Int) {
        if (index in userSnippets.indices) {
            userSnippets.removeAt(index)
            persistSnippets()
        }
    }

    private fun persistSnippets() {
        viewModelScope.launch { prefs.setUserSnippets(userSnippets.toList()) }
    }

    // ---- Git ----

    /** Re-reads repo status, log and branches for the open folder (no-op if none is open). */
    fun refreshGit() {
        val dir = rootDir
        if (dir == null) {
            gitIsRepo = false
            gitStatus = null
            gitLog.clear()
            gitBranches.clear()
            return
        }
        viewModelScope.launch {
            gitIsRepo = withContext(Dispatchers.IO) { GitRepo.isRepo(dir) }
            if (gitIsRepo) {
                runCatching { loadGitState(dir) }.onFailure { gitMessage = it.message ?: it.toString() }
            } else {
                gitStatus = null
                gitLog.clear()
                gitBranches.clear()
            }
        }
    }

    private suspend fun loadGitState(dir: File) {
        val status = withContext(Dispatchers.IO) { GitRepo.status(dir) }
        val log = withContext(Dispatchers.IO) { GitRepo.log(dir, GIT_LOG_LIMIT) }
        val branches = withContext(Dispatchers.IO) { GitRepo.branches(dir) }
        gitStatus = status
        gitLog.clear(); gitLog.addAll(log)
        gitBranches.clear(); gitBranches.addAll(branches)
    }

    /** Runs a git mutation off-main, surfacing failures in [gitMessage], then refreshes status. */
    private fun gitOp(block: suspend (File) -> Unit) {
        val dir = rootDir ?: return
        viewModelScope.launch {
            gitBusy = true
            gitMessage = null
            runCatching { withContext(Dispatchers.IO) { block(dir) } }
                .onFailure { gitMessage = it.message ?: it.toString() }
            gitIsRepo = withContext(Dispatchers.IO) { GitRepo.isRepo(dir) }
            if (gitIsRepo) runCatching { loadGitState(dir) }
            gitBusy = false
            checkExternalChanges()
        }
    }

    fun gitInit() = gitOp { GitRepo.init(it) }
    fun gitStage(path: String) = gitOp { GitRepo.stage(it, path) }
    fun gitUnstage(path: String) = gitOp { GitRepo.unstage(it, path) }
    fun gitPull() = gitOp { GitRepo.pull(it, gitUsername, gitToken) }
    fun gitPush() = gitOp { GitRepo.push(it, gitUsername, gitToken) }
    fun gitCheckout(name: String) = gitOp { GitRepo.checkout(it, name) }

    fun gitCreateBranch(name: String) = gitOp {
        GitRepo.createBranch(it, name)
        GitRepo.checkout(it, name)
    }

    fun gitCommit(message: String) = gitOp { dir ->
        val name = gitAuthorName.ifBlank { "Code" }
        val email = gitAuthorEmail.ifBlank { "code@localhost" }
        GitRepo.commit(dir, message, name, email)
    }

    /** Clones [url] into [into] and, on success, opens it as the project. */
    fun gitClone(url: String, into: File) {
        viewModelScope.launch {
            gitBusy = true
            gitMessage = null
            val result = runCatching {
                withContext(Dispatchers.IO) { GitRepo.clone(url, into, gitUsername, gitToken) }
            }
            gitBusy = false
            result.onSuccess { openFolder(into) }
                .onFailure { gitMessage = it.message ?: it.toString() }
        }
    }

    fun loadGitDiff(path: String, staged: Boolean) {
        val dir = rootDir ?: return
        viewModelScope.launch {
            gitDiff = runCatching {
                withContext(Dispatchers.IO) { GitRepo.diff(dir, path, staged) }
            }.getOrDefault("")
        }
    }

    /** Loads the side-by-side (aligned rows) diff for [path] into [gitDiffRows]. */
    fun loadSideBySideDiff(path: String, staged: Boolean) {
        val dir = rootDir ?: return
        viewModelScope.launch {
            gitDiffRows = runCatching {
                withContext(Dispatchers.IO) { GitRepo.structuredDiff(dir, path, staged) }
            }.getOrDefault(emptyList())
        }
    }

    fun clearDiffRows() {
        gitDiffRows = null
    }

    fun clearGitDiff() {
        gitDiff = null
    }

    fun clearGitMessage() {
        gitMessage = null
    }

    fun setGitUsername(value: String) {
        _gitUsername.value = value
        viewModelScope.launch { prefs.setGitUsername(value) }
    }

    fun setGitToken(value: String) {
        _gitToken.value = value
        viewModelScope.launch { prefs.setGitToken(value) }
    }

    fun setGitAuthorName(value: String) {
        _gitAuthorName.value = value
        viewModelScope.launch { prefs.setGitAuthorName(value) }
    }

    fun setGitAuthorEmail(value: String) {
        _gitAuthorEmail.value = value
        viewModelScope.launch { prefs.setGitAuthorEmail(value) }
    }

    // ---- Terminal ----

    /** Starts a shell in the open project directory if one isn't already running. */
    fun startTerminal() {
        if (terminal != null) return
        val dir = rootDir ?: return
        terminal = TerminalSession(
            dir = dir,
            onLine = { line -> appendTerminalLine(line) },
            onExit = { terminalRunning = false },
        )
        terminalRunning = true
    }

    fun terminalSend(command: String) {
        if (terminal == null) startTerminal()
        appendTerminalLine("$ $command")
        terminal?.send(command)
    }

    /** Line-based shells can't deliver a real SIGINT, so "stop" kills and restarts the shell. */
    fun terminalInterrupt() {
        terminal?.close()
        terminal = null
        terminalRunning = false
        appendTerminalLine("^C")
        startTerminal()
    }

    fun clearTerminal() {
        terminalLines.clear()
    }

    private fun appendTerminalLine(line: String) {
        terminalLines.add(line)
        while (terminalLines.size > TERMINAL_SCROLLBACK) terminalLines.removeAt(0)
    }

    override fun onCleared() {
        super.onCleared()
        terminal?.close()
    }

    /** Debounced auto-save: writes the current tab a short idle period after the last edit. */
    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            val tab = activeTab ?: return@launch
            if (tab.isDirty) saveTab(tab)
        }
    }

    /** Commits a find/replace edit to [tab] as one undo step, bypassing smart input. */
    private fun commitEdit(tab: OpenTab, new: TextFieldValue) {
        if (new.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = new
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    /** Debounced off-main recompute of diagnostics for the current tab. */
    private fun scheduleDiagnostics() {
        diagnosticsJob?.cancel()
        val tab = currentTab
        if (tab == null) {
            diagnostics.clear()
            return
        }
        val text = tab.value.text
        val language = tab.language
        diagnosticsJob = viewModelScope.launch {
            delay(DIAGNOSTICS_DELAY_MS)
            val result = withContext(Dispatchers.Default) { computeDiagnostics(text, language) }
            diagnostics.clear()
            diagnostics.addAll(result)
        }
    }

    /** Applies a pure whole-line edit as a single undo step. */
    private fun applyLineEdit(transform: (TextFieldValue) -> TextFieldValue) {
        val tab = activeTab ?: return
        val next = transform(tab.value)
        if (next.text == tab.value.text && next.selection == tab.value.selection) return
        tab.pushUndo(tab.value)
        tab.value = next
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun toggleComment() {
        val prefix = activeTab?.language?.lineCommentPrefix ?: return
        applyLineEdit { toggleLineComment(it, prefix) }
    }

    override fun duplicateLine() = applyLineEdit(::duplicateLine)

    override fun moveLineUp() = applyLineEdit(::moveLineUp)

    override fun moveLineDown() = applyLineEdit(::moveLineDown)

    override fun deleteLine() = applyLineEdit(::deleteLine)

    override fun formatDocument() {
        val tab = activeTab ?: return
        val formatted = when (tab.language) {
            Language.JSON -> formatJson(tab.value.text)
            Language.XML -> formatXml(tab.value.text)
            else -> null
        } ?: return
        if (formatted == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(formatted, TextRange(formatted.length))
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun resolveConflicts(resolutions: List<Resolution>) {
        val tab = activeTab ?: return
        val resolved = applyResolutions(tab.value.text, resolutions)
        if (resolved == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(resolved, TextRange(resolved.length))
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    /** Moves the caret to the start of [line] (1-based), without recording an undo step. */
    override fun goToLine(line: Int) {
        val tab = activeTab ?: return
        val offset = lineStartOffset(tab.value.text, line)
        setSelection(TextRange(offset))
    }

    /** Moves the selection without recording an undo step (used by find navigation). */
    override fun setSelection(range: TextRange) {
        val tab = activeTab ?: return
        tab.value = tab.value.copy(selection = range)
    }

    // ---- Folding (experimental editor) ----

    override fun toggleFold(headerLine: Int) {
        val tab = activeTab ?: return
        tab.foldedHeaders =
            if (headerLine in tab.foldedHeaders) tab.foldedHeaders - headerLine
            else tab.foldedHeaders + headerLine
        persistFoldState(tab)
    }

    override fun foldAllInTab() {
        val tab = activeTab ?: return
        tab.foldedHeaders = computeFoldRegions(tab.value.text).map { it.startLine }.toSet()
        persistFoldState(tab)
    }

    override fun unfoldAll() {
        val tab = activeTab ?: return
        tab.foldedHeaders = emptySet()
        persistFoldState(tab)
    }

    /** Records [tab]'s fold headers in the per-path store and persists it (file-backed tabs only). */
    private fun persistFoldState(tab: OpenTab) {
        val path = tab.file?.absolutePath ?: return
        if (tab.foldedHeaders.isEmpty()) foldStateByPath.remove(path)
        else foldStateByPath[path] = tab.foldedHeaders
        viewModelScope.launch { prefs.setFoldState(foldStateByPath.mapValues { it.value.toList() }) }
    }

    override fun undo() {
        activeTab?.undo()
    }

    override fun redo() {
        activeTab?.redo()
    }

    override fun save() {
        val tab = activeTab ?: return
        saveTab(tab)
    }

    override fun saveAll() {
        for (tab in tabs) if (tab.file != null && tab.isDirty) saveTab(tab)
    }

    /** Encodes [tab] with its stored charset/BOM/line-ending and writes it, refreshing the snapshot. */
    private fun saveTab(tab: OpenTab) {
        val file = tab.file ?: return // external read-only tabs have no save target
        var textToSave = tab.value.text
        if (trimTrailingOnSave) textToSave = trimTrailingWhitespace(textToSave)
        if (finalNewlineOnSave) textToSave = ensureFinalNewline(textToSave)
        // Reflect the transformed text back into the buffer so the tab isn't left "dirty".
        if (textToSave != tab.value.text) {
            val sel = tab.value.selection
            val clamped = TextRange(
                sel.start.coerceAtMost(textToSave.length),
                sel.end.coerceAtMost(textToSave.length),
            )
            tab.value = TextFieldValue(textToSave, clamped)
        }
        val bytes = TextEncoding.encode(textToSave, tab.charset, tab.lineEnding, tab.hadBom)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { FileFiles.writeBytes(file, bytes) }.isSuccess
            }
            if (ok) {
                tab.savedText = textToSave
                tab.changedOnDisk = false
                val (modified, length) = withContext(Dispatchers.IO) { file.lastModified() to file.length() }
                tab.diskModified = modified
                tab.diskLength = length
                if (gitIsRepo) refreshGit()
            }
        }
    }

    /**
     * Compares each open file-backed tab against its on-disk snapshot (called on ON_RESUME and after
     * git actions). A clean tab is silently reloaded; a dirty tab raises its "changed on disk" banner.
     */
    fun checkExternalChanges() {
        if (tabs.isEmpty()) return
        viewModelScope.launch {
            for (tab in tabs) {
                val file = tab.file ?: continue
                val snapshot = withContext(Dispatchers.IO) {
                    if (file.exists()) file.lastModified() to file.length() else null
                } ?: continue
                if (snapshot.first == tab.diskModified && snapshot.second == tab.diskLength) continue
                if (tab.isDirty) tab.changedOnDisk = true else applyReload(tab)
            }
        }
    }

    override fun reloadFromDisk() {
        val tab = currentTab ?: return
        viewModelScope.launch { applyReload(tab) }
    }

    override fun dismissDiskChange() {
        val tab = currentTab ?: return
        viewModelScope.launch {
            val file = tab.file ?: return@launch
            val (modified, length) = withContext(Dispatchers.IO) { file.lastModified() to file.length() }
            tab.diskModified = modified
            tab.diskLength = length
            tab.changedOnDisk = false
        }
    }

    /** Replaces [tab]'s buffer with the current disk contents and refreshes its snapshot. */
    private suspend fun applyReload(tab: OpenTab) {
        val file = tab.file ?: return
        val loaded = loadFile(file)
        tab.value = TextFieldValue(loaded.decoded.text)
        tab.savedText = loaded.decoded.text
        tab.charset = loaded.decoded.charset
        tab.lineEnding = loaded.decoded.lineEnding
        tab.hadBom = loaded.decoded.hadBom
        tab.diskModified = loaded.modified
        tab.diskLength = loaded.length
        tab.changedOnDisk = false
        scheduleDiagnostics()
    }

    /** Inserts [insert] at the caret, replacing any current selection (used by the Tab button). */
    override fun insertText(insert: String) {
        val tab = activeTab ?: return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + insert + v.text.substring(end)
        editTab(tab, TextFieldValue(newText, TextRange(start + insert.length)), isPrimary = tab === currentTab)
    }

    override fun toggleSoftWrap() {
        softWrap = !softWrap
        viewModelScope.launch { prefs.setSoftWrap(softWrap) }
    }

    override fun setFontSize(size: Int) {
        _fontSize.value = size
        viewModelScope.launch { prefs.setFontSize(size) }
    }

    override fun setTabWidth(width: Int) {
        _tabWidth.value = width
        viewModelScope.launch { prefs.setTabWidth(width) }
    }

    override fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    override fun setAutoIndent(enabled: Boolean) {
        _autoIndent.value = enabled
        viewModelScope.launch { prefs.setAutoIndent(enabled) }
    }

    override fun setAutoCloseBrackets(enabled: Boolean) {
        _autoCloseBrackets.value = enabled
        viewModelScope.launch { prefs.setAutoCloseBrackets(enabled) }
    }

    fun setAutoSave(enabled: Boolean) {
        _autoSave.value = enabled
        viewModelScope.launch { prefs.setAutoSave(enabled) }
        if (enabled) scheduleAutoSave()
    }

    fun setTrimTrailingOnSave(enabled: Boolean) {
        _trimTrailingOnSave.value = enabled
        viewModelScope.launch { prefs.setTrimTrailingOnSave(enabled) }
    }

    fun setFinalNewlineOnSave(enabled: Boolean) {
        _finalNewlineOnSave.value = enabled
        viewModelScope.launch { prefs.setFinalNewlineOnSave(enabled) }
    }

    fun setEditorTheme(theme: String) {
        _editorTheme.value = theme
        viewModelScope.launch { prefs.setEditorTheme(theme) }
    }

    fun setExperimentalEditor(enabled: Boolean) {
        _experimentalEditor.value = enabled
        viewModelScope.launch { prefs.setExperimentalEditor(enabled) }
    }

    fun setShowWhitespace(enabled: Boolean) {
        _showWhitespace.value = enabled
        viewModelScope.launch { prefs.setShowWhitespace(enabled) }
    }

    fun setShowIndentGuides(enabled: Boolean) {
        _showIndentGuides.value = enabled
        viewModelScope.launch { prefs.setShowIndentGuides(enabled) }
    }

    fun setShowMinimap(enabled: Boolean) {
        _showMinimap.value = enabled
        viewModelScope.launch { prefs.setShowMinimap(enabled) }
    }

    // ---- Find & replace ----

    override fun replaceRange(range: IntRange, replacement: String) {
        val tab = activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last + 1)
        commitEdit(tab, TextFieldValue(newText, TextRange(range.first + replacement.length)))
    }

    override fun replaceAll(matches: List<IntRange>, replacement: String) {
        val tab = activeTab ?: return
        if (matches.isEmpty()) return
        val text = tab.value.text
        val sb = StringBuilder(text.length)
        var last = 0
        for (m in matches.sortedBy { it.first }) {
            if (m.first < last) continue
            sb.append(text, last, m.first)
            sb.append(replacement)
            last = m.last + 1
        }
        sb.append(text, last, text.length)
        commitEdit(tab, TextFieldValue(sb.toString(), TextRange(sb.length)))
    }

    private fun buildRegex(pattern: String, caseSensitive: Boolean): Regex =
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

    override fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val sub = text.substring(range.first, range.last + 1)
        val replaced = runCatching { regex.replace(sub, replacement) }.getOrNull() ?: return
        replaceRange(range, replaced)
    }

    override fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = activeTab ?: return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val text = tab.value.text
        val newText = runCatching { regex.replace(text, replacement) }.getOrNull() ?: return
        if (newText == text) return
        commitEdit(tab, TextFieldValue(newText, TextRange(newText.length)))
    }

    // ---- Project search ----

    override fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) {
        val root = rootDir ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.clear()
            isSearching = false
            return
        }
        isSearching = true
        searchResults.clear()
        searchJob = viewModelScope.launch {
            val collected = withContext(Dispatchers.IO) {
                val out = ArrayList<SearchResult>()
                val ignore = loadGitIgnore(root)
                val stack = ArrayDeque<File>()
                stack.addLast(root)
                while (stack.isNotEmpty() && out.size < MAX_SEARCH_RESULTS) {
                    coroutineContext.ensureActive()
                    val dir = stack.removeLast()
                    val children = runCatching { FileFiles.listChildren(dir) }.getOrDefault(emptyList())
                    for (child in children) {
                        if (out.size >= MAX_SEARCH_RESULTS) break
                        val rel = relativeTo(root, child.file)
                        if (child.isDirectory) {
                            if (child.name !in SKIP_DIRS && !ignore.isIgnored(rel, true)) stack.addLast(child.file)
                            continue
                        }
                        if (ignore.isIgnored(rel, false)) continue
                        if (Language.fromFileName(child.name) == Language.PLAINTEXT) continue
                        if (child.file.length() > MAX_SEARCH_FILE_SIZE) continue
                        val text = runCatching { FileFiles.readText(child.file) }.getOrNull() ?: continue
                        val matches = findLineMatches(text, query, caseSensitive, useRegex, MAX_MATCHES_PER_FILE)
                        for (m in matches) {
                            out.add(SearchResult(child.file.absolutePath, child.name, m.line, m.preview))
                            if (out.size >= MAX_SEARCH_RESULTS) break
                        }
                    }
                }
                out
            }
            searchResults.clear()
            searchResults.addAll(collected)
            isSearching = false
        }
    }

    override fun openSearchResult(result: SearchResult) {
        addRecentFile(result.path)
        val existing = tabs.indexOfFirst { it.key == result.path }
        if (existing >= 0) {
            currentIndex = existing
            goToLine(result.line)
            return
        }
        val file = File(result.path)
        viewModelScope.launch {
            tabs.add(makeFileTab(file, result.name))
            currentIndex = tabs.lastIndex
            goToLine(result.line)
            saveSession()
        }
    }

    // ---- Quick-open ----

    override fun openPath(path: String) = openFile(File(path))

    /** Rebuilds the cached project-file list by walking the open folder off the main thread. */
    override fun refreshProjectFiles() {
        val root = rootDir ?: return
        projectFilesJob?.cancel()
        projectFilesJob = viewModelScope.launch {
            val collected = withContext(Dispatchers.IO) {
                val out = ArrayList<ProjectFileEntry>()
                val ignore = loadGitIgnore(root)
                val stack = ArrayDeque<File>()
                stack.addLast(root)
                while (stack.isNotEmpty() && out.size < MAX_PROJECT_FILES) {
                    coroutineContext.ensureActive()
                    val dir = stack.removeLast()
                    val children = runCatching { FileFiles.listChildren(dir) }.getOrDefault(emptyList())
                    for (child in children) {
                        if (out.size >= MAX_PROJECT_FILES) break
                        val rel = relativeTo(root, child.file)
                        if (child.isDirectory) {
                            if (child.name !in SKIP_DIRS && !ignore.isIgnored(rel, true)) stack.addLast(child.file)
                            continue
                        }
                        if (ignore.isIgnored(rel, false)) continue
                        out.add(toProjectEntry(child.file))
                    }
                }
                out.sortedBy { it.relativePath.lowercase() }
            }
            projectFiles.clear()
            projectFiles.addAll(collected)
        }
    }

    /** Reads and parses the project root `.gitignore`, or returns an empty matcher. */
    private fun loadGitIgnore(root: File): GitIgnore = runCatching {
        val f = File(root, ".gitignore")
        if (f.isFile) parseGitIgnore(f.readText()) else GitIgnore.EMPTY
    }.getOrDefault(GitIgnore.EMPTY)

    /** Path of [file] relative to [root] (falling back to the bare name when not under root). */
    private fun relativeTo(root: File, file: File): String {
        val rp = root.absolutePath
        val ap = file.absolutePath
        return if (ap.startsWith(rp + File.separator)) ap.substring(rp.length + 1) else file.name
    }

    /** Builds the display entry for [file], with a path relative to the open root when possible. */
    private fun toProjectEntry(file: File): ProjectFileEntry {
        val abs = file.absolutePath
        val rootPath = rootDir?.absolutePath
        val rel = if (rootPath != null && abs.startsWith(rootPath + File.separator)) {
            abs.substring(rootPath.length + 1)
        } else {
            abs
        }
        return ProjectFileEntry(abs, file.name, rel)
    }

    /** Records [path] as the most-recently-opened file and persists the capped list. */
    private fun addRecentFile(path: String) {
        recentPaths.remove(path)
        recentPaths.add(0, path)
        while (recentPaths.size > MAX_RECENT_FILES) recentPaths.removeAt(recentPaths.lastIndex)
        viewModelScope.launch { prefs.setRecentFiles(recentPaths.toList()) }
    }

    private companion object {
        const val AUTO_SAVE_DELAY_MS = 1500L
        const val DIAGNOSTICS_DELAY_MS = 400L
        const val COMPLETIONS_DELAY_MS = 150L
        const val MIN_COMPLETION_PREFIX = 1
        const val MAX_COMPLETIONS = 50
        // Buffers larger than this are left out of the identifier scan. A multi-megabyte file has
        // no useful completions in it anyway, and scanning it on every keystroke burns a core.
        const val MAX_COMPLETION_BUFFER_CHARS = 1_000_000
        const val GIT_LOG_LIMIT = 30
        const val TERMINAL_SCROLLBACK = 2000
        const val MAX_SEARCH_RESULTS = 500
        const val MAX_MATCHES_PER_FILE = 50
        const val MAX_SEARCH_FILE_SIZE = 500_000
        const val MAX_PROJECT_FILES = 5000
        const val MAX_RECENT_FILES = 15
        val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")
    }
}
