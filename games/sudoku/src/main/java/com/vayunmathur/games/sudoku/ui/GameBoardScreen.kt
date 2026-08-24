package com.vayunmathur.games.sudoku.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import com.vayunmathur.games.sudoku.ui.components.NumberPad
import com.vayunmathur.games.sudoku.ui.components.SudokuGrid
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Caps the grid on tablets, where a full-width board would put the digits absurdly far apart. */
private val BoardMaxWidth = 520.dp

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun GameBoardScreen(
    state: SudokuUiState,
    actions: SudokuActions,
    onExit: () -> Unit,
) {
    val game = state.game
    AppScaffold(
        title = if (game == null) stringResource(R.string.app_name)
        else "${game.size.displayName()} · ${game.difficulty.displayName()}",
        onNavigateBack = onExit,
        alignment = AppBarAlignment.Center,
        actions = {
            if (game != null) {
                Text(
                    formatTime(game.elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = Spacing.lg),
                )
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (game == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Box
            }

            Column(
                Modifier
                    .widthIn(max = BoardMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                SudokuGrid(
                    game = game,
                    onSelect = actions::selectCell,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (game.hintsUsed > 0) {
                    Text(
                        "${stringResource(R.string.hints_used)}: ${game.hintsUsed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                NumberPad(
                    game = game,
                    onDigit = actions::enterDigit,
                    modifier = Modifier.fillMaxWidth(),
                )

                ActionBar(state, actions, onExit)
            }

            if (game.isWon) {
                WinOverlay(
                    elapsedSeconds = game.elapsedSeconds,
                    hintsUsed = game.hintsUsed,
                    onPlayAgain = { actions.restart() },
                    onBack = onExit,
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: SudokuUiState,
    actions: SudokuActions,
    onExit: () -> Unit,
) {
    val game = state.game ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Tonal fill is the "on" state for notes mode; the label alone is too easy to miss.
            if (game.notesMode) {
                FilledTonalButton(
                    onClick = { actions.toggleNotesMode() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.notes)) }
            } else {
                OutlinedButton(
                    onClick = { actions.toggleNotesMode() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.notes)) }
            }
            OutlinedButton(
                onClick = { actions.clearCell() },
                modifier = Modifier.weight(1f),
                enabled = !game.isWon,
            ) { Text(stringResource(R.string.erase)) }
            OutlinedButton(
                onClick = { actions.undo() },
                modifier = Modifier.weight(1f),
                enabled = state.canUndo,
            ) { Text(stringResource(com.vayunmathur.library.ui.R.string.undo)) }
            OutlinedButton(
                onClick = { actions.hint() },
                modifier = Modifier.weight(1f),
                enabled = !game.isWon,
            ) { Text(stringResource(R.string.hint)) }
        }
        TextButton(
            onClick = {
                actions.giveUp()
                onExit()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.give_up)) }
    }
}

@Composable
private fun WinOverlay(
    elapsedSeconds: Int,
    hintsUsed: Int,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // Scrim, so the card reads as modal instead of letting the number pad show around it.
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(R.string.congratulations),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${stringResource(R.string.time)}: ${formatTime(elapsedSeconds)}")
                if (hintsUsed > 0) {
                    Text(
                        "${stringResource(R.string.hints_used)}: $hintsUsed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onPlayAgain, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.play_again))
                }
                TextButton(onClick = onBack, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
