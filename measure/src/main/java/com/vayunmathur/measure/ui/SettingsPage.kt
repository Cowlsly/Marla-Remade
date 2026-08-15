package com.vayunmathur.measure.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.MeasureViewModel

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.settings.collectAsState()
    SettingsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
        onOpenDiagnostics = { backStack.add(Route.Diagnostics) },
        onOpenSaved = { backStack.add(Route.Saved) },
    )
}
