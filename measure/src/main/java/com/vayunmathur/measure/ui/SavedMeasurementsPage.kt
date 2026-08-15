package com.vayunmathur.measure.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.MeasureViewModel

@Composable
fun SavedMeasurementsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.saved.collectAsState()
    SavedMeasurementsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
    )
}
