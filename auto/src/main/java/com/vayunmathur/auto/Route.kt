package com.vayunmathur.auto

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Auto has one phone-side screen. The interesting interface lives on the car's display, not
 * here; this is only somewhere to see whether a car is attached.
 */
@Serializable
sealed interface Route : NavKey {
    /** Connection state and how to attach a car. */
    @Serializable
    data object Home : Route
}
