package com.vayunmathur.measure

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Compass : Route
    @Serializable data object Level : Route
    @Serializable data object Ruler : Route

    /**
     * Camera is requested here rather than at app launch: the sensor tools are fully
     * usable without it, so gating the whole app on CAMERA would be a permission prompt
     * most sessions never need.
     */
    @Serializable data object ArMeasure : Route

    @Serializable data object Saved : Route
    @Serializable data object Settings : Route
    @Serializable data object Diagnostics : Route
}
