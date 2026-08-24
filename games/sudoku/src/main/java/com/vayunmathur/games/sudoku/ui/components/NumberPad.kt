package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.data.sudokuSymbol
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The digit keys, laid out as a grid in the board's own box shape.
 *
 * Reusing [SudokuGameState.size]'s box geometry means the pad is a miniature of a box — 2x3 for 6x6,
 * 3x3 for 9x9, 3x4 for 12x12 — so a digit sits in the same relative position on the pad as it does in
 * the pencil marks.
 *
 * Keys are fixed-size squares packed flush against each other rather than stretched across the width:
 * a full-width grid would give keys around 90dp across on a 12x12, which crowds out the board. A
 * hairline in the background colour separates them, so the block reads as distinct keys without
 * needing gaps.
 *
 * A digit that has been placed as many times as the board is wide is disabled rather than hidden, so
 * the keys never reflow under the player's thumb mid-game. Notes mode keeps every digit enabled,
 * because a pencil mark for a finished digit is still a legitimate thing to jot down while ruling it
 * out.
 */
@Composable
fun NumberPad(
    game: SudokuGameState,
    onDigit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = game.size
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until size.boxRows) {
            Row {
                for (col in 0 until size.boxCols) {
                    val digit = row * size.boxCols + col + 1
                    DigitKey(
                        digit = digit,
                        enabled = !game.isWon &&
                            (game.notesMode || game.placedCount(digit) < size.side),
                        highlighted = game.notesMode,
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitKey(
    digit: Int,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val shared = Modifier
        .size(KeySize)
        // Drawn in the background colour, so it separates neighbouring keys without adding a gap.
        .border(HairlineWidth, MaterialTheme.colorScheme.background)
    val label = @Composable {
        Text(text = sudokuSymbol(digit), fontSize = 19.sp)
    }
    if (highlighted) {
        FilledTonalButton(
            onClick = onClick,
            modifier = shared,
            enabled = enabled,
            shape = RectangleShape,
            contentPadding = PaddingValues(0.dp),
        ) { label() }
    } else {
        Button(
            onClick = onClick,
            modifier = shared,
            enabled = enabled,
            shape = RectangleShape,
            contentPadding = PaddingValues(0.dp),
        ) { label() }
    }
}

/** Comfortably above the 48dp touch target once the hairline is accounted for, and no larger. */
private val KeySize = 50.dp

private val HairlineWidth = 1.dp
