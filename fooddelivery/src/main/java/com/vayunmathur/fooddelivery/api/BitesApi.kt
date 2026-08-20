package com.vayunmathur.fooddelivery.api

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.SimpleResponse
import android.util.Log
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.data.ApiResponse
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.CustomerSavings
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.DealProgress
import com.vayunmathur.fooddelivery.data.Feedback
import com.vayunmathur.fooddelivery.data.FeedbackRequest
import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.PlatformSavings
import com.vayunmathur.fooddelivery.data.Referral
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MerchantsWrapper
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.Reward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object BitesApi {

    private const val BASE = "https://api.deliverycollective.com"
    private const val API = "$BASE/api/v1"

    /** Absolute expiry we persist alongside the token so a cold start knows if it is stale. */
    private const val EXPIRES_AT_KEY = "expires_at_ms"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        // Without encodeDefaults, kotlinx drops every field still equal to its default —
        // on a checkout that silently removed isMobile/isPickup/inStore/leaveAtDoor, tips
        // when 0, and each modifier's quantity, none of which is visible in the Kotlin
        // source. The reference sends all of them on every order.
        encodeDefaults = true
        // ...but it must not turn optional fields into explicit nulls: the reference leaves
        // promoCode/scheduledDate/scheduledTime/isDriveThru off the wire entirely when unset
        // (JSON.stringify drops undefined), so omit nulls to match.
        explicitNulls = false
    }

    @Volatile
    private var storedToken: AuthToken? = null

    @Volatile
    private var expiresAtMs: Long = 0
    var onTokenUpdated: ((String) -> Unit)? = null

    /** Serialises refreshes: the refresh token rotates, so two in flight invalidate each other. */
    private val refreshMutex = Mutex()

    private inline fun logd(message: () -> String) {
        if (BuildConfig.DEV_BUILD) Log.d("BitesApi", message())
    }

    fun setToken(token: AuthToken?) {
        storedToken = token
        if (token == null) return
        if (token.expires_in > 0) {
            expiresAtMs = System.currentTimeMillis() + token.expires_in * 1000
        }
        onTokenUpdated?.invoke(encodeTokenForStorage(token, expiresAtMs))
    }

    fun restoreToken(tokenJson: String) {
        try {
            val root = json.parseToJsonElement(tokenJson).jsonObject
            storedToken = json.decodeFromJsonElement(AuthToken.serializer(), root)
            expiresAtMs = root[EXPIRES_AT_KEY]?.jsonPrimitive?.longOrNull ?: 0L
        } catch (e: Exception) {
            Log.e("BitesApi", "restoreToken failed", e)
        }
    }

    /**
     * The token plus its absolute expiry. Persisting only `expires_in` meant every cold
     * start treated the token as unknown-expiry and forced a refresh on the first request.
     */
    private fun encodeTokenForStorage(token: AuthToken, expiryMs: Long): String {
        val encoded = json.encodeToJsonElement(AuthToken.serializer(), token).jsonObject
        return buildJsonObject {
            encoded.forEach { (k, v) -> put(k, v) }
            put(EXPIRES_AT_KEY, expiryMs)
        }.toString()
    }

    fun isLoggedIn(): Boolean = storedToken != null && storedToken!!.access_token.isNotEmpty()

    fun clearToken() {
        storedToken = null
        expiresAtMs = 0
    }

    /**
     * One refresh at a time. Callers that queue behind the mutex re-check [storedToken]:
     * if it already moved on, the refresh someone else did is theirs to use.
     */
    private suspend fun refreshToken(stale: AuthToken): AuthToken? = refreshMutex.withLock {
        val current = storedToken
        if (current != null && current.access_token != stale.access_token) return@withLock current
        val refresh = current?.refresh_token
        if (refresh.isNullOrEmpty()) return@withLock null
        exchangeRefreshTokenForToken(refresh)?.also { setToken(it) }
    }

    private suspend fun getAccessToken(): String? {
        val token = storedToken ?: return null
        if (token.access_token.isEmpty()) return null
        if (token.refresh_token.isNotEmpty() &&
            (expiresAtMs == 0L || System.currentTimeMillis() > expiresAtMs)) {
            refreshToken(token)?.let { return it.access_token }
        }
        return token.access_token
    }

    private suspend fun authenticatedRequest(url: String, method: String = "GET", body: String? = null): SimpleResponse {
        var resp = NetworkClient.performRequest(url, method, headers(), body)
        logd { "$method $url -> ${resp.status}" }
        val token = storedToken
        if (resp.status == 401 && token != null && token.refresh_token.isNotEmpty()) {
            if (refreshToken(token) != null) {
                resp = NetworkClient.performRequest(url, method, headers(), body)
                logd { "retry $method $url -> ${resp.status}" }
            } else {
                logd { "token refresh failed" }
            }
        }
        return resp
    }

    private suspend fun headers(): Map<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        getAccessToken()?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    // ---- Auth ----

    suspend fun verifyPhone(phone: String): String? = withContext(Dispatchers.Default) {
        try {
            val cleanPhone = "+" + phone.replace(Regex("\\D"), "")
            val body = """{"phoneNumber":"$cleanPhone","audience":"customer","scope":"openid email profile offline_access"}"""
            val resp = NetworkClient.performRequest(
                "$BASE/auth/verify_phone", "POST",
                mapOf("Content-Type" to "application/json"), body
            )
            logd { "verifyPhone -> ${resp.status}" }
            if (resp.isSuccess) {
                val parsed = json.parseToJsonElement(resp.body)
                if (parsed is JsonObject) {
                    (parsed["state_id"] ?: parsed["stateId"])?.toString()?.trim('"')
                } else null
            } else null
        } catch (e: Exception) {
            // Swallowing this silently is what made a TLS-pinning failure look like
            // "login does nothing" with an empty logcat.
            Log.e("BitesApi", "verifyPhone failed", e)
            null
        }
    }

    suspend fun exchangeOtpCodeForToken(stateId: String, code: String): AuthToken? = withContext(Dispatchers.Default) {
        try {
            val body = "grant_type=phone_otp&state_id=$stateId&code=$code"
            val resp = NetworkClient.performRequest(
                "$BASE/auth/token", "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                body
            )
            logd { "exchangeOtp -> ${resp.status}" }
            if (resp.isSuccess) json.decodeFromString<AuthToken>(resp.body) else null
        } catch (e: Exception) {
            Log.e("BitesApi", "exchangeOtp failed", e)
            null
        }
    }

    private suspend fun exchangeRefreshTokenForToken(refreshToken: String): AuthToken? = withContext(Dispatchers.Default) {
        try {
            val body = "grant_type=refresh_token&refresh_token=$refreshToken"
            val resp = NetworkClient.performRequest(
                "$BASE/auth/token", "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                body
            )
            logd { "refreshToken -> ${resp.status}" }
            if (resp.isSuccess) json.decodeFromString<AuthToken>(resp.body) else null
        } catch (e: Exception) {
            Log.e("BitesApi", "refreshToken failed", e)
            null
        }
    }

    // ---- Merchants ----

    suspend fun getMerchants(lat: Double? = null, lng: Double? = null): List<Merchant> = withContext(Dispatchers.Default) {
        try {
            val params = buildString {
                val parts = mutableListOf<String>()
                if (lat != null) parts.add("lat=$lat")
                if (lng != null) parts.add("lng=$lng")
                if (parts.isNotEmpty()) append("?${parts.joinToString("&")}")
            }
            val resp = authenticatedRequest("$API/merchants/all/stores$params")
            if (resp.isSuccess) {
                val wrapper = json.decodeFromString<ApiResponse<MerchantsWrapper>>(resp.body)
                wrapper.data?.merchants ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getMerchantDetail(id: Int): MerchantDetail? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/merchants/$id")
            if (resp.isSuccess) {
                val wrapper = json.decodeFromString<ApiResponse<MerchantDetail>>(resp.body)
                wrapper.data
            } else null
        } catch (_: Exception) { null }
    }

    // ---- Deals ----

    suspend fun getDeals(lat: Double? = null, lng: Double? = null): List<Deal> = withContext(Dispatchers.Default) {
        try {
            val params = buildString {
                val parts = mutableListOf<String>()
                if (lat != null) parts.add("lat=$lat")
                if (lng != null) parts.add("lng=$lng")
                if (parts.isNotEmpty()) append("?${parts.joinToString("&")}")
            }
            val resp = NetworkClient.performRequest(
                "$API/deals/active$params", headers = headers()
            )
            if (resp.isSuccess) {
                json.decodeFromString<ApiResponse<List<Deal>>>(resp.body).data ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ---- Orders ----

    suspend fun getOrders(): List<Order> = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/orders/past/all")
            if (resp.isSuccess) {
                json.decodeFromString<ApiResponse<List<Order>>>(resp.body).data ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.e("BitesApi", "getOrders failed", e)
            emptyList()
        }
    }

    /** GET /orders/pickUpOrder/{uuid} — marks a pickup order collected. */
    suspend fun pickUpOrder(orderUuid: String): Boolean = try {
        authenticatedRequest("$API/orders/pickUpOrder/$orderUuid").isSuccess
    } catch (e: Exception) {
        Log.e("BitesApi", "pickUpOrder failed", e); false
    }

    /** GET /orders/email/{token} — look an order up by its email link. */
    suspend fun getOrderByEmail(token: String): Order? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/orders/email/$token")
            if (!resp.isSuccess) null else unwrap(resp.body, Order.serializer())
        } catch (e: Exception) {
            Log.e("BitesApi", "getOrderByEmail failed", e); null
        }
    }

    /** POST /merchants/{id}/check-serviceability — can this merchant deliver to [address]? */
    suspend fun checkServiceability(merchantId: Int, address: CheckoutAddress): Boolean? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(
                "$API/merchants/$merchantId/check-serviceability", "POST",
                json.encodeToString(CheckoutAddress.serializer(), address),
            )
            if (resp.isSuccess) true else if (resp.status in 400..499) false else null
        } catch (e: Exception) {
            Log.e("BitesApi", "checkServiceability failed", e); null
        }
    }

    // ---- Customer ----

    suspend fun getCustomer(): Customer? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/customers/me")
            if (resp.isSuccess) unwrap(resp.body, Customer.serializer()) else null
        } catch (_: Exception) { null }
    }

    // ---- Savings & Rewards ----

    suspend fun getCustomerSavings(): CustomerSavings? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/orders/me/savings")
            if (resp.isSuccess) unwrap(resp.body, CustomerSavings.serializer()) else null
        } catch (_: Exception) { null }
    }

    /**
     * GET /orders/{uuid}/rewards — the credit applied to a specific order. The reference
     * calls this right before showing the total and subtracts `rewardsAvailable` from the
     * order's component sum (bites-js-decompiled.js:1255938).
     */
    suspend fun getOrderRewards(orderUuid: String): OrderRewards? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/orders/$orderUuid/rewards")
            if (!resp.isSuccess) null else unwrap(resp.body, OrderRewards.serializer())
        } catch (e: Exception) {
            Log.e("BitesApi", "getOrderRewards failed", e)
            null
        }
    }

    suspend fun getRewards(): List<Reward> = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest("$API/rewards")
            if (resp.isSuccess) {
                json.decodeFromString<ApiResponse<List<Reward>>>(resp.body).data ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ---- Feedback (POST/GET /orders/{uuid}/feedback) ----

    suspend fun submitFeedback(orderUuid: String, request: FeedbackRequest): Boolean = withContext(Dispatchers.Default) {
        try {
            authenticatedRequest(
                "$API/orders/$orderUuid/feedback", "POST",
                json.encodeToString(FeedbackRequest.serializer(), request),
            ).isSuccess
        } catch (e: Exception) { Log.e("BitesApi", "submitFeedback failed", e); false }
    }

    suspend fun getFeedback(orderUuid: String): Feedback? =
        decodeData("$API/orders/$orderUuid/feedback", Feedback.serializer())

    // ---- Referrals & email verification ----

    suspend fun createReferral(uuid: String, orderId: Int): Boolean = try {
        authenticatedRequest(
            "$API/customers/createReferral", "POST",
            """{"uuid":"$uuid","orderId":$orderId}""",
        ).isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "createReferral failed", e); false }

    suspend fun getReferrals(): List<Referral> =
        decodeDataList("$API/customers/getReferrals", Referral.serializer())

    suspend fun sendEmailVerification(email: String): Boolean = try {
        authenticatedRequest(
            "$API/customers/send-email-verification", "POST",
            """{"email":"$email"}""",
        ).isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "sendEmailVerification failed", e); false }

    suspend fun verifyEmailToken(token: String): Boolean = try {
        authenticatedRequest("$API/customers/verify-email/$token", "POST", "{}").isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "verifyEmailToken failed", e); false }

    // ---- Customer profile ----

    /** POST /customers/me — create or update the signed-in customer. */
    suspend fun createOrUpdateCustomer(customer: Customer): Customer? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(
                "$API/customers/me", "POST",
                json.encodeToString(Customer.serializer(), customer),
            )
            if (!resp.isSuccess) null else unwrap(resp.body, Customer.serializer())
        } catch (e: Exception) { Log.e("BitesApi", "createOrUpdateCustomer failed", e); null }
    }

    /** DELETE /customers/me — permanent account deletion. */
    suspend fun deleteCustomer(): Boolean = try {
        authenticatedRequest("$API/customers/me", "DELETE").isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "deleteCustomer failed", e); false }

    /** POST /customers/me/pushNotifications — body is {token, uuid}. */
    suspend fun registerPushNotification(token: String, uuid: String): Boolean = try {
        authenticatedRequest(
            "$API/customers/me/pushNotifications", "POST",
            """{"token":"$token","uuid":"$uuid"}""",
        ).isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "registerPushNotification failed", e); false }

    // ---- Merchant loyalty & rewards ----

    /** GET /customers/me/rewards — reward balance at every merchant, as the reference does. */
    suspend fun getCustomerMerchantRewards(): List<MerchantRewards> =
        decodeDataList("$API/customers/me/rewards", MerchantRewards.serializer())

    /** POST /customers/merchantLoyaltyCode — body is {inviteCode, merchantId}. */
    suspend fun createCustomerMerchantLoyalty(inviteCode: String, merchantId: Int): Boolean = try {
        authenticatedRequest(
            "$API/customers/merchantLoyaltyCode", "POST",
            """{"inviteCode":"$inviteCode","merchantId":$merchantId}""",
        ).isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "createCustomerMerchantLoyalty failed", e); false }

    /** POST (not DELETE) /customers/deleteCustomerMerchantLoyalty — body is {merchantId}. */
    suspend fun deleteCustomerMerchantLoyalty(merchantId: Int): Boolean = try {
        authenticatedRequest(
            "$API/customers/deleteCustomerMerchantLoyalty", "POST",
            """{"merchantId":$merchantId}""",
        ).isSuccess
    } catch (e: Exception) { Log.e("BitesApi", "deleteCustomerMerchantLoyalty failed", e); false }

    // ---- Merchants & savings ----

    suspend fun getMerchantByName(storefrontAlias: String): MerchantDetail? =
        decodeData("$API/merchants/storefront/$storefrontAlias", MerchantDetail.serializer())

    suspend fun getMerchantReporting(merchantId: Int): String? = try {
        authenticatedRequest("$API/merchants/reporting/$merchantId").takeIf { it.isSuccess }?.body
    } catch (e: Exception) { Log.e("BitesApi", "getMerchantReporting failed", e); null }

    suspend fun getPlatformSavings(): PlatformSavings? =
        decodeData("$API/orders/savings/platform", PlatformSavings.serializer())

    // ---- Deals ----

    suspend fun getAllDeals(): List<Deal> = decodeDataList("$API/deals", Deal.serializer())

    suspend fun getDealById(dealId: Int): Deal? =
        decodeData("$API/deals/$dealId", Deal.serializer())

    suspend fun getDealsByMerchant(merchantId: Int): List<Deal> =
        decodeDataList("$API/deals/merchant/$merchantId", Deal.serializer())

    suspend fun getActiveDealsByMerchant(merchantId: Int): List<Deal> =
        decodeDataList("$API/deals/merchant/$merchantId/active", Deal.serializer())

    suspend fun getDealProgress(dealId: Int): DealProgress? =
        decodeData("$API/deals/$dealId/progress", DealProgress.serializer())

    // ---- Shared decoding helpers ----

    /**
     * Unwrap the `{message, data}` envelope these endpoints use, falling back to the root.
     * The element is handed straight to the deserializer — re-serialising it to a String and
     * parsing that back is a third full pass plus a duplicate of the whole payload.
     */
    private fun <T> unwrap(body: String, serializer: KSerializer<T>): T? {
        val root = json.parseToJsonElement(body)
        val el = (root as? JsonObject)?.get("data") ?: root
        return json.decodeFromJsonElement(serializer, el)
    }

    private suspend fun <T> decodeData(
        url: String,
        serializer: KSerializer<T>,
    ): T? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(url)
            if (!resp.isSuccess) null else unwrap(resp.body, serializer)
        } catch (e: Exception) { Log.e("BitesApi", "GET $url failed", e); null }
    }

    private suspend fun <T> decodeDataList(
        url: String,
        serializer: KSerializer<T>,
    ): List<T> = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(url)
            if (!resp.isSuccess) emptyList()
            else unwrap(resp.body, ListSerializer(serializer)) ?: emptyList()
        } catch (e: Exception) { Log.e("BitesApi", "GET $url failed", e); emptyList() }
    }

    // ---- Checkout ----

    suspend fun checkout(merchantId: Int, request: CheckoutRequest): CheckoutResponse? = withContext(Dispatchers.Default) {
        try {
            val body = json.encodeToString(CheckoutRequest.serializer(), request)
            val resp = authenticatedRequest("$API/merchants/$merchantId/checkout", "POST", body)
            logd { "checkout -> ${resp.status}" }
            if (resp.isSuccess) unwrap(resp.body, CheckoutResponse.serializer()) else null
        } catch (e: Exception) {
            Log.e("BitesApi", "checkout failed", e)
            null
        }
    }
}
