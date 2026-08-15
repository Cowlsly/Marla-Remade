package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.solitaire.Route
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay

@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: SolitaireViewModel, mode: GameMode) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(mode) { if (uiState.gameMode != mode) viewModel.selectMode(mode) }
    LaunchedEffect(uiState.gameMode) { while (true) { delay(1000); viewModel.incrementTimer() } }
    GameBoardScreen(state = uiState, mode = mode, actions = viewModel, onExit = { backStack.pop() })
}
