package com.vayunmathur.games.minesweeper

import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    /**
     * Carries the [GameConfig] so the board can deal itself a field if it is ever entered without one
     * in memory — the ViewModel holds the field but nothing restores it after process death.
     */
    @Serializable
    data class Game(val config: GameConfig) : Route

    @Serializable
    data object GameCenter : Route
}
