package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [LogicViewModel] to the stateless [ProgressionScreen]. */
@Composable
fun ProgressionPage(
    backStack: NavBackStack<Route>,
    viewModel: LogicViewModel
) {
    val completed by viewModel.completedIds.collectAsState()
    ProgressionScreen(
        completed = completed,
        onOpenLevel = { lvlId -> backStack.add(Route.Game(lvlId)) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
