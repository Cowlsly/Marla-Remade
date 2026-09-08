package com.vayunmathur.updater

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The updater has one screen. It is a sealed interface anyway because the navigation host
 * requires a [NavKey], and because a changelog detail screen is the obvious next destination.
 */
@Serializable
sealed interface Route : NavKey {

    /** Current build, update state, and the controls. */
    @Serializable
    data object Home : Route
}
