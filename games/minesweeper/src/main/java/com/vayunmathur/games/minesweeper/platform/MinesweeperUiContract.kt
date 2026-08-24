package com.vayunmathur.games.minesweeper.platform

import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState

/**
 * The UI contract between [MinesweeperViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so [Noop] is the whole implementation a preview needs.
 */
interface MinesweeperActions {
    /** Tap: opens a covered cell, or chords an already-open number. */
    fun tapCell(index: Int) {}

    /** Long press: cycles the flag. */
    fun flagCell(index: Int) {}

    fun restart() {}
    fun giveUp() {}

    companion object { val Noop: MinesweeperActions = object : MinesweeperActions {} }
}

/**
 * What the board needs to draw itself.
 *
 * [config] lives here rather than on the game state because the chosen size and density belong to
 * the session, not the field — the rules work on any rectangle.
 */
data class MinesweeperUiState(
    val config: GameConfig = GameConfig(),
    val game: MinesweeperGameState? = null,
)
