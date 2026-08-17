package com.vayunmathur.fooddelivery.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.runBlocking
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
class OrderLookupProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // A provider can be created before any Activity runs, so make sure the
        // network stack (TLS trust bundle) and any saved auth token are ready —
        // MainActivity does the same on its own launch path.
        val ctx = context ?: return false
        try {
            NetworkClient.init(ctx, TrustBundle.STANDARD)
            val prefs = ctx.getSharedPreferences("fooddelivery_prefs", Context.MODE_PRIVATE)
            prefs.getString("token_json", null)?.let { BitesApi.restoreToken(it) }
        } catch (_: Exception) {
            // Best-effort; a lookup that then fails just returns not-orderable.
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

        val merchants = runBlocking { BitesApi.getMerchants(lat, lng) }
        if (merchants.isEmpty()) return null

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
    }
}
