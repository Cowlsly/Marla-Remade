package com.vayunmathur.library.intents

import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.intents.fooddelivery.OrderLookupResult
import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.library.intents.taxi.RideEstimateResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WS-C cross-app IPC DTOs travel as JSON strings over the `AssistantIntent` ResultReceiver
 * binder (encoded by the host, decoded by the maps client). These assert each type survives a
 * JSON round-trip unchanged — including the nullable `*Label` / `address` fields both present and
 * absent, since those are what an optional query parameter maps to.
 */
class IpcDtoRoundTripTest {

    private val json = Json

    @Test
    fun rideEstimateRequest_roundTrips_withLabels() {
        val original = RideEstimateRequest(
            pickupLat = 37.7749, pickupLng = -122.4194,
            destLat = 34.0522, destLng = -118.2437,
            pickupLabel = "Home", destLabel = "Work",
        )
        val decoded = json.decodeFromString(
            RideEstimateRequest.serializer(),
            json.encodeToString(RideEstimateRequest.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun rideEstimateRequest_roundTrips_withoutLabels() {
        val original = RideEstimateRequest(
            pickupLat = 1.0, pickupLng = 2.0, destLat = 3.0, destLng = 4.0,
        )
        val decoded = json.decodeFromString(
            RideEstimateRequest.serializer(),
            json.encodeToString(RideEstimateRequest.serializer(), original),
        )
        assertEquals(original, decoded)
        assertEquals(null, decoded.pickupLabel)
        assertEquals(null, decoded.destLabel)
    }

    @Test
    fun rideEstimateResult_roundTrips_available() {
        val original = RideEstimateResult(available = true, fareEstimate = "$12.50 – $15.00", etaMinutes = 4)
        val decoded = json.decodeFromString(
            RideEstimateResult.serializer(),
            json.encodeToString(RideEstimateResult.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun rideEstimateResult_roundTrips_unavailable() {
        val original = RideEstimateResult(available = false, fareEstimate = "", etaMinutes = -1)
        val decoded = json.decodeFromString(
            RideEstimateResult.serializer(),
            json.encodeToString(RideEstimateResult.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun orderLookupRequest_roundTrips_withAndWithoutAddress() {
        val withAddress = OrderLookupRequest(name = "Joe's Café", lat = 10.0, lng = 20.0, address = "1 Main St")
        val withoutAddress = OrderLookupRequest(name = "Joe's Café", lat = 10.0, lng = 20.0)
        assertEquals(
            withAddress,
            json.decodeFromString(
                OrderLookupRequest.serializer(),
                json.encodeToString(OrderLookupRequest.serializer(), withAddress),
            ),
        )
        val decodedWithout = json.decodeFromString(
            OrderLookupRequest.serializer(),
            json.encodeToString(OrderLookupRequest.serializer(), withoutAddress),
        )
        assertEquals(withoutAddress, decodedWithout)
        assertEquals(null, decodedWithout.address)
    }

    @Test
    fun orderLookupResult_roundTrips_orderableAndNot() {
        val orderable = OrderLookupResult(available = true, merchantId = 42, deepLink = "fooddelivery://restaurant/42")
        val notOrderable = OrderLookupResult(available = false, merchantId = 0, deepLink = "")
        assertEquals(
            orderable,
            json.decodeFromString(
                OrderLookupResult.serializer(),
                json.encodeToString(OrderLookupResult.serializer(), orderable),
            ),
        )
        assertEquals(
            notOrderable,
            json.decodeFromString(
                OrderLookupResult.serializer(),
                json.encodeToString(OrderLookupResult.serializer(), notOrderable),
            ),
        )
    }
}
