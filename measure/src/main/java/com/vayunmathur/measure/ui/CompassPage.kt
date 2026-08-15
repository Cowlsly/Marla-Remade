package com.vayunmathur.measure.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.ui.components.MeasureBottomBar

@Composable
fun CompassPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.compass.collectAsState()
    CompassContent(
        state = state,
        actions = viewModel,
        onOpenSettings = { backStack.add(Route.Settings) },
        bottomBar = { MeasureBottomBar(backStack, Route.Compass) },
    )
}
