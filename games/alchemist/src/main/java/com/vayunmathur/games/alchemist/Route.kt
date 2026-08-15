package com.vayunmathur.games.alchemist

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    @Serializable
    data class ItemDetails(val item: Int) : Route
    @Serializable
    data object Collection : Route
    @Serializable
    data object GameCenter : Route
}