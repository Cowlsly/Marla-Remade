package com.vayunmathur.weather

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data object SearchLocation : Route

    @Serializable
    data class WeatherMap(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val isoTime: String? = null,
        val metric: String,
    ) : Route
}
