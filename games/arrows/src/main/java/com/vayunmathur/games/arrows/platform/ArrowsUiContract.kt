package com.vayunmathur.games.arrows.platform

import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.GameMode

/**
 * The UI contract between [ArrowsViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface ArrowsGameActions {
    /** Sends the tapped arrow on its way, or spends a heart if it is boxed in. */
    fun tapArrow(pieceId: Int) {}

    fun nextLevel() {}
    fun restartLevel() {}
    fun setGameMode(mode: GameMode) {}

    companion object { val Noop: ArrowsGameActions = object : ArrowsGameActions {} }
}

/** What the board needs to draw itself. [game] is null only while a board is being generated. */
data class ArrowsUiState(
    val game: ArrowsGameState? = null,
    val mode: GameMode = GameMode.CASUAL,
    val level: Int = 1,
    val showRoutes: Boolean = true,
    val dailyStreak: Long = 0,
    /** True once today's daily has been cleared, which swaps the next-level button for a note. */
    val dailyDone: Boolean = false,
    val generating: Boolean = false,
    /** Set when even a widened seed search found no clearable board, so the board can offer a retry. */
    val generationFailed: Boolean = false,
)

interface SettingsActions {
    fun setShowRoutes(enabled: Boolean) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object { val Noop: SettingsActions = object : SettingsActions {} }
}

data class SettingsUiState(
    val showRoutes: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)
