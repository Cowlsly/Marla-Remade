package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The digit keys.
 *
 * A digit that has been placed as many times as the board is wide is disabled rather than hidden,
 * so the keys never reflow under the player's thumb mid-game. Notes mode keeps every digit enabled,
 * because a pencil mark for a finished digit is still a legitimate thing to jot down while ruling
 * it out.
 */
@Composable
fun NumberPad(
    game: SudokuGameState,
    onDigit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val side = game.size.side
    // 4 and 6 fit one row; 9 splits so the keys stay wide enough to hit.
    val perRow = if (side > 6) 5 else side
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        var digit = 1
        while (digit <= side) {
            val rowEnd = minOf(digit + perRow - 1, side)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                for (d in digit..rowEnd) {
                    val exhausted = !game.notesMode && game.placedCount(d) >= side
                    DigitKey(
                        digit = d,
                        enabled = !exhausted && !game.isWon,
                        highlighted = game.notesMode,
                        onClick = { onDigit(d) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the last row's keys the same width as a full row's.
                repeat(perRow - (rowEnd - digit + 1)) {
                    Column(Modifier.weight(1f)) {}
                }
            }
            digit = rowEnd + 1
        }
    }
}

@Composable
private fun DigitKey(
    digit: Int,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = @Composable {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 22.sp,
        )
    }
    if (highlighted) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { label() }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { label() }
    }
}
