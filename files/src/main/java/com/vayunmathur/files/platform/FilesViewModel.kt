package com.vayunmathur.files.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Migrated from okio FileSystem/Path/openZip to java.io.File + java.util.zip.ZipFile.
 * Real FS uses File; zip browsing is virtual backed by ZipFile entries.
 */
data class FileBrowserItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val realFile: File?,
    val zipInnerPath: String?,
    val key: String,
    val lastModified: Long = 0L,
)

class FilesViewModel(application: Application) : AndroidViewModel(application), FilesActions {

    private val prefs =
        application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    private val _isFilesGranted = MutableStateFlow(Environment.isExternalStorageManager())
    val isFilesGranted: StateFlow<Boolean> = _isFilesGranted.asStateFlow()

    fun refreshPermissions() {
        val granted = Environment.isExternalStorageManager()
        if (_isFilesGranted.value != granted) {
            _isFilesGranted.value = granted
            if (granted) {
                loadDirectory()
                loadHome()
            }
        }
    }

    private val _hasPromptedNotifications =
        MutableStateFlow(prefs.getBoolean("has_prompted_notifications", false))
    val hasPromptedNotifications: StateFlow<Boolean> = _hasPromptedNotifications.asStateFlow()

    fun setNotificationsPrompted() {
        prefs.edit { putBoolean("has_prompted_notifications", true) }
        _hasPromptedNotifications.value = true
    }

    // ---- Navigation ----
    val rootDirectory: File = Environment.getExternalStorageDirectory()

    private val _currentDirectory = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    private val _zipPath = MutableStateFlow<File?>(null)
    val zipPath: StateFlow<File?> = _zipPath.asStateFlow()

    private val _zipInternalPath = MutableStateFlow("")
    val zipInternalPath: StateFlow<String> = _zipInternalPath.asStateFlow()

    fun isZipMode(): Boolean = _zipPath.value != null

    // ---- Listing ----
    private val _entries =
        MutableStateFlow<Pair<List<FileBrowserItem>, List<FileBrowserItem>>>(emptyList<FileBrowserItem>() to emptyList())
    val entries: StateFlow<Pair<List<FileBrowserItem>, List<FileBrowserItem>>> = _entries.asStateFlow()

    // ---- Selection (only valid in real FS mode) ----
    private val _selectedPaths = MutableStateFlow<Set<FileBrowserItem>>(emptySet())
    val selectedPaths: StateFlow<Set<FileBrowserItem>> = _selectedPaths.asStateFlow()

    override fun clearSelection() {
        if (_selectedPaths.value.isNotEmpty()) _selectedPaths.value = emptySet()
    }

    override fun addToSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        _selectedPaths.value = _selectedPaths.value + item
    }

    override fun toggleSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        val current = _selectedPaths.value
        _selectedPaths.value = if (current.any { it.key == item.key }) {
            current.filterNot { it.key == item.key }.toSet()
        } else {
            current + item
        }
    }

    override fun selectAll() {
        if (isZipMode()) return
        val (dirs, files) = _entries.value
        _selectedPaths.value = (dirs + files).toSet()
    }

    // ---- Sort, view mode, search ----
    private val _sortBy = MutableStateFlow(
        runCatching { SortBy.valueOf(prefs.getString("sort_by", null) ?: "NAME") }
            .getOrDefault(SortBy.NAME)
    )
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    private val _sortAscending = MutableStateFlow(prefs.getBoolean("sort_ascending", true))
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    private val _viewMode = MutableStateFlow(
        runCatching { ViewMode.valueOf(prefs.getString("view_mode", null) ?: "LIST") }
            .getOrDefault(ViewMode.LIST)
    )
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    override fun setSortBy(sortBy: SortBy) {
        if (_sortBy.value == sortBy) return
        _sortBy.value = sortBy
        prefs.edit { putString("sort_by", sortBy.name) }
        loadDirectory()
    }

    override fun toggleSortDirection() {
        setSortAscending(!_sortAscending.value)
    }

    override fun setSortAscending(ascending: Boolean) {
        if (_sortAscending.value == ascending) return
        _sortAscending.value = ascending
        prefs.edit { putBoolean("sort_ascending", ascending) }
        loadDirectory()
    }

    private val downloadsDir: File get() = File(rootDirectory, "Download")

    /**
     * Apply the sensible default sort when *entering* a folder: the Downloads folder shows
     * newest-first (by date), which is what people usually want there; every other folder
     * uses the persisted global sort. This only sets the in-memory sort, so an explicit
     * choice via the menu (which persists) still wins while you stay in the folder.
     */
    private fun applyDirDefaults(path: File) {
        if (path.absolutePath == downloadsDir.absolutePath) {
            _sortBy.value = SortBy.DATE
            _sortAscending.value = false
        } else {
            _sortBy.value = runCatching {
                SortBy.valueOf(prefs.getString("sort_by", null) ?: "NAME")
            }.getOrDefault(SortBy.NAME)
            _sortAscending.value = prefs.getBoolean("sort_ascending", true)
        }
    }

    override fun toggleViewMode() {
        val next = if (_viewMode.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        _viewMode.value = next
        prefs.edit { putString("view_mode", next.name) }
    }

    override fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ---- Hidden files ----
    private val _showHidden = MutableStateFlow(prefs.getBoolean("show_hidden", false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    override fun toggleHidden() {
        val next = !_showHidden.value
        _showHidden.value = next
        prefs.edit { putBoolean("show_hidden", next) }
        loadDirectory()
    }

    // ---- Create ----
    override fun createFolder(name: String) {
        if (isZipMode() || name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = uniqueDestination(_currentDirectory.value, name.trim())
                if (dir.mkdirs()) loadDirectory()
                else emit(getApplication<Application>().getString(R.string.create_failed))
            } catch (e: Exception) {
                emitMoveFailed(e)
            }
        }
    }

    override fun createFile(name: String) {
        if (isZipMode() || name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = uniqueDestination(_currentDirectory.value, name.trim())
                if (file.createNewFile()) loadDirectory()
                else emit(getApplication<Application>().getString(R.string.create_failed))
            } catch (e: Exception) {
                emitMoveFailed(e)
            }
        }
    }

    override fun openWith(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        val file = item.realFile ?: return
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            emitMoveFailed(e)
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        viewModelScope.launch { _intents.emit(chooser) }
    }

    // ---- Home screen: storage, recents, bookmarks, categories ----
    private val _atHome = MutableStateFlow(true)
    val atHome: StateFlow<Boolean> = _atHome.asStateFlow()

    private val _categoryTitle = MutableStateFlow<String?>(null)
    val categoryTitle: StateFlow<String?> = _categoryTitle.asStateFlow()

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = _storage.asStateFlow()

    private val _recents = MutableStateFlow<List<FileBrowserItem>>(emptyList())
    val recents: StateFlow<List<FileBrowserItem>> = _recents.asStateFlow()

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<FileBrowserItem>> = _bookmarks.asStateFlow()

    fun loadHome() {
        viewModelScope.launch(Dispatchers.IO) {
            _storage.value = readStorage()
            _bookmarks.value = loadBookmarks()
            _recents.value = queryRecents()
        }
    }

    override fun goHome() {
        _atHome.value = true
        _categoryTitle.value = null
        _zipPath.value = null
        _zipInternalPath.value = ""
        clearSelection()
        observerJob?.cancel()
        loadHome()
    }

    override fun openInternalStorage() {
        _categoryTitle.value = null
        navigateTo(rootDirectory)
    }

    override fun openBookmark(path: File) {
        _categoryTitle.value = null
        navigateTo(path)
    }

    override fun openCategory(category: FileCategory) {
        if (category == FileCategory.DOWNLOADS) {
            val dl = File(rootDirectory, "Download")
            openBookmark(if (dl.isDirectory) dl else rootDirectory)
            return
        }
        _atHome.value = false
        _zipPath.value = null
        _categoryTitle.value = getApplication<Application>().getString(categoryLabel(category))
        clearSelection()
        observerJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _entries.value = emptyList<FileBrowserItem>() to sortItems(queryCategory(category))
        }
    }

    override fun addBookmark(item: FileBrowserItem) {
        val path = item.realFile ?: return
        val set = (prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).toMutableSet()
        set.add(path.absolutePath)
        prefs.edit { putStringSet("bookmarks", set) }
        _bookmarks.value = loadBookmarks()
        emit(getApplication<Application>().getString(R.string.bookmark_added))
    }

    override fun removeBookmark(path: File) {
        val set = (prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(path.absolutePath)
        prefs.edit { putStringSet("bookmarks", set) }
        _bookmarks.value = loadBookmarks()
    }

    private fun loadBookmarks(): List<FileBrowserItem> {
        val saved = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()
        return saved.map { File(it) }
            .filter { it.exists() }
            .sortedBy { it.name.lowercase() }
            .map { it.toItem() }
    }

    private fun readStorage(): StorageInfo = try {
        val stat = StatFs(rootDirectory.absolutePath)
        StorageInfo(totalBytes = stat.totalBytes, freeBytes = stat.availableBytes)
    } catch (_: Exception) {
        StorageInfo(0, 0)
    }

    private fun categoryLabel(c: FileCategory): Int = when (c) {
        FileCategory.IMAGES -> R.string.cat_images
        FileCategory.VIDEOS -> R.string.cat_videos
        FileCategory.AUDIO -> R.string.cat_audio
        FileCategory.DOCUMENTS -> R.string.cat_documents
        FileCategory.DOWNLOADS -> R.string.cat_downloads
    }

    private fun queryCategory(category: FileCategory): List<FileBrowserItem> = when (category) {
        FileCategory.IMAGES -> queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, 2000)
        FileCategory.VIDEOS -> queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, 2000)
        FileCategory.AUDIO -> queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, 2000)
        FileCategory.DOCUMENTS -> {
            val mimes = arrayOf(
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain", "text/markdown", "text/csv", "application/rtf",
            )
            val selection = mimes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE}=?" }
            queryMedia(MediaStore.Files.getContentUri("external"), selection, mimes, 2000)
        }
        FileCategory.DOWNLOADS -> emptyList()
    }

    private fun queryRecents(): List<FileBrowserItem> =
        queryMedia(MediaStore.Files.getContentUri("external"), null, null, 40)

    private fun queryMedia(
        uri: Uri,
        selection: String?,
        args: Array<String>?,
        limit: Int,
    ): List<FileBrowserItem> {
        val ctx = getApplication<Application>()
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = mutableListOf<FileBrowserItem>()
        try {
            ctx.contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (c.moveToNext() && out.size < limit) {
                    val path = c.getString(idx) ?: continue
                    val f = File(path)
                    if (f.isFile) out.add(f.toItem())
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun File.toItem() = FileBrowserItem(
        name = name,
        isDirectory = isDirectory,
        size = if (isFile) length() else null,
        realFile = this,
        zipInnerPath = null,
        key = absolutePath,
        lastModified = lastModified(),
    )

    // ---- Clipboard (copy/cut/paste) ----
    private val _clipboard = MutableStateFlow<List<File>>(emptyList())
    val clipboard: StateFlow<List<File>> = _clipboard.asStateFlow()

    private val _clipboardIsCut = MutableStateFlow(false)
    val clipboardIsCut: StateFlow<Boolean> = _clipboardIsCut.asStateFlow()

    override fun copySelection() {
        if (isZipMode()) return
        val files = _selectedPaths.value.mapNotNull { it.realFile }
        if (files.isEmpty()) return
        _clipboard.value = files
        _clipboardIsCut.value = false
        clearSelection()
        emit(getApplication<Application>().getString(R.string.copied_n, files.size))
    }

    override fun cutSelection() {
        if (isZipMode()) return
        val files = _selectedPaths.value.mapNotNull { it.realFile }
        if (files.isEmpty()) return
        _clipboard.value = files
        _clipboardIsCut.value = true
        clearSelection()
        emit(getApplication<Application>().getString(R.string.cut_n, files.size))
    }

    override fun clearClipboard() {
        _clipboard.value = emptyList()
        _clipboardIsCut.value = false
    }

    override fun pasteHere() {
        if (isZipMode()) return
        val sources = _clipboard.value
        if (sources.isEmpty()) return
        val target = _currentDirectory.value
        val isCut = _clipboardIsCut.value
        viewModelScope.launch(Dispatchers.IO) {
            var lastError: Exception? = null
            sources.forEach { source ->
                try {
                    if (!source.exists()) return@forEach
                    // Don't paste a folder into itself or a descendant.
                    if (target.absolutePath == source.absolutePath ||
                        target.absolutePath.startsWith(source.absolutePath + "/")
                    ) return@forEach
                    val dest = uniqueDestination(target, source.name)
                    if (isCut) {
                        source.atomicMoveTo(dest)
                    } else if (source.isDirectory) {
                        source.copyRecursively(dest, overwrite = false)
                    } else {
                        source.copyTo(dest, overwrite = false)
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (isCut) clearClipboard()
            loadDirectory()
            lastError?.let { emitMoveFailed(it) }
        }
    }

    override fun shareSelection() {
        val ctx = getApplication<Application>()
        val files = _selectedPaths.value.mapNotNull { it.realFile }.filter { it.isFile }
        if (files.isEmpty()) return
        val uris = try {
            ArrayList(files.map {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
            })
        } catch (e: Exception) {
            emitMoveFailed(e)
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        clearSelection()
        viewModelScope.launch {
            _intents.emit(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun mimeFor(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"

    /** A destination in [dir] named [name], suffixed with " (n)" if that already exists. */
    private fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    // ---- Share URIs ----
    private val _incomingUris = MutableStateFlow<List<Uri>?>(null)
    val incomingUris: StateFlow<List<Uri>?> = _incomingUris.asStateFlow()

    fun setIncomingUris(uris: List<Uri>) { _incomingUris.value = uris }
    fun clearIncomingUris() { _incomingUris.value = null }

    // ---- Events ----
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = _intents.asSharedFlow()

    private var observerJob: Job? = null
    private var loadJob: Job? = null
    // Guards against a stale reload (e.g. a FileObserver event that snapshots the
    // directory mid-deletion) overwriting a newer one. Only the latest load wins.
    private val loadGeneration = AtomicInteger(0)

    init {
        loadDirectory()
        restartObserver()
        loadHome()
    }

    fun loadDirectory() {
        val gen = loadGeneration.incrementAndGet()
        loadJob?.cancel()
        val zipFile = _zipPath.value
        val dir = _currentDirectory.value
        val inner = _zipInternalPath.value
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val listed = if (zipFile == null) listRealDir(dir) else listZipDir(zipFile, inner)
            val sorted = sortEntries(listed)
            if (gen == loadGeneration.get()) {
                _entries.value = sorted
            }
        }
    }

    private fun sortEntries(
        entries: Pair<List<FileBrowserItem>, List<FileBrowserItem>>
    ): Pair<List<FileBrowserItem>, List<FileBrowserItem>> =
        sortItems(entries.first) to sortItems(entries.second)

    private fun sortItems(items: List<FileBrowserItem>): List<FileBrowserItem> {
        val cmp: Comparator<FileBrowserItem> = when (_sortBy.value) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.SIZE -> compareBy { it.size ?: -1L }
            SortBy.TYPE -> compareBy<FileBrowserItem> {
                it.name.substringAfterLast('.', "").lowercase()
            }.thenBy { it.name.lowercase() }
        }
        val sorted = items.sortedWith(cmp)
        return if (_sortAscending.value) sorted else sorted.reversed()
    }

    private fun restartObserver() {
        observerJob?.cancel()
        if (isZipMode()) {
            observerJob = null
            return
        }
        val dir = _currentDirectory.value
        observerJob = viewModelScope.launch {
            val observer = object : FileObserver(dir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) { loadDirectory() }
            }
            observer.startWatching()
            try { awaitCancellation() } finally { observer.stopWatching() }
        }
    }

    override fun navigateTo(path: File) {
        if (isZipMode()) {
            _zipPath.value = null
            _zipInternalPath.value = ""
        }
        _atHome.value = false
        _categoryTitle.value = null
        _currentDirectory.value = path
        applyDirDefaults(path)
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    override fun navigateIntoZipDir(dirName: String) {
        val current = _zipInternalPath.value
        val newPath = if (current.isEmpty()) dirName else "$current/$dirName"
        _zipInternalPath.value = newPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipInternalPath(fullInternalPath: String) {
        _zipInternalPath.value = fullInternalPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipParentRealFolder(target: File) {
        // breadcrumb click on real-FS part while in zip mode → exit zip
        _zipPath.value = null
        _zipInternalPath.value = ""
        _currentDirectory.value = target
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    override fun handleBack(): Boolean {
        if (_selectedPaths.value.isNotEmpty()) { clearSelection(); return true }
        if (_categoryTitle.value != null) { goHome(); return true }
        val z = _zipPath.value
        when {
            z != null -> {
                val internal = _zipInternalPath.value
                if (internal.isEmpty()) {
                    val parent = z.parentFile ?: rootDirectory
                    _zipPath.value = null
                    _currentDirectory.value = parent
                    restartObserver()
                } else {
                    val parentInternal = if (internal.contains("/")) internal.substringBeforeLast("/") else ""
                    _zipInternalPath.value = parentInternal
                }
                loadDirectory()
            }
            _currentDirectory.value.absolutePath != rootDirectory.absolutePath -> {
                _currentDirectory.value = _currentDirectory.value.parentFile ?: _currentDirectory.value
                applyDirDefaults(_currentDirectory.value)
                loadDirectory()
                restartObserver()
            }
            // At the storage root, Back returns to the home screen.
            else -> goHome()
        }
        return true
    }

    override fun rename(item: FileBrowserItem, newName: String) {
        if (isZipMode()) return
        val path = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(path.parentFile, newName)
                path.atomicMoveTo(target)
                clearSelection()
                loadDirectory()
            } catch (e: Exception) { emitMoveFailed(e) }
        }
    }

    override fun deleteSelection() {
        if (isZipMode()) return
        val selection = _selectedPaths.value.mapNotNull { it.realFile }
        viewModelScope.launch(Dispatchers.IO) {
            selection.forEach { it.deleteRecursively() }
            clearSelection()
            loadDirectory()
        }
    }

    override fun moveInto(sources: List<File>, target: File) {
        if (isZipMode()) return
        if (!target.isDirectory) return
        moveFiles(sources, target) { source -> source != target && !target.absolutePath.startsWith(source.absolutePath) }
    }

    override fun moveToBreadcrumb(sources: List<File>, target: File) {
        if (isZipMode()) return
        moveFiles(sources, target) { source -> source.parentFile != target && source != target }
    }

    private fun moveFiles(sources: List<File>, target: File, canMove: (File) -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var movedAny = false
            var lastError: Exception? = null
            sources.forEach { source ->
                if (canMove(source)) {
                    try {
                        val dest = File(target, source.name)
                        source.atomicMoveTo(dest)
                        movedAny = true
                    } catch (e: Exception) { lastError = e }
                }
            }
            if (movedAny) { clearSelection(); loadDirectory() }
            lastError?.let { emitMoveFailed(it) }
        }
    }

    override fun openZipFile(item: FileBrowserItem) {
        val file = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipFile(file).use { _ -> }
                _zipPath.value = file
                _zipInternalPath.value = ""
                _currentDirectory.value = File("/") // placeholder not used in zip mode
                clearSelection()
                loadDirectory()
                restartObserver()
            } catch (e: Exception) {
                emit(getApplication<Application>().getString(R.string.could_not_open_zip, e.localizedMessage))
            }
        }
    }

    override fun archive(archiveName: String) {
        if (isZipMode()) return
        val ctx = getApplication<Application>()
        val sources = _selectedPaths.value.mapNotNull { it.realFile?.absolutePath }.toTypedArray()
        val destFileName = if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip"
        val destFile = File(_currentDirectory.value, destFileName)
        if (sources.isEmpty()) return
        val zipWork = OneTimeWorkRequestBuilder<ZipWorker>().setInputData(
            workDataOf("source_paths" to sources, "dest_path" to destFile.absolutePath)
        ).build()
        WorkManager.getInstance(ctx).enqueue(zipWork)
        clearSelection()
        emit(ctx.getString(R.string.archiving_started))
    }

    fun unzip(zipItem: FileBrowserItem, destPath: File) {
        val zipFile = zipItem.realFile ?: return
        val ctx = getApplication<Application>()
        val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>().setInputData(
            workDataOf("zip_path" to zipFile.absolutePath, "dest_path" to destPath.absolutePath)
        ).build()
        WorkManager.getInstance(ctx).enqueue(unzipWork)
        clearSelection()
        emit(ctx.getString(R.string.unzipping_started_to, destPath.name))
    }

    override fun saveIncomingUris() {
        if (isZipMode()) return
        val ctx = getApplication<Application>()
        val uris = _incomingUris.value ?: return
        val target = _currentDirectory.value
        viewModelScope.launch(Dispatchers.IO) {
            var lastError: Exception? = null
            uris.forEach { uri ->
                try {
                    saveUriToPath(ctx, uri, target)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            clearIncomingUris()
            loadDirectory()
            lastError?.let { emitMoveFailed(it) }
                ?: viewModelScope.launch { _snackbarMessages.emit(ctx.getString(R.string.files_saved)) }
        }
    }

    override fun openFile(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = item.realFile ?: return
        val extension = file.extension
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            emitMoveFailed(e)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        viewModelScope.launch { _intents.emit(intent) }
    }

    // ---- APK install ----
    /** The APK waiting to be installed once the user grants the "install unknown apps" permission. */
    private var pendingApkInstall: File? = null

    private val _installPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when an APK was tapped but Files lacks permission to install; UI opens settings. */
    val installPermissionRequests: SharedFlow<Unit> = _installPermissionRequests.asSharedFlow()

    override fun installApk(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = item.realFile ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) {
            launchApkInstall(file)
        } else {
            pendingApkInstall = file
            emit(ctx.getString(R.string.install_permission_needed))
            _installPermissionRequests.tryEmit(Unit)
        }
    }

    /** Called by the UI after returning from the "install unknown apps" settings screen. */
    fun onInstallPermissionResult() {
        val ctx = getApplication<Application>()
        val file = pendingApkInstall ?: return
        pendingApkInstall = null
        if (ctx.packageManager.canRequestPackageInstalls()) {
            launchApkInstall(file)
        } else {
            emit(ctx.getString(R.string.install_permission_denied))
        }
    }

    private fun launchApkInstall(file: File) {
        val ctx = getApplication<Application>()
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            emitMoveFailed(e)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        viewModelScope.launch { _intents.emit(intent) }
    }

    fun showMessage(message: String) { emit(message) }
    private fun emit(message: String) { viewModelScope.launch { _snackbarMessages.emit(message) } }
    private fun emitMoveFailed(e: Exception) {
        emit(getApplication<Application>().getString(R.string.move_failed, e.localizedMessage))
    }

    private fun saveUriToPath(context: Context, uri: Uri, targetDir: File) {
        val name = getFileName(context, uri) ?: "shared_file_${System.currentTimeMillis()}"
        val targetFile = File(targetDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { out -> input.copyTo(out) }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) return cursor.getString(nameIndex)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun listRealDir(dir: File): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
        val all = dir.listFiles()?.toList() ?: emptyList()
        val visible = if (_showHidden.value) all else all.filterNot { it.name.startsWith(".") }
        val items = visible.map { it.toItem() }
        return items.partition { it.isDirectory }
    }

    private fun listZipDir(zipFile: File, internalDir: String): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
        return try {
            ZipFile(zipFile).use { zf ->
                val prefix = if (internalDir.isEmpty()) "" else "$internalDir/"
                val dirMap = mutableMapOf<String, FileBrowserItem>()
                val fileList = mutableListOf<FileBrowserItem>()
                for (entry in zf.entries()) {
                    val rawName = entry.name
                    val normalized = rawName.trimEnd('/')
                    if (normalized.isEmpty()) continue
                    if (normalized == internalDir) continue
                    if (internalDir.isNotEmpty() && !rawName.startsWith(prefix) && !normalized.startsWith(prefix)) continue
                    val remainder = if (prefix.isEmpty()) normalized else {
                        if (normalized.length <= prefix.length) continue
                        normalized.substring(prefix.length)
                    }
                    if (remainder.isEmpty()) continue
                    val slashIdx = remainder.indexOf('/')
                    if (slashIdx != -1) {
                        val first = remainder.substring(0, slashIdx)
                        if (first.isEmpty()) continue
                        if (!dirMap.containsKey(first)) {
                            val fullInner = if (internalDir.isEmpty()) first else "$internalDir/$first"
                            dirMap[first] = FileBrowserItem(
                                name = first,
                                isDirectory = true,
                                size = null,
                                realFile = null,
                                zipInnerPath = fullInner,
                                key = "zip:$fullInner"
                            )
                        }
                    } else {
                        if (entry.isDirectory) {
                            if (!dirMap.containsKey(remainder)) {
                                val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                                dirMap[remainder] = FileBrowserItem(
                                    name = remainder,
                                    isDirectory = true,
                                    size = null,
                                    realFile = null,
                                    zipInnerPath = fullInner,
                                    key = "zip:$fullInner"
                                )
                            }
                        } else {
                            val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                            fileList.add(
                                FileBrowserItem(
                                    name = remainder,
                                    isDirectory = false,
                                    size = entry.size.takeIf { it >= 0 },
                                    realFile = null,
                                    zipInnerPath = fullInner,
                                    key = "zip:$fullInner"
                                )
                            )
                        }
                    }
                }
                dirMap.values.toList() to fileList
            }
        } catch (_: Exception) {
            emptyList<FileBrowserItem>() to emptyList()
        }
    }

    private fun File.atomicMoveTo(target: File) {
        try {
            java.nio.file.Files.move(this.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!this.renameTo(target)) {
                if (this.isDirectory) {
                    this.copyRecursively(target, overwrite = true)
                    this.deleteRecursively()
                } else {
                    this.copyTo(target, overwrite = true)
                    this.delete()
                }
            }
        }
    }
}
