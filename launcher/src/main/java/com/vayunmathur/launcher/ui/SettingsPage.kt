package com.vayunmathur.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.launcher.Route
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: LauncherViewModel) {
    val state by viewModel.settings.collectAsState()
    SettingsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
        // The picker is a sheet over the home screen rather than a destination, so this leaves
        // settings first and the sheet is what the user lands back on.
        onOpenWidgetPicker = {
            backStack.pop()
            viewModel.openWidgetPicker()
        },
    )
}
