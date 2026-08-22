package com.vayunmathur.files

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    @Serializable
    data object Browser : Route
}
