package com.vayunmathur.games.sudoku.platform

import com.vayunmathur.games.sudoku.data.SudokuGameState

/**
 * The UI contract between [SudokuViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so [Noop] is the whole implementation a preview needs.
 */
interface SudokuActions {
    fun selectCell(index: Int) {}
    fun enterDigit(digit: Int) {}
    fun clearCell() {}
    fun toggleNotesMode() {}
    fun hint() {}
    fun undo() {}
    fun restart() {}
    fun giveUp() {}

    companion object { val Noop: SudokuActions = object : SudokuActions {} }
}

/**
 * What the board needs to draw itself.
 *
 * [game] is null only before the first puzzle has been generated; the board shows a spinner then.
 * Generation happens on a background dispatcher because digging a 9x9 runs the solver once per
 * removed clue.
 */
data class SudokuUiState(
    val game: SudokuGameState? = null,
    val canUndo: Boolean = false,
    val generating: Boolean = false,
)
