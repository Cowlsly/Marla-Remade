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
        // Persist session id so verifyCode can use it across process restarts.
        SignalAuthData.save(context, auth.copy(verificationSessionId = create.id, phoneNumber = number))

        // 2) PATCH if server asks for pushChallenge/captcha.
        // live-only: pushChallenge requires FCM token from Play Services + 5s EventBus latch (RegistrationRepository.requestAndVerifyPushToken); captcha requires hCaptcha token
        if (create.requestedInformation.contains("pushChallenge") || create.requestedInformation.contains("captcha")) {
            // Wire-correct PATCH construction; offline we have no FCM/captcha token so we send empty PATCH and surface reason.
            try {
                patchVerificationSession(create.id, pushToken = null, pushChallenge = null, captcha = null, mcc = null, mnc = null)
            } catch (t: Throwable) {
                Log.w(TAG, "patchVerificationSession (live-only) failed", t)
            }
            if (create.requestedInformation.contains("pushChallenge") && !create.allowedToRequestCode) {
                return CodeResult("error", "pushChallenge required (live-only: needs FCM pushToken)", create.raw)
            }
            if (create.requestedInformation.contains("captcha") && !create.allowedToRequestCode) {
                return CodeResult("error", "captcha required (live-only: needs hCaptcha token)", create.raw)
            }
        }

        // 3) POST /v1/verification/session/{id}/code {transport, client}
        return try {
            val codeResp = requestVerificationCode(create.id, transport)
            if (!codeResp.allowedToRequestCode && codeResp.raw.contains("captcha", true)) {
                CodeResult("error", "captcha required", codeResp.raw)
            } else {
                CodeResult("sent", null, codeResp.raw)
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
        if (!resp.isSuccess) throw IllegalStateException("HTTP ${resp.status}: ${resp.body.take(500)}")
        return parseSession(resp.body)
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
            put("registrationLock", auth.registrationLock) // null until live SVR2 MasterKey.deriveRegistrationLock
            if (uakBytes != null) put("unidentifiedAccessKey", b64NoPad(uakBytes)) else put("unidentifiedAccessKey", JSONObject.NULL)
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
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
private fun JSONObject.optBoolean(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun b64NoPad(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP).trimEnd('=')
