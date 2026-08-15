package com.vayunmathur.games.wordmaker

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Game : Route
    @Serializable
    data object GameCenter : Route
    @Serializable
    data object Settings : Route
}