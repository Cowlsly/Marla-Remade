package com.vayunmathur.vpn

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Main(val initialTab: Int = 0) : Route

    @Serializable
    data class Detail(val id: Long) : Route

    @Serializable
    data object BypassList : Route
}
