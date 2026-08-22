package com.vayunmathur.share

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Share : Route

    /** Diagnostic: which transports an AirDropping iPhone is reachable on. Not a user feature. */
    @Serializable
    data object AirDropProbe : Route
}