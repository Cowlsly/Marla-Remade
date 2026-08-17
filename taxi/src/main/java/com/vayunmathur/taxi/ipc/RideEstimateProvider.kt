package com.vayunmathur.taxi.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.provider.QuoteRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exported, signature-guarded estimate lookup that answers "can I get a ride for this
 * origin→destination, and roughly what fare/ETA?" for a co-signed app (the maps route sheet).
 * See [RideHandoffContract].
 *
 * A caller queries
 * `content://<AUTHORITY>/estimate?pickup_lat=&pickup_lng=&dest_lat=&dest_lng=(&labels…)`; the
 * provider reuses the app's own quote pipeline ([QuoteRepository.quotes]) and returns exactly one
 * row:
 *  - the cheapest live quote → `available=1`, its formatted fare and pickup ETA;
 *  - no fare / not signed in / any failure → `available=0` (empty fare, eta -1).
 *
 * Everything is wrapped so a network/parse failure degrades to an unavailable row rather than
 * throwing across the binder — the caller then simply shows the launch-only option (or none).
 */
class RideEstimateProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // A provider can be created before any Activity runs, so make sure the network stack
        // (TLS trust bundle) is ready — MainActivity does the same on its own launch path.
        val ctx = context ?: return false
        try {
            NetworkClient.init(ctx, TrustBundle.STANDARD)
        } catch (_: Exception) {
            // Best-effort; a lookup that then fails just returns an unavailable row.
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                RideHandoffContract.COL_AVAILABLE,
                RideHandoffContract.COL_FARE_ESTIMATE,
                RideHandoffContract.COL_ETA_MINUTES,
            )
        )
        val quote = try {
            bestQuote(uri)
        } catch (_: Exception) {
            null
        }
        if (quote != null) {
            cursor.addRow(arrayOf<Any?>(1, formatFare(quote), quote.pickupEtaMinutes ?: -1))
        } else {
            cursor.addRow(arrayOf<Any?>(0, "", -1))
        }
        return cursor
    }

    private fun bestQuote(uri: Uri): RideQuote? {
        val ctx = context ?: return null
        val pickup = place(
            uri,
            RideHandoffContract.PARAM_PICKUP_LAT,
            RideHandoffContract.PARAM_PICKUP_LNG,
            RideHandoffContract.PARAM_PICKUP_LABEL,
        ) ?: return null
        val dropoff = place(
            uri,
            RideHandoffContract.PARAM_DEST_LAT,
            RideHandoffContract.PARAM_DEST_LNG,
            RideHandoffContract.PARAM_DEST_LABEL,
        ) ?: return null
        val results = runBlocking {
            // Bound the network quote so a slow/hung upstream can't hold a binder thread
            // indefinitely; a timeout degrades to an unavailable row like any other failure.
            withTimeoutOrNull(ESTIMATE_TIMEOUT_MS) { QuoteRepository.quotes(ctx, pickup, dropoff) }
        } ?: return null
        return results.values
            .filterIsInstance<QuoteResult.Success>()
            .flatMap { it.quotes }
            .minByOrNull { it.fareLowMinor }
    }

    private fun place(uri: Uri, latParam: String, lngParam: String, labelParam: String): Place? {
        val lat = uri.getQueryParameter(latParam)?.toDoubleOrNull() ?: return null
        val lng = uri.getQueryParameter(lngParam)?.toDoubleOrNull() ?: return null
        val label = uri.getQueryParameter(labelParam)?.ifBlank { null }.orEmpty()
        return Place(label, null, LatLng(lat, lng))
    }

    private fun formatFare(quote: RideQuote): String {
        fun money(minor: Long) = "$%.2f".format(minor / 100.0)
        return if (quote.fareLowMinor != quote.fareHighMinor) {
            "${money(quote.fareLowMinor)} – ${money(quote.fareHighMinor)}"
        } else {
            money(quote.fareLowMinor)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Upper bound on the upstream quote so the binder call can't hang. */
        private const val ESTIMATE_TIMEOUT_MS = 5_000L
    }
}
