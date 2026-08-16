package com.vayunmathur.communicate.data.signal.registration

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.transport.SignalTrust
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Signal registration — real verification-session flow.
 *
 * Grounded in:
 * - PushServiceSocket.java: VERIFICATION_SESSION_PATH="/v1/verification/session",
 *   VERIFICATION_CODE_PATH="/v1/verification/session/%s/code", REGISTRATION_PATH="/v1/registration"
 * - RegistrationApiV2.kt + VerificationSessionMetadataRequestBody.kt, UpdateVerificationSessionRequestBody.kt,
 *   RegistrationSessionRequestBody.kt, AccountAttributes.kt
 * - libsignal/rust/net/src/env.rs DOMAIN_CONFIG_CHAT hostname "grpc.chat.signal.org" (WS) /
 *   service REST base "https://chat.signal.org" (uncensored) — see SignalServiceNetworkAccess.kt
 *
 * Flow:
 *  1. POST /v1/verification/session {number,pushToken,pushTokenType,mcc,mnc} -> {id,allowedToRequestCode,requestedInformation,verified}
 *  2. PATCH /v1/verification/session/{id} {pushChallenge|captcha,pushToken,...} when server requests it
 *  3. POST /v1/verification/session/{id}/code {transport:"sms"|"voice",client:"android"} (+ Accept-Language)
 *  4. PUT  /v1/verification/session/{id}/code {code} -> verified=true
 *  5. POST /v1/registration Authorization: Basic e164:password body RegistrationSessionRequestBody
 *     {sessionId, accountAttributes, aciIdentityKey/pniIdentityKey (base64-nopad IdentityKey.serialize 33B),
 *      aciSignedPreKey/pniSignedPreKey {keyId,publicKey,signature},
 *      aciPqLastResortPreKey/pniPqLastResortPreKey {keyId,publicKey (KEM 1569B 0x08||1568),signature},
 *      gcmToken null if fetchesMessages:true else {gcmRegistrationId,webSocketChannel:true},
 *      skipDeviceTransfer:true, requireAtomic:true}
 *     -> {uuid/aci, pni, storageCapable} or 423 RegistrationLock {timeRemaining, svr2Credentials, svr3Credentials}
 *     423 RegistrationLock is live-only (requires SVR2/SVR3 + recoveryPassword/MasterKey.deriveRegistrationLock); keep wire-correct and return error.
 *
 * Uses :library:network NetworkClient (HttpURLConnection, no OkHttp/Ktor).
 */
class RegistrationHttpClient(private val context: Context) {

    // Real service base — chat.signal.org (REST); WS uses grpc.chat.signal.org per env.rs DOMAIN_CONFIG_CHAT.
    var baseUrl: String = "https://chat.signal.org"
    var cdnUrl: String = "https://cdn.signal.org"

    data class CodeResult(val status: String, val reason: String?, val raw: String) {
        val ok: Boolean get() = status == "sent" || status == "ok" || status == "200"

        /** Server asked for an hCaptcha token (requestedInformation contains "captcha"). */
        val needsCaptcha: Boolean get() = status == "captcha"
    }
    data class RegisterResult(
        val status: String, val aci: String?, val pni: String?, val reason: String?, val auth: SignalAuthData?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" }
    data class ExistResult(val exists: Boolean, val reason: String?, val raw: String)
    data class SessionInfo(
        val id: String,
        val allowedToRequestCode: Boolean,
        val requestedInformation: List<String>,
        val verified: Boolean,
        val nextSms: Int?,
        val nextCall: Int?,
        val raw: String,
    )

    suspend fun requestSmsCode(e164: String): CodeResult = requestCode(e164, "sms")
    suspend fun requestVoiceCode(e164: String): CodeResult = requestCode(e164, "voice")

    private suspend fun requestCode(e164: String, transport: String): CodeResult {
        val number = e164.filter { it.isDigit() || it == '+' }
        // Reuse existing scaffold if same number, else generate fresh ACI+PNI keys + password + UAK.
        val existing = SignalAuthData.load(context)
        val auth = if (existing != null && existing.phoneNumber == number && existing.password.isNotEmpty()) existing
        else SignalRegistrationKeys.generate(number).authScaffold.also { SignalAuthData.save(context, it) }

        // 1) POST /v1/verification/session
        val create = try { createVerificationSession(number, pushToken = null, mcc = null, mnc = null) } catch (t: Throwable) {
            Log.e(TAG, "createVerificationSession failed", t)
            return CodeResult("error", t.message, t.message ?: "")
        }
        // Persist session id so submitCaptcha/verifyCode can use it across process restarts.
        SignalAuthData.save(context, auth.copy(verificationSessionId = create.id, phoneNumber = number))

        // 2) Server-requested challenges gate code delivery.
        // captcha: needs an hCaptcha token minted via the signalcaptchas.org WebView (see submitCaptcha + SignalCaptchaScreen).
        if (create.requestedInformation.contains("captcha") && !create.allowedToRequestCode) {
            return CodeResult("captcha", "captcha required", create.raw)
        }
        // pushChallenge is live-only: requires an FCM token from Play Services + EventBus latch (RegistrationRepository.requestAndVerifyPushToken).
        if (create.requestedInformation.contains("pushChallenge") && !create.allowedToRequestCode) {
            try {
                patchVerificationSession(create.id, pushToken = null, pushChallenge = null, captcha = null, mcc = null, mnc = null)
            } catch (t: Throwable) {
                Log.w(TAG, "patchVerificationSession (live-only) failed", t)
            }
            return CodeResult("error", "pushChallenge required (live-only: needs FCM pushToken)", create.raw)
        }

        // 3) POST /v1/verification/session/{id}/code {transport, client}
        return sendCode(create.id, transport)
    }

    /**
     * Submit a solved hCaptcha token to the current verification session, then continue to request the code.
     *
     * Signal-Android flow (RegistrationApi.submitCaptchaToken):
     *  - WebView loads [CAPTCHA_URL]; on success it redirects to
     *    `signalcaptcha://signal-hcaptcha.{sitekey}.registration.{token}`.
     *  - The client strips the `signalcaptcha://` scheme and sends the remainder as the `captcha` field via
     *    PATCH /v1/verification/session/{id} (UpdateVerificationSessionRequestBody).
     *  - Once the session is no longer captcha-gated, request the SMS/voice code.
     *
     * [captchaToken] may be the raw `signalcaptcha://…` redirect URL or the already-stripped value; the scheme is
     * removed here so callers can pass either.
     */
    suspend fun submitCaptcha(e164: String, captchaToken: String, transport: String = "sms"): CodeResult {
        val number = e164.filter { it.isDigit() || it == '+' }
        val stored = SignalAuthData.load(context)
            ?: return CodeResult("error", "no_session", "missing verification session — request a code first")
        val sessionId = stored.verificationSessionId
            ?: return CodeResult("error", "no_session", "missing verification session — request a code first")
        val token = captchaToken.removePrefix(SIGNAL_CAPTCHA_SCHEME).ifBlank {
            return CodeResult("error", "empty_captcha", "empty captcha token")
        }
        // PATCH /v1/verification/session/{id} {captcha}
        val patched = try {
            patchVerificationSession(sessionId, pushToken = null, pushChallenge = null, captcha = token, mcc = null, mnc = null)
        } catch (t: Throwable) {
            Log.e(TAG, "submitCaptcha patch failed", t)
            return CodeResult("error", t.message, t.message ?: "")
        }
        // Keep the (possibly rotated) session id and number persisted for verifyCode.
        SignalAuthData.save(context, stored.copy(verificationSessionId = patched.id.ifEmpty { sessionId }, phoneNumber = number.ifEmpty { stored.phoneNumber }))
        if (patched.requestedInformation.contains("captcha") && !patched.allowedToRequestCode) {
            return CodeResult("captcha", "captcha rejected", patched.raw)
        }
        return sendCode(patched.id.ifEmpty { sessionId }, transport)
    }

    /** POST /v1/verification/session/{id}/code — shared by initial request and post-captcha retry. */
    private suspend fun sendCode(sessionId: String, transport: String): CodeResult {
        return try {
            val codeResp = requestVerificationCode(sessionId, transport)
            when {
                !codeResp.allowedToRequestCode && codeResp.requestedInformation.contains("captcha") ->
                    CodeResult("captcha", "captcha required", codeResp.raw)
                !codeResp.allowedToRequestCode && codeResp.raw.contains("captcha", true) ->
                    CodeResult("captcha", "captcha required", codeResp.raw)
                else -> CodeResult("sent", null, codeResp.raw)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "requestVerificationCode failed", t)
            CodeResult("error", t.message, t.message ?: "")
        }
    }

    suspend fun verifyCode(e164: String, code: String): RegisterResult {
        val stored = SignalAuthData.load(context)
            ?: return RegisterResult("error", null, null, "no_keys", null, "missing key scaffold")
        val digits = code.filter { it.isDigit() }
        val sessionId = stored.verificationSessionId
            ?: return RegisterResult("error", null, null, "no_session", null, "missing verification session — call requestSmsCode first")
        // 4) PUT /v1/verification/session/{id}/code {code}
        val verify = try { submitVerificationCode(sessionId, digits) } catch (t: Throwable) {
            Log.e(TAG, "submitVerificationCode failed", t)
            return RegisterResult("error", null, null, t.message, null, t.message ?: "")
        }
        if (!verify.verified) {
            return RegisterResult("error", null, null, "code_not_verified", null, verify.raw)
        }
        // 5) POST /v1/registration with Basic e164:password + RegistrationSessionRequestBody
        return try { submitRegistration(sessionId, stored) } catch (t: Throwable) {
            Log.e(TAG, "submitRegistration failed", t)
            RegisterResult("error", null, null, t.message, null, t.message ?: "")
        }
    }

    /** Real Signal has no GET /v1/accounts/exists/{e164}; CDSIv2 is authority for number->ACI. Post-auth use GET /v1/accounts/whoami. */
    suspend fun checkExists(e164: String): ExistResult {
        val auth = SignalAuthData.load(context)
        // If authenticated (registered), probe real endpoint GET /v1/accounts/whoami with Basic e164:password
        if (auth != null && auth.registered && auth.password.isNotEmpty()) {
            return try {
                val basic = "Basic " + b64NoPad("${auth.phoneNumber}:${auth.password}".toByteArray(Charsets.UTF_8))
                val resp = NetworkClient.performRequest(
                    "$baseUrl/v1/accounts/whoami",
                    method = "GET",
                    headers = mapOf("Authorization" to basic),
                    sslSocketFactory = SignalTrust.sslSocketFactory(context),
                )
                // whoami returns {number, aci, pni} when registered
                ExistResult(resp.status == 200, null, resp.body)
            } catch (t: Throwable) {
                Log.e(TAG, "checkExists whoami failed", t)
                ExistResult(false, t.message, t.message ?: "")
            }
        }
        // Pre-registration: no Signal endpoint to probe number existence; CDSIv2 discovery is the live-only authority.
        return ExistResult(false, "live-only: use CDSIv2 POST https://cdsi.signal.org/v1/{mrenclave}/discovery after registration", "")
    }

    // ---- Low-level verification-session calls ----

    private suspend fun createVerificationSession(number: String, pushToken: String?, mcc: String?, mnc: String?): SessionInfo {
        val body = JSONObject().apply {
            put("number", number)
            if (pushToken != null) {
                put("pushToken", pushToken)
                put("pushTokenType", "fcm")
            }
            if (mcc != null) put("mcc", mcc)
            if (mnc != null) put("mnc", mnc)
        }.toString()
        val resp = NetworkClient.performRequest(
            "$baseUrl$VERIFICATION_SESSION_PATH",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        )
        if (resp.status == 423) throw IllegalStateException("423 RegistrationLock (live-only: needs svr2Credentials/recoveryPassword)")
        if (!resp.isSuccess) throw IllegalStateException("HTTP ${resp.status}: ${resp.body.take(500)}")
        return parseSession(resp.body)
    }

    private suspend fun patchVerificationSession(
        sessionId: String,
        pushToken: String?,
        pushChallenge: String?,
        captcha: String?,
        mcc: String?,
        mnc: String?,
    ): SessionInfo {
        val body = JSONObject().apply {
            if (captcha != null) put("captcha", captcha)
            if (pushToken != null) {
                put("pushToken", pushToken)
                put("pushTokenType", "fcm")
            }
            if (pushChallenge != null) put("pushChallenge", pushChallenge)
            if (mcc != null) put("mcc", mcc)
            if (mnc != null) put("mnc", mnc)
        }.toString()
        val resp = NetworkClient.performRequest(
            "$baseUrl$VERIFICATION_SESSION_PATH/$sessionId",
            method = "PATCH",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        )
        if (!resp.isSuccess) throw IllegalStateException("HTTP ${resp.status}: ${resp.body.take(500)}")
        return parseSession(resp.body)
    }

    private suspend fun requestVerificationCode(sessionId: String, transport: String): SessionInfo {
        val body = JSONObject().apply {
            put("transport", transport) // "sms" | "voice"
            put("client", "android") // or "android-2021-03" for SMS retriever
        }.toString()
        val resp = NetworkClient.performRequest(
            "$baseUrl${String.format(VERIFICATION_CODE_PATH, sessionId)}",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept-Language" to (java.util.Locale.getDefault().toLanguageTag()),
            ),
            body = body,
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        )
        if (resp.status == 423) throw IllegalStateException("423 RegistrationLock (live-only)")
        if (resp.status == 429) throw IllegalStateException("429 rate limited: ${resp.body.take(500)}")
        if (!resp.isSuccess) throw IllegalStateException("HTTP ${resp.status}: ${resp.body.take(500)}")
        return parseSession(resp.body)
    }

    private suspend fun submitVerificationCode(sessionId: String, code: String): SessionInfo {
        val body = JSONObject().put("code", code).toString()
        val resp = NetworkClient.performRequest(
            "$baseUrl${String.format(VERIFICATION_CODE_PATH, sessionId)}",
            method = "PUT",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        )
        if (resp.status == 423) throw IllegalStateException("423 RegistrationLock (live-only)")
        // Signal keys off session.verified, not strictly the HTTP code. A 409 whose body has verified:true means
        // the code is already verified (idempotent) — mirror SubmitVerificationCodeResponseHandler, which maps that
        // 409 to AlreadyVerifiedException and the app then proceeds to POST /v1/registration. So: if the parsed
        // session is verified (on 200/204 or 409), treat it as success and continue the flow.
        val session = parseSession(resp.body)
        if (resp.isSuccess || session.verified) {
            return session
        }
        throw IllegalStateException("HTTP ${resp.status}: ${resp.body.take(500)}")
    }

    private suspend fun submitRegistration(sessionId: String, auth: SignalAuthData): RegisterResult {
        val password = auth.password.ifEmpty { return RegisterResult("error", null, null, "missing_password", null, "no password") }
        val e164 = auth.phoneNumber
        val basic = "Basic " + b64NoPad("$e164:$password".toByteArray(Charsets.UTF_8))

        val accountAttributes = buildAccountAttributesJson(auth)
        // Identity keys: Base64-nopad(IdentityKey.serialize() 33B = 0x05||32)
        val aciIdKey = auth.effectiveAciPublic().ifEmpty { auth.aciIdentityPublicKey }
        val pniIdKey = auth.pniIdentityPublicKey.ifEmpty { auth.effectiveAciPublic() }
        // Fallback: if PNI identity missing (legacy single-identity auth), reuse ACI identity wire-correctly but document live-only dual requirement
        val pniIdentityWire = if (pniIdKey.isEmpty()) aciIdKey else pniIdKey

        fun spkEntity(keyId: Int, pubB64: String, sigB64: String): JSONObject = JSONObject().apply {
            put("keyId", keyId)
            put("publicKey", pubB64.trimEnd('=')) // Base64-nopad per SignedPreKeyEntity
            put("signature", sigB64.trimEnd('='))
        }
        fun kyberEntity(keyId: Int, pubB64: String, sigB64: String): JSONObject = JSONObject().apply {
            put("keyId", keyId)
            // KEM public serialize is 1569B = 0x08||1568; keep 0x08 tag intact for wire (see SignalPqPreKey)
            put("publicKey", pubB64.trimEnd('='))
            put("signature", sigB64.trimEnd('='))
        }

        val bodyJson = JSONObject().apply {
            put("sessionId", sessionId)
            // recoveryPassword is xor with sessionId — null here; live-only SVR re-registration uses recoveryPassword instead
            put("accountAttributes", accountAttributes)
            put("aciIdentityKey", aciIdKey.trimEnd('='))
            put("pniIdentityKey", pniIdentityWire.trimEnd('='))
            put("aciSignedPreKey", spkEntity(auth.effectiveAciSignedId(), auth.effectiveAciSignedPub(), auth.effectiveAciSignedSig()))
            // pniSignedPreKey — reuse ACI if missing legacy
            val pniSpkId = if (auth.pniSignedPreKeyId != 0) auth.pniSignedPreKeyId else auth.effectiveAciSignedId()
            val pniSpkPub = auth.pniSignedPreKeyPublic.ifEmpty { auth.effectiveAciSignedPub() }
            val pniSpkSig = auth.pniSignedPreKeySignature.ifEmpty { auth.effectiveAciSignedSig() }
            put("pniSignedPreKey", spkEntity(pniSpkId, pniSpkPub, pniSpkSig))
            put("aciPqLastResortPreKey", kyberEntity(auth.effectiveAciPqId(), auth.effectiveAciPqPub(), auth.effectiveAciPqSig()))
            val pniPqId = if (auth.pniPqLastResortKeyId != 0) auth.pniPqLastResortKeyId else auth.effectiveAciPqId()
            val pniPqPub = auth.pniPqLastResortPublic.ifEmpty { auth.effectiveAciPqPub() }
            val pniPqSig = auth.pniPqLastResortSignature.ifEmpty { auth.effectiveAciPqSig() }
            put("pniPqLastResortPreKey", kyberEntity(pniPqId, pniPqPub, pniPqSig))
            // gcmToken: null if fetchesMessages:true (primary), else {gcmRegistrationId, webSocketChannel:true}
            put("skipDeviceTransfer", true)
            put("requireAtomic", true)
        }.toString()

        val resp = NetworkClient.performRequest(
            "$baseUrl$REGISTRATION_PATH",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to basic,
            ),
            body = bodyJson,
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        )
        // 423 RegistrationLock — live-only: server returns {timeRemaining, svr2Credentials:{username,password}, svr3Credentials} + recoveryPassword path
        if (resp.status == 423) {
            return RegisterResult("error", null, null, "423 RegistrationLock (live-only: needs svr2Credentials/recoveryPassword via SVR2/SVR3)", null, resp.body)
        }
        if (!resp.isSuccess) {
            // Log the full server body so a 422/400 names the rejected field in logcat on the next retest.
            Log.e(TAG, "submitRegistration HTTP ${resp.status}: ${resp.body.take(1000)}")
            return RegisterResult("error", null, null, "HTTP ${resp.status}", null, resp.body)
        }
        return finalizeRegister(resp.body, auth)
    }

    private fun buildAccountAttributesJson(auth: SignalAuthData): JSONObject {
        // AccountAttributes per org.whispersystems.signalservice.api.account.AccountAttributes
        // {signalingKey:null, registrationId, pniRegistrationId, unidentifiedAccessKey 32B, registrationLock, recoveryPassword?,
        //  fetchesMessages:true, capabilities:{storage,versionedExpirationTimer,attachmentBackfill,spqr,usernameChangeSyncMessage}, discoverableByPhoneNumber, name}
        val uakB64 = auth.unidentifiedAccessKey
        val uakBytes = if (uakB64.isNotEmpty()) runCatching { Base64.decode(uakB64, Base64.NO_WRAP) }.getOrNull() else null
        val caps = JSONObject().apply {
            put("storage", true)
            put("versionedExpirationTimer", true)
            put("attachmentBackfill", true)
            put("spqr", true)
            put("usernameChangeSyncMessage", true)
        }
        return JSONObject().apply {
            put("signalingKey", JSONObject.NULL)
            put("registrationId", auth.effectiveAciRegId())
            put("pniRegistrationId", if (auth.pniRegistrationId != 0) auth.pniRegistrationId else auth.effectiveAciRegId())
            put("fetchesMessages", true)
            // AccountAttributes has no class-level @JsonInclude(NON_NULL): nullable fields serialize as explicit null.
            put("registrationLock", auth.registrationLock ?: JSONObject.NULL) // null until live SVR2 MasterKey.deriveRegistrationLock
            put("recoveryPassword", JSONObject.NULL) // live-only SVR re-registration path; null for session-based reg
            // Jackson serializes AccountAttributes.unidentifiedAccessKey (ByteArray) with its default variant =
            // standard base64 WITH padding (MIME_NO_LINEFEEDS) — unlike the prekey material which is nopad.
            if (uakBytes != null) put("unidentifiedAccessKey", b64Padded(uakBytes)) else put("unidentifiedAccessKey", JSONObject.NULL)
            put("unrestrictedUnidentifiedAccess", false)
            put("discoverableByPhoneNumber", true)
            put("capabilities", caps)
            put("name", auth.profileName.ifEmpty { JSONObject.NULL })
            // voice/video true by default in two-arg constructor
            put("voice", true)
            put("video", true)
        }
    }

    private fun finalizeRegister(body: String, auth: SignalAuthData): RegisterResult {
        val j = parse(body)
        val status = j.optString("status", if (body.contains("\"uuid\"") || body.contains("\"aci\"")) "ok" else "error")
        val aci = j.optStringOrNull("uuid") ?: j.optStringOrNull("aci") ?: j.optStringOrNull("number")
        val pni = j.optStringOrNull("pni")
        // VerifyAccountResponse fields: uuid/aci, pni, storageCapable, number, reregistration
        val aciVal = j.optStringOrNull("uuid") ?: j.optStringOrNull("aci") ?: auth.aci
        val pniVal = pni ?: auth.pni
        val ok = status == "ok" || (aciVal.isNotEmpty() && body.contains("\"uuid\""))
        val updated = if (ok || j.has("uuid") || j.has("aci")) {
            auth.copy(aci = aciVal.ifEmpty { aci ?: auth.aci }, pni = pniVal, registered = true).also { SignalAuthData.save(context, it) }
        } else null
        val finalStatus = if (updated != null) "ok" else status
        return RegisterResult(finalStatus, aci, pni, j.optStringOrNull("reason"), updated, body)
    }

    private fun parseSession(body: String): SessionInfo {
        val j = parse(body)
        val id = j.optStringOrNull("id") ?: j.optStringOrNull("sessionId") ?: ""
        val allowed = j.optBoolean("allowedToRequestCode", true)
        val verified = j.optBoolean("verified", false)
        val nextSms = j.optIntOrNull("nextSms")
        val nextCall = j.optIntOrNull("nextCall")
        val reqInfo = mutableListOf<String>()
        val arr: JSONArray? = j.optJSONArray("requestedInformation")
        if (arr != null) for (i in 0 until arr.length()) reqInfo.add(arr.optString(i))
        return SessionInfo(id, allowed, reqInfo, verified, nextSms, nextCall, body)
    }

    private fun parse(body: String): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

    companion object {
        private const val TAG = "SignalRegHttp"
        private const val VERIFICATION_SESSION_PATH = "/v1/verification/session"
        private const val VERIFICATION_CODE_PATH = "/v1/verification/session/%s/code"
        private const val REGISTRATION_PATH = "/v1/registration"

        /** hCaptcha challenge page (Signal-Android BuildConfig.SIGNAL_CAPTCHA_URL). */
        const val CAPTCHA_URL = "https://signalcaptchas.org/registration/generate.html"

        /**
         * Redirect scheme the captcha page hands back on success:
         * `signalcaptcha://signal-hcaptcha.{sitekey}.registration.{token}` (RegistrationConstants.SIGNAL_CAPTCHA_SCHEME).
         * The value after this prefix is submitted verbatim as the `captcha` field.
         */
        const val SIGNAL_CAPTCHA_SCHEME = "signalcaptcha://"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
private fun JSONObject.optBoolean(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun b64NoPad(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP).trimEnd('=')
private fun b64Padded(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
