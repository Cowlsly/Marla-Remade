package com.vayunmathur.files.platform

import java.io.File

/**
 * The UI contract between [FilesViewModel] and the directory browser.
 *
 * The screen takes a state value plus an actions interface rather than the ViewModel
 * itself, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `util` rather than next to the composables so the
 * dependency runs one way, and [FilesViewModel] implements [FilesActions] directly.
 */

/** How the current directory's entries are ordered. */
enum class SortBy { NAME, DATE, SIZE, TYPE }

/** List vs. grid presentation of the current directory. */
enum class ViewMode { LIST, GRID }

/** A home-screen shortcut that collects files of one kind from across storage. */
enum class FileCategory { IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS }

/** Storage volume usage, for the home screen meter. */
data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

/** Everything the directory browser draws. */
data class FilesUiState(
    val rootDirectory: File,
    /**
     * Label for the root breadcrumb — the device model. Passed in because a preview has no
     * device to read it from.
     */
    val rootDisplayName: String,
    val currentDirectory: File,
    /** Non-null while browsing inside a zip; [currentDirectory] is then meaningless. */
    val zipPath: File? = null,
    val zipInternalPath: String = "",
    val directories: List<FileBrowserItem> = emptyList(),
    val files: List<FileBrowserItem> = emptyList(),
    val selectedPaths: Set<FileBrowserItem> = emptySet(),
    /** True while files shared into the app are waiting to be saved here. */
    val hasIncomingUris: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    /** Non-empty while the search field filters the current listing by name. */
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    /** Number of files on the copy/cut clipboard (0 = nothing to paste). */
    val clipboardCount: Int = 0,
    /** True when the clipboard holds a cut (move on paste) rather than a copy. */
    val clipboardIsCut: Boolean = false,
    val showHidden: Boolean = false,
    /** Non-null while showing a category result list (e.g. "Images"); [directories] is empty. */
    val categoryTitle: String? = null,
)

/** The state the home screen draws (storage meter, shortcuts, recents, bookmarks). */
data class HomeUiState(
    val rootDisplayName: String,
    val storage: StorageInfo? = null,
    val recents: List<FileBrowserItem> = emptyList(),
    val bookmarks: List<FileBrowserItem> = emptyList(),
)

/**
 * Browser callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface FilesActions {
    fun navigateTo(path: File) {}
    fun navigateIntoZipDir(dirName: String) {}
    fun navigateToZipInternalPath(fullInternalPath: String) {}
    fun navigateToZipParentRealFolder(target: File) {}

    /** Returns true when the back press was consumed (selection cleared, or moved up). */
    fun handleBack(): Boolean = false

    fun clearSelection() {}
    fun addToSelection(item: FileBrowserItem) {}
    fun toggleSelection(item: FileBrowserItem) {}
    fun selectAll() {}

    fun rename(item: FileBrowserItem, newName: String) {}
    fun deleteSelection() {}
    fun moveInto(sources: List<File>, target: File) {}
    fun moveToBreadcrumb(sources: List<File>, target: File) {}

    fun openZipFile(item: FileBrowserItem) {}
    fun openFile(item: FileBrowserItem) {}
    fun openWith(item: FileBrowserItem) {}
    /** Hands an .apk to the system package installer, prompting for the install permission first. */
    fun installApk(item: FileBrowserItem) {}
    fun shareSelection() {}
    fun archive(archiveName: String) {}
    fun saveIncomingUris() {}

    // ---- Sort & view ----
    fun setSortBy(sortBy: SortBy) {}
    fun toggleSortDirection() {}
    fun setSortAscending(ascending: Boolean) {}
    fun toggleViewMode() {}
    fun toggleHidden() {}

    // ---- Search ----
    fun setSearchActive(active: Boolean) {}
    fun setSearchQuery(query: String) {}

    // ---- Clipboard (copy/cut/paste) ----
    fun copySelection() {}
    fun cutSelection() {}
    fun pasteHere() {}
    fun clearClipboard() {}

    // ---- Create ----
    fun createFolder(name: String) {}
    fun createFile(name: String) {}

    // ---- Home / categories / bookmarks ----
    fun goHome() {}
    fun openInternalStorage() {}
    fun openCategory(category: FileCategory) {}
    fun openBookmark(path: File) {}
    fun addBookmark(item: FileBrowserItem) {}
    fun removeBookmark(path: File) {}

    companion object {
        val Noop: FilesActions = object : FilesActions {}
    }
}
