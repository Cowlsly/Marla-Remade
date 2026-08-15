package com.vayunmathur.games.pipes

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object PackSelector : Route
    @Serializable
    data class LevelSelector(val packIndex: Int) : Route
    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int) : Route
    @Serializable
    data object DailySelector : Route
    @Serializable
    data class DailyGame(val levelIndex: Int) : Route
    @Serializable
    data object GameCenter : Route
    @Serializable
    data object Settings : Route
}
