package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [LogicViewModel] to the stateless [GameScreen]. */
@Composable
fun GamePage(
    backStack: NavBackStack<Route>,
    viewModel: LogicViewModel,
    levelId: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val unlocked by viewModel.unlockedChips.collectAsState()
    GameScreen(
        levelId = levelId,
        state = uiState,
        unlockedChips = unlocked,
        actions = viewModel,
        onBack = { backStack.pop() },
        onOpenLevel = { nextId -> backStack.add(Route.Game(nextId)) },
    )
}
