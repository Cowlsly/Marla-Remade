package com.vayunmathur.games.arrows

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /** The only board there is: which one it shows comes from the stored mode and level. */
    @Serializable
    data object Game : Route

    @Serializable
    data object GameCenter : Route

    @Serializable
    data object Settings : Route
}
