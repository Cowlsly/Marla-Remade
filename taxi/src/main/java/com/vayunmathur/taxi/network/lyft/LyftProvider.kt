package com.vayunmathur.taxi.network.lyft

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.provider.RideProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lyft fares from `POST /v2/offerings` (`/pb.api.endpoints.v1.offers.Offers/ReadOffersV2`).
 *
 * Request/response shapes recovered from the production APK (`me.lyft.android` v2026.29.3) with
 * jadx — see `lyft-re/api-notes.md` §3. The request is the proto-JSON form of `OffersRequestDTO`.
 * On success the server negotiates a body: it may reply with proto-JSON **or** binary protobuf
 * depending on `Accept`, so we read raw bytes and parse whichever came back (protobuf via the
 * hand-rolled reader below, keyed by the same field tags as the DTOs).
 */
class LyftProvider(private val context: Context) : RideProvider {
    override val provider = Provider.LYFT

    private val tokens = LyftTokenStore(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * TLS factory over the platform (system) CA store, for the external payment processors
     * (Stripe/Braintree). The shared [NetworkClient] is initialised with a reduced trust bundle
     * that only covers Lyft's own hosts, and its `useSystemTrust` flag falls back to that bundle
     * rather than to system trust — so passing an explicit system factory is the only way to
     * reach public processor endpoints without touching the shared network library.
     */
    private val systemTrustFactory: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // null keystore => platform default CAs
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    override suspend fun isSignedIn(): Boolean = tokens.isSignedIn()

    override suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult {
        val token = accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(token, "${LyftAuth.BASE}/v2/offerings", offersBody(pickup, dropoff, null, null))
    }

    /**
     * Re-quotes an existing offer set via `/v2/offerings/update` (`ReadOffersV2Update`), carrying
     * [lastOffersId] (the previous `offers_response_id`) and [purchaseSessionId] so the server
     * refreshes the same session's prices/cost-tokens. Used to replace fares whose cost token is
     * about to expire. Response shape matches `/v2/offerings`.
     */
    suspend fun updateQuotes(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): QuoteResult {
        val token = accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(
            token,
            "${LyftAuth.BASE}/v2/offerings/update",
            offersBody(pickup, dropoff, lastOffersId, purchaseSessionId),
        )
    }

    /**
     * OffersRequestDTO body shared by `/v2/offerings` and `/v2/offerings/update`. See api-notes §3:
     * origin(1)+destination(2) as E6LatLng, request_source=OFFER_SELECTOR,
     * offer_selector_type=CATEGORIZED_VERTICAL, a session id, and an entry context. The update
     * path additionally carries `last_offers_id` + `purchase_session_id` to refresh a session.
     */
    private fun offersBody(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): String = buildString {
        append("{")
        append(""""origin":${e6(pickup)},""")
        append(""""destination":${e6(dropoff)},""")
        append(""""request_source":"OFFER_SELECTOR",""")
        append(""""offer_selector_type":"CATEGORIZED_VERTICAL",""")
        append(""""offer_selector_session_id":"${java.util.UUID.randomUUID()}",""")
        lastOffersId?.let { append(""""last_offers_id":"$it",""") }
        purchaseSessionId?.let { append(""""purchase_session_id":"$it",""") }
        append(""""request_entry_context":{"entry_point":{"home":{}}}""")
        append("}")
    }

    private suspend fun readOffers(token: String, url: String, body: String): QuoteResult {
        val resp = NetworkClient.execute(
            url = url,
            method = "POST",
            // Reuse the exact standard header set the app sends (user-agent, user-device,
            // x-design-id, locale, timestamps). Without a valid `user-agent` the server
            // rejects the call outright ("invalid user agent") even with a good token.
            headers = LyftAuth.commonHeaders() + mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/x-protobuf, application/json",
            ),
            body = body,
        )
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        Log.d(TAG, "POST $url -> ${resp.status} ($contentType, ${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            // Error bodies come back as JSON regardless of Accept; surface the reason.
            return QuoteResult.Failed("Lyft returned HTTP ${resp.status}: ${resp.text.take(300)}")
        }

        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var parsed = runCatching {
            if (isProto) parseOfferingsProto(resp.bytes) else parseOfferingsJson(resp.text)
        }.onFailure { Log.w(TAG, "primary parse failed (proto=$isProto)", it) }.getOrDefault(ParsedOffers.EMPTY)
        // Content-Type can lie or be absent; fall back to the other codec before giving up.
        if (parsed.quotes.isEmpty()) {
            parsed = runCatching {
                if (isProto) parseOfferingsJson(resp.text) else parseOfferingsProto(resp.bytes)
            }.getOrDefault(ParsedOffers.EMPTY)
        }

        return if (parsed.quotes.isEmpty()) {
            QuoteResult.Failed("Lyft 200 ($contentType) but no offers parsed")
        } else {
            QuoteResult.Success(
                quotes = parsed.quotes,
                purchaseSessionId = parsed.purchaseSessionId,
                offersResponseId = parsed.offersResponseId,
            )
        }
    }

    override fun bookingUri(pickup: Place, dropoff: Place, quote: RideQuote?): String =
        RideDeepLinks.webUri(Provider.LYFT, pickup, dropoff, quote)

    // ----------------------------------------------------------------------------------------
    // In-app booking + payment management
    //
    // Endpoints recovered from the APK (`defpackage/l77`, `d77`, `ech0`, response DTO `u77` /
    // `c67`):
    //   GET    /chargeaccounts                → ReadChargeAccounts          (list)
    //   PUT    /charge-accounts-multi-provider → UpdateChargeAccount…      (set default)
    //   DELETE /chargeaccounts/{id}          → DeleteChargeAccount          (remove)
    //   POST   /v1/core_trips/create         → CreateTrip                   (book)
    //   GET    /v1/activeride                → ReadActiveRide               (status)
    //   POST   /v1/rides/{id}/cancel         → CancelRide                   (cancel)
    // Payment methods are held only in memory by callers and never persisted here.
    // ----------------------------------------------------------------------------------------

    override suspend fun paymentMethods(): PaymentMethodsResult {
        val token = accessToken() ?: return PaymentMethodsResult.NotSignedIn
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /chargeaccounts -> ${resp.status} (${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            return PaymentMethodsResult.Failed(httpError(resp))
        }
        return PaymentMethodsResult.Success(parseChargeAccounts(resp))
    }

    override suspend fun setDefaultPaymentMethod(id: String): PaymentActionResult {
        val token = accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        // UpdateChargeAccountMultiProvider (fvf0). The real client (t77.b via ech0, for
        // MakePersonalDefault + TriggerDebtCollection) always sends four fields on this PUT:
        //   default (tag 2, BoolValue)              = true
        //   charge_account_id (tag 4, string)       = id
        //   skip_debt_collection (tag 8, BoolValue) = false  (TriggerDebtCollection)
        //   skip_persisted_challenge (tag 9, ...)   = false
        // Omitting the two skip_* wrappers makes the server reject the update with 422, so we
        // mirror the real client exactly.
        val body = buildJsonObject {
            put("charge_account_id", id)
            put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
        }.toString()
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
            method = "PUT",
            headers = authProtoJsonHeaders(
                token,
                "pb.api.endpoints.charge_accounts.UpdateChargeAccountMultipleProviderRequest",
            ),
            body = body,
        )
        Log.d(TAG, "PUT /charge-accounts-multi-provider -> ${resp.status}")
        if (!resp.isSuccess) {
            // 422s here are opaque without the server's reason — log the full exchange so the
            // exact validation error (field name / compliance / challenge) is visible.
            Log.w(TAG, "set-default failed ${resp.status}: req=$body resp=${resp.text}")
            return PaymentActionResult.Failed(httpError(resp))
        }
        // The response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { parseChargeAccounts(resp) }.getOrDefault(emptyList())
        return PaymentActionResult.Success(accounts.ifEmpty { null })
    }

    override suspend fun removePaymentMethod(id: String): PaymentActionResult {
        val token = accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts/${Uri.encode(id)}",
            method = "DELETE",
            headers = authHeaders(token),
        )
        Log.d(TAG, "DELETE /chargeaccounts/{id} -> ${resp.status}")
        if (!resp.isSuccess) return PaymentActionResult.Failed(httpError(resp))
        // Callers re-fetch the list after a delete.
        return PaymentActionResult.Success(null)
    }

    // ----------------------------------------------------------------------------------------
    // Add a card (Create)
    //
    // Lyft never accepts a raw PAN. The card is tokenized by a payment processor first, and only
    // the resulting token/nonce is sent on. Two hops:
    //   1. POST /v1/tokenization_strategies (PostTokenizationStrategies, req `n1z` / resp `o1z`)
    //      → a list of TokenizationStrategy (`sbe0`), each carrying its processor's client key
    //        under `api_key` (Stripe publishable key `thc0`, Braintree client token `zo3`).
    //      NB: the strategy comes from *this* endpoint, not from `payment_options_response`.
    //   2. Tokenize at the processor (Stripe `POST /v1/tokens`, Braintree GraphQL) → token|nonce.
    //   3. POST /charge-accounts-multi-provider (CreateChargeAccountMultiProvider, req `una`)
    //      with provider_representation + card_meta_data → refreshed ChargeAccountsResponse.
    // Card data lives only for the duration of this call; full PAN/CVV are never logged.
    // ----------------------------------------------------------------------------------------

    override suspend fun addCard(card: NewCard, makeDefault: Boolean): AddCardResult {
        val token = accessToken() ?: return AddCardResult.Failed("Not signed in to Lyft")

        // No retrievable processor config → native add-card is blocked for this session.
        val config = fetchTokenizerConfig(token, card) ?: return AddCardResult.Unsupported

        val tokenized = when (val r = tokenizeCard(config, card)) {
            is TokenizeResult.Err -> return AddCardResult.Failed(r.message)
            is TokenizeResult.Ok -> r
        }

        // `una`: default(2, bool), provider_representation(4, repeated `mt00`), card_meta_data(6,
        // `lf6`), skip_debt_collection(8), skip_persisted_challenge(9). The real create (`t77.a`)
        // sets default only when making default, always sends the two skip_* flags, and each Stripe
        // `mt00` carries a `version` (STRIPE_TOKEN / STRIPE_SETUP_INTENT). Wrapper types serialize
        // as bare scalars in proto3-JSON, so this maps 1:1 onto the DTO.
        val body = buildJsonObject {
            if (makeDefault) put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
            putJsonArray("provider_representation") {
                addJsonObject {
                    put("provider", tokenized.provider)
                    tokenized.token?.let { put("token", it) }
                    tokenized.nonce?.let { put("nonce", it) }
                    tokenized.version?.let { put("version", it) }
                }
            }
            putJsonObject("card_meta_data") {
                put("expiration_month", card.expMonth)
                put("expiration_year", card.expYear)
                if (card.postalCode.isNotBlank()) put("postal_code", card.postalCode)
            }
        }.toString()

        val resp = runCatching {
            NetworkClient.execute(
                url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
                method = "POST",
                headers = authProtoJsonHeaders(
                    token,
                    "pb.api.endpoints.charge_accounts.CreateChargeAccountMultipleProviderRequest",
                ),
                body = body,
            )
        }.getOrElse { return AddCardResult.Failed("Create card request failed: ${it.message}") }
        Log.d(TAG, "POST /charge-accounts-multi-provider (create) -> ${resp.status}")
        if (!resp.isSuccess) {
            Log.w(TAG, "add-card failed ${resp.status}: req=$body resp=${resp.text}")
            return AddCardResult.Failed(httpError(resp))
        }
        // Response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { parseChargeAccounts(resp) }.getOrDefault(emptyList())
        return AddCardResult.Success(accounts.ifEmpty { null })
    }

    /**
     * Resolves which processor to tokenize the card with by calling PostTokenizationStrategies.
     * The response (`o1z`) lists per-provider strategies (`sbe0`); the card ones carry the
     * processor's client key under `api_key`. Stripe is preferred, then Braintree; providers we
     * don't implement (Adyen etc.) yield null → caller reports Unsupported. Logs the resolved
     * provider and a redacted key so the residual "where does the key live" unknown is closed.
     */
    private suspend fun fetchTokenizerConfig(token: String, card: NewCard): TokenizerConfig? {
        // `n1z`: purpose(1, enum) = PAYIN, card_request(11) = { bin(1), last_four(2) }.
        val body = buildJsonObject {
            put("purpose", "PAYIN")
            putJsonObject("card_request") {
                put("bin", card.bin)
                put("last_four", card.last4)
            }
        }.toString()
        val resp = runCatching {
            NetworkClient.execute(
                url = "${LyftAuth.BASE}/v1/tokenization_strategies",
                method = "POST",
                headers = authJsonHeaders(token),
                body = body,
            )
        }.getOrElse {
            Log.w(TAG, "tokenization_strategies request failed", it)
            return null
        }
        Log.d(TAG, "POST /v1/tokenization_strategies -> ${resp.status} (${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            Log.w(TAG, "tokenization_strategies failed: ${resp.text.take(200)}")
            return null
        }
        val config = parseTokenizerConfig(resp)
        if (config == null) {
            Log.w(TAG, "No supported tokenizer strategy in response")
        } else {
            Log.d(TAG, "Tokenizer resolved: provider=${config.provider} key=${redactKey(config.key)}")
        }
        return config
    }

    /**
     * `PostTokenizationStrategiesResponse` (`o1z`): strategies(1, repeated `sbe0`). Each `sbe0`
     * has stripe_card_data(10)→api_key(1) and braintree_card_data(11)→api_key(1). Tries JSON then
     * protobuf, matching the codec negotiation the rest of this class uses.
     */
    private fun parseTokenizerConfig(resp: RawResponse): TokenizerConfig? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        return if (isProto) {
            parseTokenizerConfigProto(resp.bytes) ?: parseTokenizerConfigJson(resp.text)
        } else {
            parseTokenizerConfigJson(resp.text) ?: parseTokenizerConfigProto(resp.bytes)
        }
    }

    /**
     * `PostTokenizationStrategiesResponse` (`o1z`): strategies(1, repeated `sbe0`). The real client
     * prioritises the modern SCA strategy `stripe_card_setup_intent_data` (tag 22, `uhc0`) far above
     * legacy `stripe_card_data` (tag 10) — a 2026 build returns the SetupIntent one for cards, which
     * is why only recognising the legacy strategies made add-card fail (no config → Unsupported).
     * Order here mirrors that: SetupIntent → legacy Stripe token → Braintree.
     */
    private fun parseTokenizerConfigJson(raw: String): TokenizerConfig? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val strategies = root["strategies"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?: return null
        strategies.firstNotNullOfOrNull { s ->
            (s["stripe_card_setup_intent_data"] as? JsonObject)?.let { d ->
                d.str("api_key")?.let { apiKey ->
                    TokenizerConfig(
                        provider = "stripe_setup_intent",
                        key = apiKey,
                        clientSecret = d.str("client_secret"),
                        setupIntentId = d.str("setup_intent_id"),
                        stripeApiVersion = d.str("stripe_api_version"),
                    )
                }
            }
        }?.let { return it }
        strategies.firstNotNullOfOrNull { s ->
            (s["stripe_card_data"] as? JsonObject)?.str("api_key")
                ?.let { TokenizerConfig("stripe", it) }
        }?.let { return it }
        return strategies.firstNotNullOfOrNull { s ->
            (s["braintree_card_data"] as? JsonObject)?.str("api_key")
                ?.let { TokenizerConfig("braintree", it) }
        }
    }

    private fun parseTokenizerConfigProto(bytes: ByteArray): TokenizerConfig? {
        val strategies = runCatching { ProtoMessage(bytes, 0, bytes.size).messages(1) }
            .getOrDefault(emptyList())
        // stripe_card_setup_intent_data (tag 22): api_key(1), client_secret(2), setup_intent_id(3),
        // stripe_api_version(4).
        strategies.firstNotNullOfOrNull { s ->
            s.message(22)?.let { d ->
                d.string(1)?.let { apiKey ->
                    TokenizerConfig(
                        provider = "stripe_setup_intent",
                        key = apiKey,
                        clientSecret = d.string(2),
                        setupIntentId = d.string(3),
                        stripeApiVersion = d.string(4),
                    )
                }
            }
        }?.let { return it }
        strategies.firstNotNullOfOrNull { it.message(10)?.string(1) }
            ?.let { return TokenizerConfig("stripe", it) }
        return strategies.firstNotNullOfOrNull { it.message(11)?.string(1) }
            ?.let { TokenizerConfig("braintree", it) }
    }

    private suspend fun tokenizeCard(config: TokenizerConfig, card: NewCard): TokenizeResult =
        when (config.provider) {
            "stripe_setup_intent" -> tokenizeStripeSetupIntent(config, card)
            "stripe" -> tokenizeStripe(config.key, card)
            "braintree" -> tokenizeBraintree(config.key, card)
            else -> TokenizeResult.Err("Unsupported card processor: ${config.provider}")
        }

    /**
     * Modern SCA-compliant Stripe path (`sbe0` tag 22 `StripeCardSetupIntentDataDTO`, tokenized in
     * `ol6.c` case 2 via `qic0`). The server pre-creates a SetupIntent; we confirm it with the raw
     * card inline:
     *   POST https://api.stripe.com/v1/setup_intents/{setup_intent_id}/confirm
     *   Authorization: Bearer <api_key>;  Stripe-Version: <stripe_api_version>; form-encoded
     *   client_secret + payment_method_data[type|card[...]|billing_details[address][postal_code]]
     * The confirm response's `payment_method` (`pm_…`, `mna.payment_method`) is the token, sent on
     * as `mt00.token` with version `STRIPE_SETUP_INTENT` (`it00`).
     */
    private suspend fun tokenizeStripeSetupIntent(
        config: TokenizerConfig,
        card: NewCard,
    ): TokenizeResult {
        val setupIntentId = config.setupIntentId
            ?: return TokenizeResult.Err("Stripe SetupIntent strategy missing setup_intent_id")
        val clientSecret = config.clientSecret
            ?: return TokenizeResult.Err("Stripe SetupIntent strategy missing client_secret")
        val form = buildString {
            append("client_secret=").append(Uri.encode(clientSecret))
            append("&payment_method_data[type]=card")
            append("&payment_method_data[card][number]=").append(Uri.encode(card.number))
            append("&payment_method_data[card][exp_month]=").append(card.expMonth)
            append("&payment_method_data[card][exp_year]=").append(card.expYear)
            append("&payment_method_data[card][cvc]=").append(Uri.encode(card.cvc))
            if (card.postalCode.isNotBlank()) {
                append("&payment_method_data[billing_details][address][postal_code]=")
                append(Uri.encode(card.postalCode))
            }
        }
        val headers = buildMap {
            put("Authorization", "Bearer ${config.key}")
            put("Content-Type", "application/x-www-form-urlencoded")
            put("Accept", "application/json")
            config.stripeApiVersion?.takeIf { it.isNotBlank() }?.let { put("Stripe-Version", it) }
        }
        val resp = runCatching {
            NetworkClient.execute(
                url = "https://api.stripe.com/v1/setup_intents/${Uri.encode(setupIntentId)}/confirm",
                method = "POST",
                headers = headers,
                body = form,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Stripe SetupIntent request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        if (!resp.isSuccess) {
            val msg = root?.get("error")?.jsonObject?.str("message")
                ?: "Stripe HTTP ${resp.status}: ${resp.text.take(200)}"
            return TokenizeResult.Err(msg)
        }
        val pm = root?.str("payment_method")
            ?: return TokenizeResult.Err("Stripe returned no payment_method")
        Log.d(TAG, "Stripe SetupIntent confirmed ••${card.last4} -> ${pm.take(8)}…")
        // mt00.provider for a Stripe card is jju.h(qbe0.STRIPE.name()) = "stripe".
        return TokenizeResult.Ok("stripe", token = pm, nonce = null, version = "STRIPE_SETUP_INTENT")
    }

    /**
     * Stripe card token: `POST https://api.stripe.com/v1/tokens`, form-encoded, publishable key
     * as Bearer (mirrors `ol6` case STRIPE_CARD_DATA). Returns a `tok_…` used as `mt00.token`.
     */
    private suspend fun tokenizeStripe(publishableKey: String, card: NewCard): TokenizeResult {
        val form = buildString {
            append("card[number]=").append(Uri.encode(card.number))
            append("&card[exp_month]=").append(card.expMonth)
            append("&card[exp_year]=").append(card.expYear)
            append("&card[cvc]=").append(Uri.encode(card.cvc))
        }
        // External host: force system CAs via an explicit factory. (NetworkClient's own
        // useSystemTrust flag falls back to the Lyft-only bundle, which rejects Stripe.) Guard
        // the call so a transport/TLS failure surfaces as an error rather than crashing.
        val resp = runCatching {
            NetworkClient.execute(
                url = "https://api.stripe.com/v1/tokens",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $publishableKey",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                    "Stripe-Version" to "2015-10-12",
                ),
                body = form,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Stripe request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        if (!resp.isSuccess) {
            val msg = root?.get("error")?.jsonObject?.str("message")
                ?: "Stripe HTTP ${resp.status}: ${resp.text.take(200)}"
            return TokenizeResult.Err(msg)
        }
        val tok = root?.str("id") ?: return TokenizeResult.Err("Stripe returned no token")
        Log.d(TAG, "Stripe tokenized ••${card.last4} -> ${tok.take(8)}…")
        return TokenizeResult.Ok("stripe", token = tok, nonce = null, version = "STRIPE_TOKEN")
    }

    /**
     * Braintree card nonce. The Braintree `card` SDK module isn't bundled in the Lyft APK, so the
     * flow is hand-built: parse the client token (`av7` → `authorizationFingerprint` + GraphQL
     * url), then run the `tokenizeCreditCard` mutation against the GraphQL endpoint. Returns a
     * nonce used as `mt00.nonce`.
     */
    private suspend fun tokenizeBraintree(clientToken: String, card: NewCard): TokenizeResult {
        val parsed = parseBraintreeClientToken(clientToken)
            ?: return TokenizeResult.Err("Couldn't parse Braintree client token")
        val (graphQlUrl, fingerprint) = parsed
        val query = "mutation TokenizeCard(\$input: TokenizeCreditCardInput!) { " +
            "tokenizeCreditCard(input: \$input) { token } }"
        val reqBody = buildJsonObject {
            put("query", query)
            putJsonObject("variables") {
                putJsonObject("input") {
                    putJsonObject("creditCard") {
                        put("number", card.number)
                        put("expirationMonth", card.expMonth.toString())
                        put("expirationYear", card.expYear.toString())
                        put("cvv", card.cvc)
                    }
                }
            }
        }.toString()
        val resp = runCatching {
            NetworkClient.execute(
                url = graphQlUrl,
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $fingerprint",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Braintree-Version" to "2024-08-23",
                ),
                body = reqBody,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Braintree request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        // Braintree GraphQL returns 200 with a non-empty `errors` array on failure.
        val errors = root?.get("errors")?.jsonArray
        if (!resp.isSuccess || !errors.isNullOrEmpty()) {
            val msg = errors?.firstOrNull()?.jsonObject?.str("message")
                ?: "Braintree HTTP ${resp.status}: ${resp.text.take(200)}"
            return TokenizeResult.Err(msg)
        }
        val nonce = root?.get("data")?.jsonObject
            ?.get("tokenizeCreditCard")?.jsonObject
            ?.str("token")
            ?: return TokenizeResult.Err("Braintree returned no nonce")
        Log.d(TAG, "Braintree tokenized ••${card.last4} -> nonce ${nonce.take(6)}…")
        return TokenizeResult.Ok("braintree", token = null, nonce = nonce)
    }

    /**
     * A Braintree client token is either raw JSON or base64-encoded JSON (`av7` base64-decodes
     * when it matches). We need `authorizationFingerprint` and the GraphQL url (`graphQL.url`,
     * defaulting to the public endpoint).
     */
    private fun parseBraintreeClientToken(clientToken: String): Pair<String, String>? {
        val raw = if (clientToken.trimStart().startsWith("{")) {
            clientToken
        } else {
            runCatching { String(Base64.decode(clientToken, Base64.DEFAULT), Charsets.UTF_8) }
                .getOrNull() ?: return null
        }
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        val fingerprint = obj.str("authorizationFingerprint") ?: return null
        val url = obj["graphQL"]?.jsonObject?.str("url")
            ?: "https://payments.braintree-api.com/graphql"
        return url to fingerprint
    }

    private fun redactKey(key: String): String =
        if (key.length <= 8) "***" else "${key.take(8)}…(len ${key.length})"

    override suspend fun createRide(
        quote: RideQuote,
        pickup: Place,
        dropoff: Place,
        account: ChargeAccount?,
        purchaseSessionId: String?,
        dryRun: Boolean,
    ): BookingResult {
        val token = accessToken() ?: return BookingResult.Failed("Not signed in to Lyft")
        val offerId = quote.offerId
            ?: return BookingResult.Failed("This fare has no offer id — re-quote and try again")
        val riderId = tokens.userId()

        // CreateTripRequest (defpackage/ura): rider_id(1), offer_id(2), origin(3), destination(4),
        // ride_segment_creation_args(5). Sent as proto-JSON, as the offers call is.
        val request = buildJsonObject {
            riderId?.let { put("rider_id", it) } // uint64 → string in proto-JSON
            put("offer_id", offerId)
            put("origin", locationV2(pickup))
            put("destination", locationV2(dropoff))
            putJsonObject("ride_segment_creation_args") {
                quote.offerToken?.let { put("offer_token", it) }
                quote.rideType?.let { put("ride_type", it) }
                quote.costToken?.let { put("cost_token", it) }
                put("party_size", 1)
                // pickup_mode defaults to "standard" in the official app (RideSegmentCreationArgs
                // tag 9); the resolver expects it set.
                put("pickup_mode", "standard")
                // ChargeAccountDTO exposes only an id; passed as charge_token (tag 12). Exact
                // charge_token vs shared_charge_account_id split is unverified — dry-run surfaces
                // the built body so it can be checked against a real capture.
                account?.let { put("charge_token", it.chargeToken ?: it.id) }
            }
            // NB: purchase_session_id is NOT a field on CreateTripRequest (it belongs to the
            // offers request); server-side dedupe is handled by the request throttler instead.
        }
        val requestJson = request.toString()

        // Master guard: never send while BOOKING_LIVE is false, or when the caller asked for a
        // dry run. The full request is returned so the UI/logcat can verify it — no charge.
        if (!BOOKING_LIVE || dryRun) {
            Log.i(TAG, "DRY-RUN /v1/core_trips/create (not sent): $requestJson")
            return BookingResult.DryRun(requestJson, account)
        }

        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/core_trips/create",
            method = "POST",
            headers = authJsonHeaders(token),
            body = requestJson,
        )
        Log.d(TAG, "POST /v1/core_trips/create -> ${resp.status}")
        if (!resp.isSuccess) return BookingResult.Failed(httpError(resp))
        // CreateTripResponse (vra): trip_details(1 = TripDetails) → trip_id(1). No status here;
        // parse the id (JSON or protobuf) for tracking and show nothing else.
        return BookingResult.Created(rideId = parseCreatedRideId(resp), status = null, raw = "")
    }

    /** Extracts `trip_details.trip_id` from a CreateTripResponse (JSON or binary protobuf). */
    private fun parseCreatedRideId(resp: RawResponse): String? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto(): String? = runCatching {
            ProtoMessage(resp.bytes, 0, resp.bytes.size).message(1)?.varint(1)?.toString()
        }.getOrNull()
        fun asJson(): String? = runCatching {
            (json.parseToJsonElement(resp.text) as? JsonObject)
                ?.get("trip_details")?.jsonObject
                ?.let { it.str("trip_id") ?: it.str("id") }
        }.getOrNull()
        return if (isProto) (proto() ?: asJson()) else (asJson() ?: proto())
    }

    override suspend fun activeRide(): RideStatusResult {
        val token = accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/activeride",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/activeride -> ${resp.status} (${resp.bytes.size} bytes)")
        if (resp.status == 404) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(httpError(resp))
        val ride = parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    override suspend fun driverLocation(rideId: String): DriverLocation? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/driver-location",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/rides/{id}/driver-location -> ${resp.status}")
        if (!resp.isSuccess) return null
        return parseDriverLocation(resp)
    }

    override suspend fun cancelRide(rideId: String): CancelResult {
        val token = accessToken() ?: return CancelResult.Failed("Not signed in to Lyft")
        // A cancel genuinely cancels a live ride (and may incur a fee), so it is sent live
        // regardless of BOOKING_LIVE — that flag only gates ride *creation*.
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/cancel",
            method = "POST",
            headers = authJsonHeaders(token),
            body = "{}",
        )
        Log.d(TAG, "POST /v1/rides/{id}/cancel -> ${resp.status}")
        // Surface the server response verbatim either way — a cancel can carry a fee.
        return if (resp.isSuccess) {
            CancelResult.Done(resp.text.take(2000).ifBlank { "Ride cancelled" })
        } else {
            CancelResult.Failed(httpError(resp))
        }
    }

    /**
     * Signs out of Lyft: best-effort revokes the refresh and access tokens server-side, then
     * clears the local session regardless of the server's response.
     */
    suspend fun signOut() {
        tokens.refreshToken()?.let { LyftAuth.revoke(it) }
        tokens.accessToken()?.let { LyftAuth.revoke(it) }
        tokens.clear()
    }

    /** Reads a specific ride by id (`GET /v1/rides/{id}`), parsed like the active ride. */
    suspend fun rideById(rideId: String): RideStatusResult {
        val token = accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/rides/{id} -> ${resp.status}")
        if (resp.status == 404) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(httpError(resp))
        val ride = parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    // ----------------------------------------------------------------------------------------
    // Best-effort car ride endpoints. Their request/response shapes are unverified in the APK
    // teardown (api-notes §3/§4), so these issue the documented HTTP call and return the raw
    // response body (truncated) for the caller to interpret; null on any non-2xx or when signed
    // out. No dedicated UI consumes them yet.
    // ----------------------------------------------------------------------------------------

    /** `GET /v1/active-offer` — the current unaccepted offer, if any. */
    suspend fun activeOffer(): String? = getRaw("/v1/active-offer")

    /** `GET /v1/rides/{id}/pickupgeofence` — the pickup geofence for a ride. */
    suspend fun pickupGeofence(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/pickupgeofence")

    /** `GET /v1/rides/{id}/paymentdetails` — payment breakdown for a ride. */
    suspend fun paymentDetails(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/paymentdetails")

    /** `POST /v1/updated-cost-estimate/{id}` — refreshed cost for an in-flight ride. */
    suspend fun updatedCostEstimate(rideId: String): String? =
        postRaw("/v1/updated-cost-estimate/${Uri.encode(rideId)}", "{}")

    /** `POST /v1/scheduledridetimeestimates` — pickup-time estimates for a scheduled ride. */
    suspend fun scheduledRideTimeEstimates(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/scheduledridetimeestimates", offersBody(pickup, dropoff, null, null))

    /** `POST /v1/offerings/overview` — offer overview for a route. */
    suspend fun offeringsOverview(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/offerings/overview", offersBody(pickup, dropoff, null, null))

    /** `POST /v1/rides/{id}/pickup` — move the pickup of an existing ride. Mutates a live ride. */
    suspend fun updatePickup(rideId: String, pickup: Place): String? =
        postRaw("/v1/rides/${Uri.encode(rideId)}/pickup", locationV2(pickup).toString())

    /** `POST /v1/rides/redispatch` — request a new driver for a ride. Mutates a live ride. */
    suspend fun redispatch(rideId: String): String? =
        postRaw("/v1/rides/redispatch", buildJsonObject { put("ride_id", rideId) }.toString())

    private suspend fun getRaw(path: String): String? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(4000) else null
    }

    private suspend fun postRaw(path: String, body: String): String? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "POST",
            headers = authJsonHeaders(token),
            body = body,
        )
        Log.d(TAG, "POST $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(4000) else null
    }

    // ----------------------------------------------------------------------------------------
    // Active-ride / driver-location parsing (PassengerRide, ReadDriverLocationResponse).
    // Field tags mirror the DTOs in api-notes §4; both JSON and binary protobuf are accepted,
    // matching the codec negotiation the rest of this class uses.
    //
    //   PassengerRide: ride_id(1), status(2), driver(6), vehicle(8), stops(9 repeated),
    //                  location(11 = live DriverLocation)
    //   DriverLocation: lat(1 double), lng(2 double), bearing(3 double)
    //   Driver:  first_name(5), last_name(6), image_url(7), phone_number(8), rating(9)
    //   RideVehicle: make(1), model(2), license_plate(3), image_url(4), color(6)
    //   RideStop: location(2 PlaceDTO), kind(3), completed(4), eta_seconds(6), location_v2(7)
    //   PlaceDTO: lat(1 double), lng(2 double), address(3), place_name(5)
    // ----------------------------------------------------------------------------------------

    private fun parseActiveRide(resp: RawResponse): ActiveRide? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto() = runCatching { toActiveRideProto(resp.bytes) }.getOrNull()
        fun asJson() = runCatching { toActiveRideJson(resp.text) }.getOrNull()
        val primary = if (isProto) proto() else asJson()
        if (primary != null && primary.hasContent) return primary
        return (if (isProto) asJson() else proto())?.takeIf { it.hasContent } ?: primary
    }

    private fun toActiveRideJson(raw: String): ActiveRide? {
        val ride = firstRideObject(raw) ?: return null
        val statusRaw = ride.str("status") ?: ride.str("ride_status")
        val driver = (ride["driver"] as? JsonObject)?.let { d ->
            DriverInfo(
                firstName = d.str("first_name") ?: d.str("firstName"),
                lastName = d.str("last_name") ?: d.str("lastName"),
                imageUrl = d.str("image_url") ?: d.str("imageUrl"),
                phoneNumber = d.str("phone_number") ?: d.str("phoneNumber"),
                rating = d.dbl("rating"),
            )
        }
        val vehicle = (ride["vehicle"] as? JsonObject)?.let { v ->
            VehicleInfo(
                make = v.str("make"),
                model = v.str("model"),
                color = v.str("color"),
                licensePlate = v.str("license_plate") ?: v.str("licensePlate"),
                imageUrl = v.str("image_url") ?: v.str("imageUrl"),
            )
        }
        val driverLocation = (ride["location"] as? JsonObject)?.let(::toDriverLocationJson)
        val stops = ride["stops"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.let(::toStopJson) }
            ?: emptyList()
        return ActiveRide(
            rideId = ride.str("ride_id") ?: ride.str("id"),
            status = RideStatus.fromWire(statusRaw),
            statusRaw = statusRaw,
            driver = driver,
            vehicle = vehicle,
            driverLocation = driverLocation,
            stops = stops,
            raw = raw.take(2000),
        )
    }

    private fun toActiveRideProto(bytes: ByteArray): ActiveRide {
        val m = ProtoMessage(bytes, 0, bytes.size)
        val statusRaw = m.string(2)
        val driver = m.message(6)?.let { d ->
            DriverInfo(
                firstName = d.string(5),
                lastName = d.string(6),
                imageUrl = d.string(7),
                phoneNumber = d.string(8),
                rating = d.double(9) ?: d.wrappedDouble(9),
            )
        }
        val vehicle = m.message(8)?.let { v ->
            VehicleInfo(
                make = v.string(1),
                model = v.string(2),
                color = v.string(6),
                licensePlate = v.string(3),
                imageUrl = v.string(4),
            )
        }
        val driverLocation = m.message(11)?.let(::toDriverLocationProto)
        val stops = m.messages(9).map(::toStopProto)
        return ActiveRide(
            rideId = m.string(1),
            status = RideStatus.fromWire(statusRaw),
            statusRaw = statusRaw,
            driver = driver,
            vehicle = vehicle,
            driverLocation = driverLocation,
            stops = stops,
            raw = "<protobuf ${bytes.size} bytes>",
        )
    }

    private fun parseDriverLocation(resp: RawResponse): DriverLocation? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto(): DriverLocation? {
            val root = ProtoMessage(resp.bytes, 0, resp.bytes.size)
            // ReadDriverLocationResponse.location = 1; tolerate a bare DriverLocation too.
            return root.message(1)?.let(::toDriverLocationProto) ?: toDriverLocationProto(root)
        }
        fun asJson(): DriverLocation? {
            val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
                ?: return null
            val loc = (root["location"] as? JsonObject) ?: root
            return toDriverLocationJson(loc)
        }
        return if (isProto) (proto() ?: asJson()) else (asJson() ?: proto())
    }

    private fun toDriverLocationJson(o: JsonObject): DriverLocation? {
        val lat = o.dbl("lat") ?: o.dbl("latitude") ?: return null
        val lng = o.dbl("lng") ?: o.dbl("longitude") ?: return null
        return DriverLocation(lat, lng, o.dbl("bearing"))
    }

    private fun toDriverLocationProto(m: ProtoMessage): DriverLocation? {
        val lat = m.double(1) ?: return null
        val lng = m.double(2) ?: return null
        return DriverLocation(lat, lng, m.double(3))
    }

    private fun toStopJson(o: JsonObject): RideStopInfo {
        val place = (o["location"] as? JsonObject) ?: (o["location_v2"] as? JsonObject)
        val latLng = place?.let { p ->
            val lat = p.dbl("lat") ?: p.dbl("latitude")
            val lng = p.dbl("lng") ?: p.dbl("longitude")
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return RideStopInfo(
            location = latLng,
            name = place?.str("place_name") ?: place?.str("placeName") ?: place?.str("address"),
            kind = o.str("kind"),
            etaSeconds = o["eta_seconds"]?.jsonPrimitive?.intOrNull
                ?: o["etaSeconds"]?.jsonPrimitive?.intOrNull,
            completed = o["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun toStopProto(m: ProtoMessage): RideStopInfo {
        val place = m.message(2) ?: m.message(7)
        val latLng = place?.let {
            val lat = it.double(1)
            val lng = it.double(2)
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return RideStopInfo(
            location = latLng,
            name = place?.string(5) ?: place?.string(3),
            kind = m.string(3),
            etaSeconds = m.varint(6)?.toInt(),
            completed = m.varint(4)?.let { it != 0L } ?: false,
        )
    }

    private fun authHeaders(token: String): Map<String, String> =
        LyftAuth.commonHeaders() + mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json, application/x-protobuf",
        )

    private fun authJsonHeaders(token: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json")

    /**
     * Lyft's server deserializes request bodies with a reflection-based proto-JSON codec that is
     * told which proto message to map the JSON onto via a `messageType` Content-Type parameter.
     * The APK builds it in `defpackage/tvu.d`:
     *   `application/json;messageType=<proto full name>`   (name from `fkh0.a()`, the DTO's
     *   super-constructor arg, e.g. `pb.api.endpoints.charge_accounts.Update…Request`).
     * Paths that map to a single message (e.g. `/v2/offerings`) tolerate a bare `application/json`,
     * but `/charge-accounts-multi-provider` is shared by Create (POST) and Update (PUT), so without
     * `messageType` the server can't resolve the body and returns 422. We must send it.
     */
    private fun authProtoJsonHeaders(token: String, messageType: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json;messageType=$messageType")

    private fun httpError(resp: RawResponse): String =
        "Lyft returned HTTP ${resp.status}: ${resp.text.take(300)}"

    /**
     * A `LocationV2DTO` for `origin`/`destination` on `/v1/core_trips/create`, recovered from the
     * APK (`kvp`, `LocationV2MapperKt.toLocationV2ForApiRequest`). Coordinates live three levels
     * deep as **integer microdegrees** (degrees × 1e6, sint32) at
     * `portable_location_with_features.portable_location.location.{lat,lng}_microdegrees` — a flat
     * `{latitude, longitude}` is not resolvable by the server (it answers 422 "not available for
     * your specified locations"). The display name/address go under
     * `location_metadata.static_metadata.spot`.
     */
    private fun locationV2(place: Place): JsonObject {
        val latMicro = (place.location.latitude * 1_000_000).roundToInt()
        val lngMicro = (place.location.longitude * 1_000_000).roundToInt()
        val basicLocation = buildJsonObject {
            put("lat_microdegrees", latMicro)
            put("lng_microdegrees", lngMicro)
        }
        return buildJsonObject {
            putJsonObject("portable_location_with_features") {
                putJsonObject("portable_location") {
                    put("location", basicLocation)
                }
            }
            putJsonObject("location_metadata") {
                putJsonObject("static_metadata") {
                    putJsonObject("spot") {
                        put("name", place.name)
                        place.address?.let {
                            put("display_address", it)
                            put("routable_address", it)
                        }
                    }
                }
            }
            put("source", "user")
        }
    }

    // ----------------------------------------------------------------------------------------
    // Charge-account parsing (ChargeAccountsResponse `u77` → ChargeAccountDTO `c67`)
    //   c67: id=1 (StringValue), kind=2, default=3 (BoolValue), label=5, lastFour=10
    // ----------------------------------------------------------------------------------------

    private fun parseChargeAccounts(resp: RawResponse): List<ChargeAccount> {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var accounts = runCatching {
            if (isProto) parseChargeAccountsProto(resp.bytes) else parseChargeAccountsJson(resp.text)
        }.getOrDefault(emptyList())
        if (accounts.isEmpty()) {
            accounts = runCatching {
                if (isProto) parseChargeAccountsJson(resp.text) else parseChargeAccountsProto(resp.bytes)
            }.getOrDefault(emptyList())
        }
        return accounts
    }

    private fun parseChargeAccountsJson(raw: String): List<ChargeAccount> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val arr = root["chargeAccounts"]?.jsonArray
            ?: root["charge_accounts"]?.jsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::toChargeAccountJson) }
    }

    private fun toChargeAccountJson(o: JsonObject): ChargeAccount? {
        val id = o.str("id") ?: return null
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(o.str("label"), o.str("kind"), o.str("lastFour") ?: o.str("last_four")),
            isDefault = o["default"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun parseChargeAccountsProto(bytes: ByteArray): List<ChargeAccount> {
        val root = ProtoMessage(bytes, 0, bytes.size)
        return root.messages(1).mapNotNull { toChargeAccountProto(it) }
    }

    private fun toChargeAccountProto(m: ProtoMessage): ChargeAccount? {
        val id = m.wrappedString(1) ?: return null
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(m.wrappedString(5), m.wrappedString(2), m.wrappedString(10)),
            isDefault = m.wrappedBool(3) ?: false,
        )
    }

    private fun accountLabel(label: String?, kind: String?, lastFour: String?): String = when {
        !label.isNullOrBlank() -> label
        !kind.isNullOrBlank() && !lastFour.isNullOrBlank() -> "$kind ••$lastFour"
        !lastFour.isNullOrBlank() -> "•• $lastFour"
        !kind.isNullOrBlank() -> kind
        else -> "Card"
    }

    /**
     * Best-effort pick of the ride/trip object out of a create or active-ride response. Exact
     * shapes are unverified (api-notes §4/§6); we look for the common wrappers and fall back to
     * the root so id/status are surfaced when present.
     */
    private fun firstRideObject(raw: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        return root["ride"]?.jsonObject
            ?: root["trip"]?.jsonObject
            ?: root["active_ride"]?.jsonObject
            ?: root
    }

    private suspend fun accessToken(): String? {
        if (!tokens.isExpired()) return tokens.accessToken()
        val refresh = tokens.refreshToken() ?: return null
        val fresh = LyftAuth.refresh(refresh) ?: return null
        tokens.save(fresh)
        return fresh.accessToken
    }

    /** A single `E6LatLngDTO`: lat/lng in integer micro-degrees (degrees × 1e6). */
    private fun e6(place: Place): String {
        val latE6 = (place.location.latitude * 1_000_000).roundToLong()
        val lngE6 = (place.location.longitude * 1_000_000).roundToLong()
        return """{"latitude_e6":$latE6,"longitude_e6":$lngE6}"""
    }

    // ----------------------------------------------------------------------------------------
    // JSON response
    // ----------------------------------------------------------------------------------------

    /**
     * Parses proto-JSON `ReadOffersV2Response`: `offers.offers_list[]`, each an `OfferDTO` with a
     * `cost_estimate` (price), `ride_type_details` (name + seats) and `ride_travel_details`
     * (pickup ETA). int64 proto-JSON fields may arrive as strings, which the accessors handle.
     * The offers wrapper also carries `purchase_session_id`/`offers_response_id`, reused when
     * booking.
     */
    private fun parseOfferingsJson(raw: String): ParsedOffers {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return ParsedOffers.EMPTY
        val offers = root["offers"]?.jsonObject ?: return ParsedOffers.EMPTY
        val quotes = offers["offers_list"]?.jsonArray
            ?.mapNotNull { element -> (element as? JsonObject)?.let(::toQuoteJson) }
            ?: emptyList()
        return ParsedOffers(
            quotes = quotes,
            purchaseSessionId = offers.str("purchase_session_id"),
            offersResponseId = offers.str("offers_response_id"),
        )
    }

    private fun toQuoteJson(offer: JsonObject): RideQuote? {
        val cost = offer["cost_estimate"]?.jsonObject
        val rideType = offer["ride_type_details"]?.jsonObject
        val display = rideType?.get("display_properties")?.jsonObject

        val name = display?.str("name")
            ?: cost?.str("ride_type")
            ?: offer.str("offer_product_id")
            ?: return null

        val min = cost?.long("estimated_cost_cents_min")
        val max = cost?.long("estimated_cost_cents_max")
        val upfront = cost?.long("upfront_cost_cents")
        // CostEstimate.applicable_coupons[0] carries the discount the rider actually receives.
        val coupon = cost?.get("applicable_coupons")?.jsonArray?.firstOrNull() as? JsonObject
        val fare = farePrice(
            min, max, upfront,
            discountMin = coupon?.long("discount_amount_min"),
            discountMax = coupon?.long("discount_amount_max"),
        ) ?: return null

        val pickupEtaMs = offer["ride_travel_details"]?.jsonObject
            ?.get("pickup_estimate")?.jsonObject
            ?.get("duration_range")?.jsonObject
            ?.long("duration_ms")

        return RideQuote(
            provider = Provider.LYFT,
            productId = offer.str("offer_product_id") ?: name,
            displayName = name,
            fareLowMinor = fare.low,
            fareHighMinor = fare.high,
            originalFareLowMinor = fare.originalLow,
            originalFareHighMinor = fare.originalHigh,
            currency = cost?.str("currency") ?: "USD",
            pickupEtaMinutes = pickupEtaMs?.let { (it / 60_000).toInt() },
            tripDurationMinutes = cost?.long("estimated_duration_seconds")?.let { (it / 60).toInt() },
            surgeMultiplier = cost?.get("primetime_multiplier")?.jsonPrimitive?.doubleOrNull,
            capacity = rideType?.get("seats")?.jsonPrimitive?.intOrNull,
            offerId = offer.str("id"),
            // StringValue wrappers serialize to the bare string in proto3-JSON.
            offerToken = offer.str("offer_token"),
            costToken = cost?.str("cost_token"),
            rideType = cost?.str("ride_type"),
            // `cost_token_expiry_time` is an epoch **seconds** value (measured live: ~120s
            // lifetime, shared across all fares); convert to ms so callers can compare to now.
            costTokenExpiryMs = cost?.long("cost_token_expiry_time")?.let { it * 1000 },
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.dbl(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

    // ----------------------------------------------------------------------------------------
    // Protobuf response (binary). Field tags mirror the DTOs decompiled from the APK.
    // ----------------------------------------------------------------------------------------

    /**
     * Parses binary `ReadOffersV2Response`. Message nesting (field numbers):
     *  - ReadOffersV2Response.offers = 1  → OffersV2DTO
     *  - OffersV2DTO.offers_list      = 3  (repeated) → OfferDTO
     *  - OfferDTO: offer_product_id=2, cost_estimate=4, ride_type_details=5, ride_travel_details=9
     *  - CostEstimate: cents_max=5, cents_min=6, upfront=7, currency=8, primetime_multiplier=12,
     *    estimated_duration_seconds=22  (the *_cents / currency / duration are wrapper messages)
     *  - RideMode: seats=7, display_properties=10 → name=3
     *  - RideTravelDetails.pickup_estimate=1 → duration_range=2 → duration_ms=1 (wrapper)
     */
    private fun parseOfferingsProto(bytes: ByteArray): ParsedOffers {
        val root = ProtoMessage(bytes, 0, bytes.size)
        val offers = root.message(1) ?: return ParsedOffers.EMPTY
        return ParsedOffers(
            quotes = offers.messages(3).mapNotNull { toQuoteProto(it) },
            purchaseSessionId = offers.string(1),
            offersResponseId = offers.string(2),
        )
    }

    private fun toQuoteProto(offer: ProtoMessage): RideQuote? {
        val cost = offer.message(4)
        val rideType = offer.message(5)
        val display = rideType?.message(10)

        val name = display?.string(3)
            ?: cost?.string(4) // ride_type
            ?: offer.string(2) // offer_product_id
            ?: return null

        val min = cost?.wrappedLong(6)
        val max = cost?.wrappedLong(5)
        val upfront = cost?.wrappedLong(7)
        // applicable_coupons(18, repeated ApplicableCoupon): discount_amount_min(10),
        // discount_amount_max(11) are plain int64. First coupon is the one the client applies.
        val coupon = cost?.messages(18)?.firstOrNull()
        val fare = farePrice(
            min, max, upfront,
            discountMin = coupon?.varint(10),
            discountMax = coupon?.varint(11),
        ) ?: return null

        val pickupEtaMs = offer.message(9)?.message(1)?.message(2)?.wrappedLong(1)

        return RideQuote(
            provider = Provider.LYFT,
            productId = offer.string(2) ?: name,
            displayName = name,
            fareLowMinor = fare.low,
            fareHighMinor = fare.high,
            originalFareLowMinor = fare.originalLow,
            originalFareHighMinor = fare.originalHigh,
            currency = cost?.wrappedString(8) ?: "USD",
            pickupEtaMinutes = pickupEtaMs?.let { (it / 60_000).toInt() },
            tripDurationMinutes = cost?.wrappedLong(22)?.let { (it / 60).toInt() },
            surgeMultiplier = cost?.double(12),
            capacity = rideType?.varint(7)?.toInt(),
            // OfferDTO.id=1, offer_token=3 (StringValue). CostEstimate.cost_token=3,
            // ride_type=4, cost_token_expiry_time=20 (best-effort; type unverified — dry-run
            // surfaces the built request for validation).
            offerId = offer.string(1),
            offerToken = offer.wrappedString(3),
            costToken = cost?.string(3),
            rideType = cost?.string(4),
            // Epoch seconds (see toQuoteJson) -> ms.
            costTokenExpiryMs = (cost?.wrappedLong(20) ?: cost?.varint(20))?.let { it * 1000 },
        )
    }

    private fun priceRange(min: Long?, max: Long?, upfront: Long?): Pair<Long, Long>? = when {
        min != null && max != null -> min to max
        upfront != null -> upfront to upfront
        min != null -> min to min
        max != null -> max to max
        else -> null
    }

    /**
     * The pre-discount base range plus the actual price after the first applicable coupon. Lyft
     * carries no post-promo scalar; the app computes it as estimate − coupon discount (clamped at
     * 0), keeping `upfront`/estimate as the struck-through original. Mirrors that (see api-notes §3).
     */
    private data class FarePrice(
        val low: Long,
        val high: Long,
        val originalLow: Long?,
        val originalHigh: Long?,
    )

    private fun farePrice(
        min: Long?,
        max: Long?,
        upfront: Long?,
        discountMin: Long?,
        discountMax: Long?,
    ): FarePrice? {
        val (baseLow, baseHigh) = priceRange(min, max, upfront) ?: return null
        val dLow = (discountMin ?: 0).coerceAtLeast(0)
        val dHigh = (discountMax ?: 0).coerceAtLeast(0)
        if (dLow == 0L && dHigh == 0L) return FarePrice(baseLow, baseHigh, null, null)
        return FarePrice(
            low = (baseLow - dLow).coerceAtLeast(0),
            high = (baseHigh - dHigh).coerceAtLeast(0),
            originalLow = baseLow,
            originalHigh = baseHigh,
        )
    }

    companion object {
        private const val TAG = "LyftProvider"

        /**
         * Master guard for live ride booking. While false, [createRide] builds and returns the
         * request but never sends it. Flip to true only after the dry-run request body has been
         * verified against a real capture.
         *
         * Note: this gates *ride creation* only. Payment-method management and [cancelRide] are
         * sent live regardless, per product decision.
         */
        const val BOOKING_LIVE = true
    }
}

/**
 * The pieces of a `ReadOffersV2Response` the app keeps: the per-product [quotes] plus the
 * offers-wrapper identifiers reused when booking.
 */
private data class ParsedOffers(
    val quotes: List<RideQuote>,
    val purchaseSessionId: String?,
    val offersResponseId: String?,
) {
    companion object {
        val EMPTY = ParsedOffers(emptyList(), null, null)
    }
}

/**
 * Which processor to tokenize a new card with, and that processor's client [key]. For the modern
 * Stripe SetupIntent strategy (`sbe0` tag 22, `uhc0`) the extra SetupIntent fields are carried too.
 */
private data class TokenizerConfig(
    val provider: String,
    val key: String,
    val clientSecret: String? = null,
    val setupIntentId: String? = null,
    val stripeApiVersion: String? = null,
)

/**
 * Outcome of tokenizing a card at the processor: a Stripe [token] or a Braintree [nonce], plus the
 * [version] (`it00`) the server needs to interpret a Stripe token (STRIPE_TOKEN vs
 * STRIPE_SETUP_INTENT); null for processors that don't send one (Braintree).
 */
private sealed interface TokenizeResult {
    data class Ok(
        val provider: String,
        val token: String?,
        val nonce: String?,
        val version: String? = null,
    ) : TokenizeResult

    data class Err(val message: String) : TokenizeResult
}

/**
 * Minimal read-only protobuf message view over a byte range. Supports exactly the wire types the
 * offers response uses: varint (0), 64-bit (1), length-delimited (2). Accessors return the last
 * value for a field number (protobuf "last one wins"), which is all this parser needs.
 */
private class ProtoMessage(private val buf: ByteArray, start: Int, private val end: Int) {
    // fieldNumber -> list of entries. For wire 0/1 the Long is the value; for wire 2 it is the
    // [start, end) byte range packed as start (in `ranges`).
    private val varints = HashMap<Int, MutableList<Long>>()
    private val fixed64 = HashMap<Int, MutableList<Long>>()
    private val ranges = HashMap<Int, MutableList<IntArray>>()

    init {
        var p = start
        loop@ while (p < end) {
            val (tag, afterTag) = readVarint(buf, p)
            p = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> {
                    val (v, np) = readVarint(buf, p); p = np
                    varints.getOrPut(field) { mutableListOf() }.add(v)
                }
                1 -> {
                    var v = 0L
                    for (i in 0 until 8) v = v or ((buf[p + i].toLong() and 0xff) shl (8 * i))
                    p += 8
                    fixed64.getOrPut(field) { mutableListOf() }.add(v)
                }
                2 -> {
                    val (len, np) = readVarint(buf, p); p = np
                    val s = p; val e = p + len.toInt()
                    ranges.getOrPut(field) { mutableListOf() }.add(intArrayOf(s, e))
                    p = e
                }
                5 -> p += 4
                else -> break@loop // unknown/invalid wire type: stop rather than misread
            }
        }
    }

    fun varint(field: Int): Long? = varints[field]?.lastOrNull()

    fun double(field: Int): Double? = fixed64[field]?.lastOrNull()?.let { Double.fromBits(it) }

    fun string(field: Int): String? = ranges[field]?.lastOrNull()?.let {
        String(buf, it[0], it[1] - it[0], Charsets.UTF_8)
    }

    fun message(field: Int): ProtoMessage? = ranges[field]?.lastOrNull()?.let {
        ProtoMessage(buf, it[0], it[1])
    }

    fun messages(field: Int): List<ProtoMessage> =
        ranges[field]?.map { ProtoMessage(buf, it[0], it[1]) } ?: emptyList()

    /** A google.protobuf.Int64Value/UInt64Value wrapper: a sub-message with field 1 = the value. */
    fun wrappedLong(field: Int): Long? = message(field)?.varint(1)

    /** A google.protobuf.StringValue wrapper: a sub-message with field 1 = the string. */
    fun wrappedString(field: Int): String? = message(field)?.string(1)

    /** A google.protobuf.BoolValue wrapper: a sub-message with field 1 = the bool (varint). */
    fun wrappedBool(field: Int): Boolean? = message(field)?.varint(1)?.let { it != 0L }

    /** A google.protobuf.DoubleValue wrapper: a sub-message with field 1 = the double. */
    fun wrappedDouble(field: Int): Double? = message(field)?.double(1)

    private companion object {
        fun readVarint(buf: ByteArray, from: Int): Pair<Long, Int> {
            var p = from
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[p++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return result to p
        }
    }
}
