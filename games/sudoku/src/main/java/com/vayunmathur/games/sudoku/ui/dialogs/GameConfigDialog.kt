package com.vayunmathur.games.sudoku.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.ui.displayName
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Picks the board size and difficulty for a new puzzle.
 *
 * Both choices are local state: nothing reaches the ViewModel until the player confirms, so backing
 * out cannot disturb a game already in progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameConfigDialog(
    initial: GameConfig,
    onStart: (GameConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var size by remember { mutableStateOf(initial.size) }
    var difficulty by remember { mutableStateOf(initial.difficulty) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_puzzle)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(R.string.board_size),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow {
                    BoardSize.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index, BoardSize.entries.size),
                            onClick = { size = value },
                            selected = size == value,
                        ) { Text(value.displayName()) }
                    }
                }
                Text(
                    stringResource(R.string.difficulty),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow {
                    Difficulty.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index, Difficulty.entries.size),
                            onClick = { difficulty = value },
                            selected = difficulty == value,
                        ) { Text(value.displayName()) }
                    }
                }
                Button(
                    onClick = { onStart(GameConfig(size, difficulty)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.new_game)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) }
        },
    )
}
