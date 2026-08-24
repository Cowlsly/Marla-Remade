package com.vayunmathur.games.sudoku

import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    /**
     * Carries the [GameConfig] so the board can deal itself a puzzle if it is ever entered without
     * one in memory — the ViewModel holds the grid but nothing restores it after process death.
     */
    @Serializable
    data class Game(val config: GameConfig) : Route

    @Serializable
    data object GameCenter : Route
}
