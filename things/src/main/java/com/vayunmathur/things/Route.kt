package com.vayunmathur.things

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Devices : Route

    @Serializable
    data object Home : Route
}
