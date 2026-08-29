package com.vayunmathur.files.ui

import com.vayunmathur.files.Route
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FileCategory
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.util.NavBackStack
import java.io.File

/**
 * The [FilesActions] the screens are given: every action that changes *where* the user is becomes a
 * back stack operation, and everything else - copy, rename, sort, select - falls through to the
 * view model untouched.
 *
 * Delegation rather than a rewrite of the screens. `FilesActions` is one wide interface that the
 * view model implements, so `by viewModel` forwards the eighty-odd members that are not navigation
 * and leaves only the handful below to override. The screens keep calling `actions.navigateTo(...)`
 * and do not know the difference.
 */
internal fun filesNavActions(
    viewModel: FilesViewModel,
    backStack: NavBackStack<Route>,
): FilesActions = object : FilesActions by viewModel {

    private val root: File get() = viewModel.rootDirectory

    override fun goHome() = backStack.reset(Route.Home)

    override fun openInternalStorage() =
        backStack.reset(Route.Home, Route.Directory(root.absolutePath))

    /**
     * A bookmark can point anywhere, so the intermediate folders are synthesised rather than pushed
     * one at a time. Without them Back from a deep bookmark would jump straight to Home, skipping
     * every folder the breadcrumb bar is showing.
     */
    override fun openBookmark(path: File) = backStack.showDirectory(root, path)

    override fun openCategory(category: FileCategory) {
        // Downloads is a real folder rather than a media query, so it browses like one.
        if (category == FileCategory.DOWNLOADS) {
            val downloads = File(root, "Download")
            openBookmark(if (downloads.isDirectory) downloads else root)
        } else {
            backStack.reset(Route.Home, Route.Category(category))
        }
    }

    /**
     * Serves both descending into a child folder and tapping an ancestor in the breadcrumb bar. A
     * target already on the stack is popped back to, so jumping up does not stack a second copy of a
     * folder the user has already been through.
     */
    override fun navigateTo(path: File) {
        val target = Route.Directory(path.absolutePath)
        if (!backStack.popTo(target)) backStack.add(target)
    }

    override fun navigateIntoZipDir(dirName: String) {
        val current = backStack.backStack.lastOrNull() as? Route.Zip ?: return
        val child =
            if (current.internalPath.isEmpty()) dirName else "${current.internalPath}/$dirName"
        backStack.add(current.copy(internalPath = child))
    }

    override fun navigateToZipInternalPath(fullInternalPath: String) {
        val current = backStack.backStack.lastOrNull() as? Route.Zip ?: return
        val target = current.copy(internalPath = fullInternalPath)
        if (!backStack.popTo(target)) backStack.add(target)
    }

    /** Breadcrumb tap on the real-filesystem part of the trail while inside an archive. */
    override fun navigateToZipParentRealFolder(target: File) {
        val route = Route.Directory(target.absolutePath)
        if (!backStack.popTo(route)) backStack.showDirectory(root, target)
    }
}

/**
 * Pops until [target] is the current destination, reporting whether it was there to begin with.
 *
 * Returns false and changes nothing when the target is absent, so a caller can fall back to pushing
 * rather than unwinding the whole stack looking for something that was never on it.
 */
private fun NavBackStack<Route>.popTo(target: Route): Boolean {
    if (target !in backStack) return false
    while (backStack.size > 1 && backStack.last() != target) pop()
    return true
}

/** Replaces the stack with Home followed by one entry per folder from [root] down to [target]. */
private fun NavBackStack<Route>.showDirectory(root: File, target: File) {
    val chain = fileAncestors(target, root)
    if (chain.isEmpty()) {
        reset(Route.Home, Route.Directory(target.absolutePath))
    } else {
        reset(Route.Home, *chain.map { Route.Directory(it.absolutePath) }.toTypedArray())
    }
}
