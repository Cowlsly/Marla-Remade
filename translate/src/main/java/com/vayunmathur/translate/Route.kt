package com.vayunmathur.translate

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Text : Route

    @Serializable
    data object Camera : Route

    @Serializable
    data class LanguagePicker(val forSource: Boolean) : Route
}
