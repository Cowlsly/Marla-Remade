package com.vayunmathur.games.logicgate

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Progression : Route
    @Serializable data class Game(val levelId: String) : Route
    @Serializable data object GameCenter : Route
}
