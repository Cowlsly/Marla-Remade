package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.launcher.Route
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * The stateful half of the home screen.
 *
 * It also feeds the drawer's state in, because the drawer is an overlay on the home screen rather
 * than a destination of its own — see [com.vayunmathur.launcher.Route].
 *
 * Every `XxxPage` in this package does the same three things and nothing else: read the state off
 * the ViewModel, hand the stateless `XxxContent` that state plus the actions, and wire navigation.
 * That split is what makes the previews in `src/screenshotTest` possible.
 *
 * The callbacks are **remembered**, and that is a performance requirement rather than style. A
 * method reference like `viewModel::widgetView` is a fresh object every time this composable runs,
 * and this composable runs whenever any of the four flows above emits — every workspace write, every
 * keystroke in the drawer. Those references are handed down into every single workspace cell, so a
 * new identity for them means nothing below here can skip: one drawer keystroke recomposed three
 * pages of icons. Measured, this was the launcher's dominant frame cost.
 */
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: LauncherViewModel) {
    val state by viewModel.home.collectAsState()
    val drawerState by viewModel.drawer.collectAsState()
    val itemMenuState by viewModel.itemMenu.collectAsState()
    val widgetPickerState by viewModel.widgetPicker.collectAsState()

    val onOpenItemMenu = remember(viewModel) { { id: Long -> viewModel.openItemMenu(id) } }
    val onPickWallpaper = remember(viewModel) { { viewModel.pickWallpaper() } }
    val widgetView = remember(viewModel) {
        { id: Int -> viewModel.widgetView(id) }
    }
    val updateWidgetSize = remember(viewModel) {
        { view: AppWidgetHostView, width: Int, height: Int ->
            viewModel.updateWidgetSize(view, width, height)
        }
    }
    val onOpenSettings = remember(backStack) { { backStack.add(Route.Settings) } }

    HomeContent(
        state = state,
        actions = viewModel,
        drawerState = drawerState,
        drawerActions = viewModel,
        folderActions = viewModel,
        // Not a destination: the menu is an overlay in the home screen, so this only loads the
        // state for it. See Route.
        onOpenItemMenu = onOpenItemMenu,
        itemMenuState = itemMenuState,
        itemMenuActions = viewModel,
        onOpenSettings = onOpenSettings,
        onPickWallpaper = onPickWallpaper,
        widgetPickerState = widgetPickerState,
        widgetPickerActions = viewModel,
        widgetView = widgetView,
        updateWidgetSize = updateWidgetSize,
    )
}
