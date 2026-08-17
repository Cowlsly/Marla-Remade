package com.vayunmathur.maps.ipc

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Byte-for-byte mirror of taxi's `RideHandoffContract`.
 *
 * As with the P18 family-location channel and the P19 order-lookup channel, the two apps
 * deliberately do NOT share a module: the contract is just an authority, a signature permission,
 * a deep-link shape and a handful of query-param / column names, so duplicating it here keeps maps
 * decoupled from taxi's source while still reading the same provider and building the same deep
 * link. Reading the provider is gated by the signature permission [PERMISSION] (both MA apps share
 * the signing key).
 */
object RideHandoffContract {
    const val AUTHORITY = "com.vayunmathur.taxi.ridelookup"
    const val PERMISSION = "com.vayunmathur.taxi.permissions.ACCESS_RIDES"
    const val PATH_ESTIMATE = "estimate"

    const val PARAM_PICKUP_LAT = "pickup_lat"
    const val PARAM_PICKUP_LNG = "pickup_lng"
    const val PARAM_PICKUP_LABEL = "pickup_label"
    const val PARAM_DEST_LAT = "dest_lat"
    const val PARAM_DEST_LNG = "dest_lng"
    const val PARAM_DEST_LABEL = "dest_label"

    const val COL_AVAILABLE = "available"
    const val COL_FARE_ESTIMATE = "fare_estimate"
    const val COL_ETA_MINUTES = "eta_minutes"

    const val DEEP_LINK_SCHEME = "taxi"
    const val DEEP_LINK_HOST = "book"

    /** Package of the MA taxi app, for the Android 11+ package-visibility / installed check. */
    const val PACKAGE = "com.vayunmathur.taxi"

    /** Builds the `taxi://book?…` deep link that opens the taxi app with this trip pre-filled. */
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
}

/** Result of a ride estimate: whether a ride is available and, if so, its fare/ETA. */
data class RideEstimate(
    val available: Boolean,
    val fareEstimate: String?,
    val etaMinutes: Int?,
)

/**
 * Reads taxi's exported ride-estimate provider (see [RideHandoffContract]) for an
 * origin→destination.
 *
 * Absence handling is total: if taxi isn't installed the authority resolves to no provider and the
 * query returns null; if the signature permission isn't held it throws [SecurityException]; a
 * malformed reply or any other failure is caught. In every one of those cases this returns null
 * and the caller shows the launch-only option (or none) — no crash.
 */
object RideEstimateClient {
    /** Whether the MA taxi app is installed (so the option is worth offering at all). */
    fun isInstalled(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(RideHandoffContract.PACKAGE, 0)
        }.isSuccess

    fun estimate(
        context: Context,
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double,
        pickupLabel: String? = null,
        destLabel: String? = null,
    ): RideEstimate? {
        val uri = Uri.parse("content://${RideHandoffContract.AUTHORITY}/${RideHandoffContract.PATH_ESTIMATE}")
            .buildUpon()
            .appendQueryParameter(RideHandoffContract.PARAM_PICKUP_LAT, pickupLat.toString())
            .appendQueryParameter(RideHandoffContract.PARAM_PICKUP_LNG, pickupLng.toString())
            .appendQueryParameter(RideHandoffContract.PARAM_DEST_LAT, destLat.toString())
            .appendQueryParameter(RideHandoffContract.PARAM_DEST_LNG, destLng.toString())
            .apply {
                if (!pickupLabel.isNullOrBlank()) appendQueryParameter(RideHandoffContract.PARAM_PICKUP_LABEL, pickupLabel)
                if (!destLabel.isNullOrBlank()) appendQueryParameter(RideHandoffContract.PARAM_DEST_LABEL, destLabel)
            }
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val available = c.getInt(c.getColumnIndexOrThrow(RideHandoffContract.COL_AVAILABLE)) == 1
                if (!available) return RideEstimate(false, null, null)
                val fare = c.getString(c.getColumnIndexOrThrow(RideHandoffContract.COL_FARE_ESTIMATE))
                    ?.ifBlank { null }
                val eta = c.getInt(c.getColumnIndexOrThrow(RideHandoffContract.COL_ETA_MINUTES))
                    .takeIf { it >= 0 }
                RideEstimate(true, fare, eta)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Resolves a taxi ride estimate for an origin→destination off the main thread. Emits null while
 * loading and when taxi is absent / the lookup fails; re-resolves whenever the endpoints change.
 */
@Composable
fun rememberRideEstimate(
    context: Context,
    pickupLat: Double,
    pickupLng: Double,
    destLat: Double,
    destLng: Double,
    pickupLabel: String?,
    destLabel: String?,
): State<RideEstimate?> = produceState<RideEstimate?>(
    null, pickupLat, pickupLng, destLat, destLng, pickupLabel, destLabel,
) {
    value = withContext(Dispatchers.IO) {
        RideEstimateClient.estimate(context, pickupLat, pickupLng, destLat, destLng, pickupLabel, destLabel)
    }
}
