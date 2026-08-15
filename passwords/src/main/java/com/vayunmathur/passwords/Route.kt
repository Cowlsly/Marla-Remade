package com.vayunmathur.passwords

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Menu : Route

    @Serializable
    data class PasswordPage(val id: Long) : Route

    @Serializable
    data class PasswordEditPage(val id: Long) : Route

    @Serializable
    data class PasskeyPage(val id: Long) : Route

    @Serializable
    data object Settings : Route
}
