package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PipesActions
import com.vayunmathur.games.pipes.ui.components.MovesInfoBox
import com.vayunmathur.games.pipes.ui.components.PuzzleInfoBox
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoardScreen(state: GameBoardUiState, actions: PipesActions, onBack: () -> Unit, onLevelChange: (Int) -> Unit) {
    Scaffold(topBar = { TopAppBar({}, navigationIcon = { IconNavigation(onBack) }) }) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val infoBoxes = @Composable { PuzzleInfoBox(levelIndex = state.levelIndex, onLevelChange = onLevelChange, isCompleted = state.isCompleted, maxLevelIndex = state.maxLevelIndex); MovesInfoBox(moves = state.moves, bestScore = state.bestScore, optimalMoves = state.levelData.optimalMoves) }
            val actionButtons = @Composable { if (!state.isLevelWon) { Button(onClick = { actions.onUndo() }, enabled = state.canUndo) { Text(stringResource(UiR.string.undo)) }; Button(onClick = { actions.onRestart() }, enabled = state.canUndo) { Text(stringResource(R.string.restart)) } } else if (state.levelIndex < state.maxLevelIndex) { Button(onClick = { onLevelChange(state.levelIndex + 1) }) { Text(stringResource(R.string.next_level)) } } }
            val board = @Composable { boardModifier: Modifier -> GameBoard(levelData = state.levelData, gameState = state.gameState, activeColor = state.activeColor, activePath = state.activePath, onStartDraw = actions::startDraw, onExtendPath = actions::extendPath, onCommitDraw = actions::commitDraw, isLevelWon = state.isLevelWon, colorblind = state.colorblind, modifier = boardModifier) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                if (maxWidth > maxHeight) { Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { board(Modifier.fillMaxSize()) }; Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { infoBoxes(); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actionButtons() } } } }
                else { Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) { infoBoxes() }; Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { board(Modifier.fillMaxSize()) }; Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { actionButtons() } } }
            }
        }
    }
}
