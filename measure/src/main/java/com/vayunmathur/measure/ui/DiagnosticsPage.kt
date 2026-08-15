package com.vayunmathur.measure.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.MeasureViewModel

@Composable
fun DiagnosticsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.diagnostics.collectAsState()
    DiagnosticsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
    )
}
