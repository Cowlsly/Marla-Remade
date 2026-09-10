package com.vayunmathur.library.intents.taxi

import kotlinx.serialization.Serializable

/**
 * Shared wire DTOs for taxi's cross-app ride-estimate [AssistantIntent][com.vayunmathur.library.util.AssistantIntent]
 * (`com.vayunmathur.taxi.intents.RideEstimateIntent`), answered for a co-signed caller (the maps
 * route sheet). This replaces the old `content://com.vayunmathur.taxi.ridelookup/estimate` cursor:
 * a ContentProvider is the wrong shape for a live, time-varying quote.
 */
@Serializable
data class RideEstimateRequest(
    val pickupLat: Double,
    val pickupLng: Double,
    val destLat: Double,
    val destLng: Double,
    val pickupLabel: String? = null,
    val destLabel: String? = null,
)

/**
 * @property available whether a live quote was found for the trip.
 * @property fareEstimate formatted fare (e.g. "$12.50" or "$12.50 – $15.00"); "" when unavailable.
 * @property etaMinutes pickup ETA in minutes; -1 when unknown/unavailable.
 */
@Serializable
data class RideEstimateResult(
    val available: Boolean,
    val fareEstimate: String,
    val etaMinutes: Int,
)
