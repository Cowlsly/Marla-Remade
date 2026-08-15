package com.vayunmathur.taxi

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Ride : Route
    @Serializable data object CurrentRide : Route
    @Serializable data object Accounts : Route
    @Serializable data object LyftSignIn : Route
    @Serializable data class RideTracking(val rideId: String) : Route
}
