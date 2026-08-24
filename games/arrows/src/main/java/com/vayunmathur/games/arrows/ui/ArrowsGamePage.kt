package com.vayunmathur.games.arrows.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.arrows.Route
import com.vayunmathur.games.arrows.platform.ArrowsViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Binds [ArrowsViewModel] to the stateless [ArrowsGameScreen].
 *
 * The ViewModel already implements the actions contract, so this only has to collect state and supply
 * the two navigation callbacks the board cannot know about.
 */
@Composable
fun ArrowsGamePage(
    backStack: NavBackStack<Route>,
    viewModel: ArrowsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    ArrowsGameScreen(
        state = uiState,
        actions = viewModel,
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
