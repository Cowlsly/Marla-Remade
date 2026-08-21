package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.vayunmathur.library.ui.AppScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import com.vayunmathur.library.ui.Button

private val SolitaireBoardMaxWidth = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoardScreen(state: SolitaireUiState, mode: GameMode, actions: SolitaireActions, onExit: () -> Unit) {
    val activeGame = when (mode) {
        GameMode.KLONDIKE -> state.klondike?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.SPIDER -> state.spider?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.FREECELL -> state.freeCell?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.PYRAMID -> state.pyramid?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
    }
    val isWon = activeGame?.first == true
    val moveCount = activeGame?.second ?: 0
    val elapsed = activeGame?.third ?: 0
    val modeName = mode.displayName()
    val timeText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    AppScaffold(
        title = modeName,
        actions = {
            Text(timeText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
            Text("${stringResource(R.string.moves)}: $moveCount", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameActionBar(onUndo = { actions.undo() }, onGiveUp = { actions.giveUp(); onExit() }, undoEnabled = state.history.isNotEmpty() && !isWon)
                when (mode) {
                    GameMode.KLONDIKE -> state.klondike?.let {
                        KlondikeBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SolitaireBoardMaxWidth).fillMaxWidth())
                        if (!it.isWon && it.tableauPiles.none { p -> p.faceDown.isNotEmpty() }) {
                            Button(onClick = { actions.klondikeAutoComplete() }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.auto_complete)) }
                        }
                    }
                    GameMode.SPIDER -> state.spider?.let { SpiderBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SolitaireBoardMaxWidth).fillMaxWidth()) }
                    GameMode.FREECELL -> state.freeCell?.let { FreeCellBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SolitaireBoardMaxWidth).fillMaxWidth()) }
                    GameMode.PYRAMID -> state.pyramid?.let { PyramidBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SolitaireBoardMaxWidth).fillMaxWidth()) }
                }
            }
            if (isWon) { WinOverlay(elapsedSeconds = elapsed, moveCount = moveCount, onNewGame = { actions.restart() }, onBack = onExit) }
        }
    }
}
