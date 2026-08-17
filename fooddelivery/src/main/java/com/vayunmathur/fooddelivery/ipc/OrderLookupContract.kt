package com.vayunmathur.fooddelivery.ipc

/**
 * Wire contract for the cross-app "is this restaurant orderable?" lookup exposed
 * by [OrderLookupProvider].
 *
 * A co-signed app (the maps app) queries the provider with a restaurant name +
 * lat/lng (+ optional address) and gets back a single row telling it whether the
 * place is orderable on this app and, if so, the deep link that opens its order
 * page. The provider is gated by [PERMISSION] (signature-level), so only apps
 * signed with the same key can read it; the two apps deliberately do NOT share a
 * module — maps keeps its own byte-for-byte mirror of these constants.
 */
object OrderLookupContract {
    /** Authority of the exported orderable-lookup [android.content.ContentProvider]. */
    const val AUTHORITY = "com.vayunmathur.fooddelivery.orderlookup"

    /** Signature-level permission required to read the provider. */
    const val PERMISSION = "com.vayunmathur.fooddelivery.permissions.ACCESS_ORDERS"

    /** Single query path: content://<AUTHORITY>/lookup?name=&lat=&lng=&address= */
    const val PATH_LOOKUP = "lookup"

    // ---- query parameters (caller → provider) ----
    const val PARAM_NAME = "name"
    const val PARAM_LAT = "lat"
    const val PARAM_LNG = "lng"
    const val PARAM_ADDRESS = "address"

    // ---- result columns (provider → caller) ----
    /** Int 1/0: whether a tolerant name+proximity match was found and is orderable. */
    const val COL_ORDERABLE = "orderable"
    /** Int: the matched merchant id (0 when not orderable). */
    const val COL_RESTAURANT_ID = "restaurant_id"
    /** String: deep link opening the matched merchant's order page ("" when not orderable). */
    const val COL_DEEP_LINK = "deep_link_uri"

    // ---- deep link (open a specific restaurant's order page) ----
    const val DEEP_LINK_SCHEME = "fooddelivery"
    const val DEEP_LINK_HOST = "restaurant"

    /** Build the deep link that opens merchant [id]'s order page. */
    fun deepLink(id: Int): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$id"
}
