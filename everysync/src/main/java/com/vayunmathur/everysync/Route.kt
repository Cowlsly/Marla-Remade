package com.vayunmathur.everysync

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Accounts : Route
    @Serializable data object AddAccount : Route
    @Serializable data class DavLogin(val providerId: String) : Route
    @Serializable data class AccountDetail(val accountName: String) : Route
    @Serializable data object Settings : Route
}
