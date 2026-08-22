package com.vayunmathur.games.solitaire

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.games.solitaire.data.GameMode
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    @Serializable
    data class Game(val mode: GameMode) : Route
    @Serializable
    data object GameCenter : Route
}
