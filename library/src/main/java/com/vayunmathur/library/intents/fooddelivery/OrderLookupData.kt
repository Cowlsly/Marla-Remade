package com.vayunmathur.library.intents.fooddelivery

import kotlinx.serialization.Serializable

/**
 * Shared wire DTOs for fooddelivery's cross-app order-lookup
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent]
 * (`com.vayunmathur.fooddelivery.intents.OrderLookupIntent`), answered for a co-signed caller (the
 * maps place sheet). This replaces the old `content://com.vayunmathur.fooddelivery.orderlookup/lookup`
 * cursor: a ContentProvider is the wrong shape for a live, time-varying catalog match.
 */
@Serializable
data class OrderLookupRequest(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
)

/**
 * @property available whether a tolerant name+proximity match was found and is orderable.
 * @property merchantId matched merchant id (0 when not orderable).
 * @property deepLink `fooddelivery://restaurant/<id>` opening the matched order page ("" when not orderable).
 */
@Serializable
data class OrderLookupResult(
    val available: Boolean,
    val merchantId: Int,
    val deepLink: String,
)
