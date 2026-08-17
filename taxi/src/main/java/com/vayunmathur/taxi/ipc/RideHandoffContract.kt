package com.vayunmathur.taxi.ipc

import androidx.core.net.toUri
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place

/**
 * Wire contract for the cross-app taxi hand-off exposed to a co-signed app (the maps app).
 *
 * There are two channels, both keyed on an origin→destination:
 *  - a `taxi://book?…` VIEW deep link that opens the app on the ride screen with the trip
 *    (pickup + destination lat/lng + labels) pre-filled and immediately compared, and
 *  - a signature-guarded estimate [android.content.ContentProvider] ([RideEstimateProvider]) at
 *    [AUTHORITY] that answers { available, fareEstimate, etaMinutes } so the caller can show a
 *    fare/ETA inline before launching.
 *
 * The two apps deliberately do NOT share a module: maps keeps its own byte-for-byte mirror of
 * these constants (see maps' `RideEstimateClient`). Reading the provider is gated by [PERMISSION]
 * (signature-level), granted only because both MA apps share the signing key; on any other signer
 * it is simply not granted and the maps taxi option is absent.
 */
object RideHandoffContract {
    /** Authority of the exported ride-estimate provider. */
    const val AUTHORITY = "com.vayunmathur.taxi.ridelookup"

    /** Signature-level permission required to read the provider. */
    const val PERMISSION = "com.vayunmathur.taxi.permissions.ACCESS_RIDES"

    /** Single query path: content://<AUTHORITY>/estimate?pickup_lat=&pickup_lng=&dest_lat=&dest_lng= */
    const val PATH_ESTIMATE = "estimate"

    // ---- query / deep-link parameters (caller → app) ----
    const val PARAM_PICKUP_LAT = "pickup_lat"
    const val PARAM_PICKUP_LNG = "pickup_lng"
    const val PARAM_PICKUP_LABEL = "pickup_label"
    const val PARAM_DEST_LAT = "dest_lat"
    const val PARAM_DEST_LNG = "dest_lng"
    const val PARAM_DEST_LABEL = "dest_label"

    // ---- estimate result columns (provider → caller) ----
    /** Int 1/0: whether a live quote was found for the trip. */
    const val COL_AVAILABLE = "available"
    /** String: formatted fare (e.g. "$12.50" or "$12.50 – $15.00"); "" when unavailable. */
    const val COL_FARE_ESTIMATE = "fare_estimate"
    /** Int: pickup ETA in minutes; -1 when unknown/unavailable. */
    const val COL_ETA_MINUTES = "eta_minutes"

    // ---- deep link (open the ride screen with a trip pre-filled) ----
    const val DEEP_LINK_SCHEME = "taxi"
    const val DEEP_LINK_HOST = "book"

    /** Builds the `taxi://book?…` deep link that pre-fills a booking for a co-signed caller. */
    fun bookingDeepLink(
        pickupLat: Double,
        pickupLng: Double,
        pickupLabel: String?,
        destLat: Double,
        destLng: Double,
        destLabel: String?,
    ): String =
        "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST".toUri().buildUpon().apply {
            appendQueryParameter(PARAM_PICKUP_LAT, pickupLat.toString())
            appendQueryParameter(PARAM_PICKUP_LNG, pickupLng.toString())
            if (!pickupLabel.isNullOrBlank()) appendQueryParameter(PARAM_PICKUP_LABEL, pickupLabel)
            appendQueryParameter(PARAM_DEST_LAT, destLat.toString())
            appendQueryParameter(PARAM_DEST_LNG, destLng.toString())
            if (!destLabel.isNullOrBlank()) appendQueryParameter(PARAM_DEST_LABEL, destLabel)
        }.build().toString()

    /**
     * Parses a `taxi://book?…` deep link into a [BookingTrip]. Returns null for any other URI or
     * when the required coordinates are missing/malformed, so a bad link degrades to a plain
     * launch rather than a crash.
     */
    fun parseBooking(uriString: String?): BookingTrip? {
        val uri = uriString?.toUri() ?: return null
        if (uri.scheme != DEEP_LINK_SCHEME || uri.host != DEEP_LINK_HOST) return null
        val pickupLat = uri.getQueryParameter(PARAM_PICKUP_LAT)?.toDoubleOrNull() ?: return null
        val pickupLng = uri.getQueryParameter(PARAM_PICKUP_LNG)?.toDoubleOrNull() ?: return null
        val destLat = uri.getQueryParameter(PARAM_DEST_LAT)?.toDoubleOrNull() ?: return null
        val destLng = uri.getQueryParameter(PARAM_DEST_LNG)?.toDoubleOrNull() ?: return null
        val pickupLabel = uri.getQueryParameter(PARAM_PICKUP_LABEL)?.ifBlank { null }
        val destLabel = uri.getQueryParameter(PARAM_DEST_LABEL)?.ifBlank { null }
        return BookingTrip(
            pickup = Place(pickupLabel.orEmpty(), null, LatLng(pickupLat, pickupLng)),
            destination = Place(destLabel.orEmpty(), null, LatLng(destLat, destLng)),
        )
    }
}
