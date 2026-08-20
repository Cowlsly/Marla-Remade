package com.vayunmathur.fooddelivery.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.platform.AppInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exported, signature-guarded lookup that answers "can I order from this
 * restaurant?" for a co-signed app (the maps place sheet). See
 * [OrderLookupContract].
 *
 * A caller queries `content://<AUTHORITY>/lookup?name=&lat=&lng=(&address=)`;
 * the provider fetches the merchant catalog near that point ([BitesApi.getMerchants])
 * and does a tolerant name-normalise + proximity match against it. It always
 * returns exactly one row:
 *  - a match within [MATCH_RADIUS_METERS] whose normalised name matches →
 *    `orderable=1`, the merchant id, and its `fooddelivery://restaurant/<id>` deep link;
 *  - otherwise → `orderable=0` (empty id/deep link).
 *
 * Everything is wrapped so a network/parse failure degrades to a not-orderable
 * row rather than throwing across the binder — the caller then simply shows no
 * Order button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderLookupProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // A provider is created on the main thread before Application.onCreate and before
        // any Activity, so kick the network/token warm-up off to a background thread and
        // return immediately rather than paying for it here. [findMatch] awaits it.
        val ctx = context ?: return false
        AppInit.start(ctx)
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
                OrderLookupContract.COL_ORDERABLE,
                OrderLookupContract.COL_RESTAURANT_ID,
                OrderLookupContract.COL_DEEP_LINK,
            )
        )
        val match = try {
            findMatch(uri)
        } catch (_: Exception) {
            null
        }
        if (match != null) {
            cursor.addRow(arrayOf<Any?>(1, match.id, OrderLookupContract.deepLink(match.id)))
        } else {
            cursor.addRow(arrayOf<Any?>(0, 0, ""))
        }
        return cursor
    }

    private fun findMatch(uri: Uri): Merchant? {
        val name = uri.getQueryParameter(OrderLookupContract.PARAM_NAME)?.trim().orEmpty()
        val lat = uri.getQueryParameter(OrderLookupContract.PARAM_LAT)?.toDoubleOrNull()
        val lng = uri.getQueryParameter(OrderLookupContract.PARAM_LNG)?.toDoubleOrNull()
        if (name.isBlank() || lat == null || lng == null) return null

        val target = normalize(name)
        if (target.isEmpty()) return null

        // This runs on a binder thread out of a 16-strong pool, and the request underneath
        // can take the engine's full 30s connect / 60s read budget (three times over, if the
        // auth path retries). Bound the wait: query() degrades to orderable=0 on a null match,
        // so giving up is strictly better than holding the transaction open. The fetch runs on
        // a scope of its own — cancelling a coroutine parked in a blocking socket read does not
        // unblock it, so the timeout has to sit on a separate, cancellable suspension point.
        val fetch = lookupScope.async {
            AppInit.awaitReady()
            BitesApi.getMerchants(lat, lng)
        }
        val merchants = runBlocking {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { fetch.await() }
        }
        if (merchants == null) fetch.cancel()
        if (merchants.isNullOrEmpty()) return null

        // Keep only merchants that are plausibly the same place: close enough AND
        // whose normalised name matches (equal / prefix / containment). Pick the
        // nearest such candidate.
        return merchants
            .mapNotNull { m ->
                val d = haversineMeters(lat, lng, m.latitude, m.longitude)
                if (d <= MATCH_RADIUS_METERS && nameMatches(target, normalize(m.name))) m to d else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun nameMatches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** Lowercase and strip everything but a–z/0–9 so "Joe's Café" ≈ "joes cafe". */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** How close a merchant must be to the queried point to be the same place. */
        private const val MATCH_RADIUS_METERS = 800.0

        /** Total budget for warm-up plus catalog fetch on the binder thread. */
        private const val LOOKUP_TIMEOUT_MS = 3_000L

        private val lookupScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_INFLIGHT_LOOKUPS))

        /**
         * A timed-out fetch keeps its thread until the engine's own timeouts fire, so cap how
         * many can be in flight rather than letting a chatty caller drain [Dispatchers.IO].
         */
        private const val MAX_INFLIGHT_LOOKUPS = 4
    }
}
