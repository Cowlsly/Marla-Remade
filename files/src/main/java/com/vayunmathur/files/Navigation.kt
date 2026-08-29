package com.vayunmathur.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.ui.DirectoryPage
import com.vayunmathur.files.ui.FilesShell
import com.vayunmathur.files.ui.HomeScreenBinder
import com.vayunmathur.files.ui.filesNavActions
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import java.io.File

/**
 * One view model holds one location, so each destination claims it when it becomes the current one -
 * hence the `isCurrent` guard repeated below. It is needed on the way forward *and* on the way back,
 * because popping to a folder already on the stack has to reload it; and it stops the outgoing entry
 * reloading its own folder mid-transition, fighting the incoming one for the same state.
 */
@Composable
fun Navigation(viewModel: FilesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val actions = remember(viewModel, backStack) { filesNavActions(viewModel, backStack) }

    // An archive becomes a destination only once the view model has confirmed it opens, so a corrupt
    // zip reports an error where the user is instead of navigating into a listing that cannot load.
    LaunchedEffect(viewModel, backStack) {
        viewModel.zipOpened.collect { backStack.add(Route.Zip(it.absolutePath)) }
    }

    FilesShell(viewModel, actions) { openDrawer ->
        MainNavigation(backStack) {
            entry<Route.Home> {
                val isCurrent = backStack.last() == Route.Home
                LaunchedEffect(isCurrent) { if (isCurrent) viewModel.goHome() }
                HomeScreenBinder(viewModel, actions, openDrawer)
            }
            entry<Route.Directory> { route ->
                val isCurrent = backStack.last() == route
                LaunchedEffect(route, isCurrent) {
                    if (isCurrent) viewModel.navigateTo(File(route.path))
                }
                DirectoryPage(viewModel, actions, isCurrent, openDrawer)
            }
            // A category listing spans every folder, so it has nowhere to go "up" to: it always
            // sits directly on Home rather than on the folder it was opened from.
            entry<Route.Category> { route ->
                val isCurrent = backStack.last() == route
                LaunchedEffect(route, isCurrent) {
                    if (isCurrent) viewModel.openCategory(route.category)
                }
                DirectoryPage(viewModel, actions, isCurrent, openDrawer)
            }
            entry<Route.Zip> { route ->
                val isCurrent = backStack.last() == route
                LaunchedEffect(route, isCurrent) {
                    if (isCurrent) viewModel.showZip(File(route.zipPath), route.internalPath)
                }
                DirectoryPage(viewModel, actions, isCurrent, openDrawer)
            }
        }
    }
}
