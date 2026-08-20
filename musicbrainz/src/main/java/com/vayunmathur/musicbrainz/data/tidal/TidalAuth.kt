package com.vayunmathur.musicbrainz.data.tidal

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.Base64

/** The tokens a successful sign-in yields, plus who they belong to. */
data class TidalTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val userId: String,
    val countryCode: String,
    val username: String,
)

/** The device code and where to enter it, from the start of the sign-in. */
data class TidalDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

/** One poll's outcome while the user completes sign-in on another device. */
sealed interface TidalPollResult {
    /** The user has not finished yet; keep polling. */
    data object Pending : TidalPollResult

    /** The device code's lifetime ran out before the user finished. */
    data object Expired : TidalPollResult

    /** Poll interval was too short; back off before the next poll. */
    data object SlowDown : TidalPollResult

    data class Success(val tokens: TidalTokens) : TidalPollResult

    data class Error(val message: String) : TidalPollResult
}

/**
 * Tidal OAuth 2.0 device authorization grant.
 *
 * Chosen over the redirect/PKCE flow the email app uses because it needs no intent-filter
 * or redirect activity: the user enters a short code at [TidalDeviceCode.verificationUri]
 * and this polls for the token. Ported from tiddl's `core/auth`, including its well-known
 * client credentials - Tidal has no public app registration, so every open client uses
 * the same pair, stored base64-encoded the way tiddl does.
 *
 * [NetworkClient.callJson] throws on any non-2xx, but `authorization_pending` - the normal
 * idle response of a device-code poll - comes back as HTTP 400, so this uses
 * [NetworkClient.performRequest] and reads the JSON body by hand instead.
 */
object TidalAuth {

    private const val AUTH_URL = "https://auth.tidal.com/v1/oauth2"
    private const val LOGOUT_URL = "https://api.tidal.com/v1/logout"
    private const val SCOPE = "r_usr+w_usr+w_sub"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    // client_id;client_secret, base64-encoded exactly as tiddl stores it.
    private const val CREDENTIALS =
        "NE4zbjZRMXg5NUxMNUs3cDtvS09YZkpXMzcxY1g2eGFaMFB5aGdHTkJkTkxsQlpkNEFLS1lvdWdNamlrPQ=="

    private val clientId: String
    private val clientSecret: String

    init {
        val pair = String(Base64.getDecoder().decode(CREDENTIALS), Charsets.UTF_8).split(";", limit = 2)
        clientId = pair[0]
        clientSecret = pair.getOrElse(1) { "" }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val formHeaders = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
    )

    private val basicAuthHeaders = formHeaders + mapOf(
        "Authorization" to "Basic " + Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8)),
    )

    /** Begins sign-in. Throws on a network or protocol failure. */
    suspend fun requestDeviceCode(): TidalDeviceCode {
        val body = form("client_id" to clientId, "scope" to SCOPE)
        val response = NetworkClient.performRequest(
            "$AUTH_URL/device_authorization",
            method = "POST",
            headers = formHeaders,
            body = body,
        )
        if (!response.isSuccess) {
            throw java.io.IOException("Tidal device auth failed: HTTP ${response.status}")
        }
        val root = json.parseToJsonElement(response.body).jsonObject()
        return TidalDeviceCode(
            deviceCode = root.string("deviceCode").orEmpty(),
            userCode = root.string("userCode").orEmpty(),
            verificationUri = root.string("verificationUriComplete")
                ?.let { if (it.startsWith("http")) it else "https://$it" }
                .orEmpty(),
            intervalSeconds = root.int("interval", 2),
            expiresInSeconds = root.int("expiresIn", 300),
        )
    }

    /** Polls once for the token belonging to [deviceCode]. */
    suspend fun poll(deviceCode: String): TidalPollResult {
        val body = form(
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to DEVICE_GRANT,
            "scope" to SCOPE,
        )
        // A network blip mid-sign-in must not end the flow: report it as pending so the
        // caller keeps polling until the device code's own deadline.
        val response = runCatching {
            NetworkClient.performRequest(
                "$AUTH_URL/token",
                method = "POST",
                headers = basicAuthHeaders,
                body = body,
            )
        }.getOrNull() ?: return TidalPollResult.Pending
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject() }.getOrNull()
        if (response.isSuccess && root != null) {
            return TidalPollResult.Success(tokens(root))
        }
        return when (root?.string("error")) {
            "authorization_pending" -> TidalPollResult.Pending
            "slow_down" -> TidalPollResult.SlowDown
            "expired_token" -> TidalPollResult.Expired
            null -> TidalPollResult.Error("HTTP ${response.status}")
            else -> TidalPollResult.Error(
                root.string("error_description") ?: root.string("error").orEmpty(),
            )
        }
    }

    /** Exchanges a refresh token for a fresh access token, or null on failure. */
    suspend fun refresh(refreshToken: String): TidalTokens? {
        val body = form(
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
            "scope" to SCOPE,
        )
        val response = NetworkClient.performRequest(
            "$AUTH_URL/token",
            method = "POST",
            headers = basicAuthHeaders,
            body = body,
        )
        if (!response.isSuccess) return null
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject() }.getOrNull()
            ?: return null
        // A refresh response carries no refresh_token of its own, so the caller keeps the old one.
        return tokens(root, fallbackRefresh = refreshToken)
    }

    /** Tells Tidal to drop the session. Best-effort; failures are ignored. */
    suspend fun logout(accessToken: String) {
        runCatching {
            NetworkClient.performRequest(
                LOGOUT_URL,
                method = "POST",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
            )
        }
    }

    private fun tokens(root: JsonObject, fallbackRefresh: String? = null): TidalTokens {
        val user = (root["user"] as? JsonObject)
        val expiresIn = root.long("expires_in") ?: 86_400L
        return TidalTokens(
            accessToken = root.string("access_token").orEmpty(),
            refreshToken = root.string("refresh_token") ?: fallbackRefresh,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
            userId = root.string("user_id") ?: user?.string("userId").orEmpty(),
            countryCode = user?.string("countryCode").orEmpty(),
            username = user?.string("username")
                ?: user?.string("email")
                ?: user?.string("nickname").orEmpty(),
        )
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObject(): JsonObject = this as JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String, default: Int): Int = long(key)?.toInt() ?: default
}
