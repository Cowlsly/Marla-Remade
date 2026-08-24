package com.vayunmathur.maps.ipc

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Byte-for-byte mirror of fooddelivery's `OrderLookupContract`.
 *
 * As with the P18 family-location channel, the two apps deliberately do NOT share
 * a module: the contract is just an authority, a signature permission and a
 * handful of query-param / column names, so duplicating it here keeps maps
 * decoupled from fooddelivery's source while still reading the same provider.
 * Reading is gated by the signature permission [PERMISSION] (both MA apps share
 * the signing key).
 */
object OrderLookupContract {
    const val AUTHORITY = "com.vayunmathur.fooddelivery.orderlookup"
    const val PERMISSION = "com.vayunmathur.fooddelivery.permissions.ACCESS_ORDERS"
    const val PATH_LOOKUP = "lookup"

    const val PARAM_NAME = "name"
    const val PARAM_LAT = "lat"
    const val PARAM_LNG = "lng"
    const val PARAM_ADDRESS = "address"

    const val COL_ORDERABLE = "orderable"
    const val COL_RESTAURANT_ID = "restaurant_id"
    const val COL_DEEP_LINK = "deep_link_uri"
}

/** Result of an orderable lookup: whether it's orderable and, if so, its deep link. */
data class OrderInfo(val orderable: Boolean, val deepLinkUri: String?)

/**
 * Reads fooddelivery's exported orderable-lookup provider (see
 * [OrderLookupContract]) for a place at [name] / [lat] / [lng].
 *
 * Absence handling is total: if fooddelivery isn't installed the authority
 * resolves to no provider and the query returns null; if the signature
 * permission isn't held it throws [SecurityException]; a malformed reply or any
 * other failure is caught. In every one of those cases this returns null and the
 * caller simply shows no Order button — no crash.
 */
object OrderLookupClient {
    fun lookup(context: Context, name: String, lat: Double, lng: Double, address: String? = null): OrderInfo? {
        if (name.isBlank()) return null
        val uri = Uri.parse("content://${OrderLookupContract.AUTHORITY}/${OrderLookupContract.PATH_LOOKUP}")
            .buildUpon()
            .appendQueryParameter(OrderLookupContract.PARAM_NAME, name)
            .appendQueryParameter(OrderLookupContract.PARAM_LAT, lat.toString())
            .appendQueryParameter(OrderLookupContract.PARAM_LNG, lng.toString())
            .apply { if (!address.isNullOrBlank()) appendQueryParameter(OrderLookupContract.PARAM_ADDRESS, address) }
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val orderable = c.getInt(c.getColumnIndexOrThrow(OrderLookupContract.COL_ORDERABLE)) == 1
                if (!orderable) return OrderInfo(false, null)
                val deepLink = c.getString(c.getColumnIndexOrThrow(OrderLookupContract.COL_DEEP_LINK))
                    ?.ifBlank { null }
                OrderInfo(true, deepLink)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** Food categories worth checking for orderability. */
private val FOOD_KEYWORDS = listOf(
    "restaurant", "food", "dining", "pizza", "burger", "steak", "sushi",
    "cafe", "coffee", "bakery", "tea", "dessert",
)

private fun isFoodCategory(category: String?): Boolean {
    val c = category?.lowercase() ?: return false
    return FOOD_KEYWORDS.any { it in c }
}

/** Whether this selection is worth asking fooddelivery about at all. */
private fun isOrderableCandidate(feature: SpecificFeature.RoutableFeature, category: String?): Boolean =
    feature is SpecificFeature.Restaurant || isFoodCategory(category)

/**
 * For a selected restaurant/food place, resolve fooddelivery's order deep link
 * (or null if it isn't a food place, isn't orderable, or fooddelivery is absent).
 *
 * The provider query runs on [Dispatchers.IO]; the state re-resolves whenever the
 * selection or its enrichment category changes. Non-food places short-circuit
 * without ever touching the provider.
 */
@Composable
fun rememberOrderDeepLink(
    context: Context,
    feature: SpecificFeature.RoutableFeature,
    category: String?,
): State<String?> = produceState<String?>(null, feature.name, feature.position, category) {
    value = null
    if (!isOrderableCandidate(feature, category)) return@produceState
    val info = withContext(Dispatchers.IO) {
        OrderLookupClient.lookup(context, feature.name, feature.position.latitude, feature.position.longitude)
    }
    value = info?.takeIf { it.orderable }?.deepLinkUri
}
