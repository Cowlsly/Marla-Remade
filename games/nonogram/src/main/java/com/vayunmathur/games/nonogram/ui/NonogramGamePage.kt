package com.vayunmathur.games.nonogram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.nonogram.Route
import com.vayunmathur.games.nonogram.platform.NonogramViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds [NonogramViewModel] to the stateless [NonogramGameScreen].
 *
 * The ViewModel already implements the actions contract, so this only has to collect state and supply
 * the two navigation callbacks the board cannot know about.
 */
@Composable
fun NonogramGamePage(
    backStack: NavBackStack<Route>,
    viewModel: NonogramViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    NonogramGameScreen(
        state = uiState,
        actions = viewModel,
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
