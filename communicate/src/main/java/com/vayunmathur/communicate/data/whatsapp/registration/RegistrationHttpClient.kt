package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Speaks WhatsApp's primary-registration `/v2/ endpoints` protocol (own phone-number registration).
 *
 * Flow: [requestCode] (→ SMS/voice OTP) → [register] (with the OTP) → persisted [WhatsAppAuthData]
 * with `new_jid`. [checkExist] probes state; [submitTwoFactor] handles the `/v2/security` PIN path.
 *
 * Parameter set + encodings are matched **exactly** to the pinned APK's `KotlinRegistrationBridge` +
 * `C34244EyE` request builder (see [RegEncoding]) to avoid `bad_param`:
 *  - plain `A01`: cc, in, lg, lc, fdid, token, method, code
 *  - `A03` (UUID→16B→url-b64): expid
 *  - `A05` (percent-encoded raw bytes, stored pre-encoded): id, backup_token
 *  - `A00` ("true"/"false"): clicked_education_link, manage_call_permission, call_log_permission
 *  - `A04` (url-b64) E2E bundle: authkey, e_ident, e_keytype, e_regid, e_skey_id, e_skey_val, e_skey_sig
 *
 * Body is `application/x-www-form-urlencoded`. Default is the PLAIN body (the official client falls
 * back to plain when its ENC layer fails, so the server accepts it); [useEncWrapper] adds ENC/H.
 */
class RegistrationHttpClient(
    private val context: Context,
    private val useEncWrapper: Boolean = false,
) {
    private val fingerprint = WhatsAppDeviceFingerprint.getOrCreate(context)

    // ------------------------------------------------------------------ results

    data class CodeResult(
        val status: String, val method: String?, val length: Int?,
        val retryAfter: Long?, val reason: String?, val param: String?, val raw: String,
    ) { val ok: Boolean get() = status == "sent" || status == "ok" }

    data class RegisterResult(
        val status: String, val newJid: String?, val login: String?, val serverTime: Long?,
        val reason: String?, val param: String?, val auth: WhatsAppAuthData?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" }

    data class ExistResult(val status: String, val reason: String?, val param: String?, val raw: String)

    /** `/v2/consent` — over-18 self-declare / DOB / parent-verification consent (§3.12). */
    data class ConsentResult(
        val status: String, val reason: String?, val pending: String?, val url: String?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" }

    /** `/v2/autoconf` — request the autoconf verifier / attribute list (§3.8). */
    data class AutoconfResult(
        val status: String, val reason: String?, val registerStartMessage: String?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" || status == "sent" }

    /** `/v2/autoconf_verifier` — submit the encrypted verifier data (§3.9). */
    data class AutoconfVerifierResult(val status: String, val reason: String?, val raw: String) {
        val ok: Boolean get() = status == "ok"
    }

    /** `/v2/captcha_verify` — fraud-checkpoint code verification (§3.10). */
    data class CaptchaVerifyResult(
        val status: String, val reason: String?, val violationType: String?, val appealToken: String?, val raw: String,
    ) { val ok: Boolean get() = status == "verified" }

    /** `/v2/challenge` — email/oauth challenge step (§3.14). */
    data class ChallengeResult(val status: String, val reason: String?, val raw: String) {
        val ok: Boolean get() = status == "ok"
    }

    /** `/v2/client_log` — fire-and-forget onboarding funnel log (§3.6). */
    data class ClientLogResult(val status: String, val raw: String) {
        val ok: Boolean get() = status == "ok"
    }

    // ------------------------------------------------------------------ endpoints

    /** POST `/v2/code` — request an OTP. Generates + persists fresh key material first. */
    suspend fun requestCode(cc: String, number: String, method: String = "sms"): CodeResult {
        val keys = RegistrationKeys.generate("$cc$number")
        WhatsAppAuthData.save(context, keys.authScaffold)

        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        // Token uses the NATIONAL number only (APK: ES2.A00.A01(app, $phoneNumber); $countryCode is
        // a separate param, and server login = cc + in confirms `in`/$phoneNumber is national).
        p.a01("token", RegistrationAttestation.computeToken(context, number))
        p.a01("method", method)
        p.a00("clicked_education_link", false)
        p.a00("manage_call_permission", false)
        p.a00("call_log_permission", false)
        p.addIntegrity(EndpointKind.CODE)
        p.bundle(RegistrationKeys.bundleFields(keys.authScaffold))

        val body = send("code", p)
        val j = parse(body)
        Log.e(TAG, "W2 code raw=${body.take(2000)} reason=${j.optStringOrNull("reason")} param=${j.optStringOrNull("param")}")
        return CodeResult(
            status = j.optString("status", "error"),
            method = j.optStringOrNull("method"),
            length = j.optIntOrNull("length"),
            retryAfter = j.optLongOrNull("retry_after"),
            reason = j.optStringOrNull("reason"),
            param = j.optStringOrNull("param"),
            raw = body,
        )
    }

    /** POST `/v2/register` — submit the OTP and finalize the primary line. */
    suspend fun register(cc: String, number: String, code: String): RegisterResult {
        val auth = WhatsAppAuthData.load(context)
            ?: return RegisterResult("error", null, null, null, "no_keys", null, "missing key scaffold")
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("code", code.filter { it.isDigit() })
        p.addIntegrity(EndpointKind.REGISTER)
        p.bundle(RegistrationKeys.bundleFields(auth))

        val body = send("register", p)
        return finalizeRegister(body, auth)
    }

    /** POST `/v2/security` — submit the account's 2FA PIN when register returns `security_code`. */
    suspend fun submitTwoFactor(cc: String, number: String, pin: String): RegisterResult {
        val auth = WhatsAppAuthData.load(context)
            ?: return RegisterResult("error", null, null, null, "no_keys", null, "missing key scaffold")
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("code", pin.filter { it.isDigit() })
        p.bundle(RegistrationKeys.bundleFields(auth))

        val body = send("security", p)
        return finalizeRegister(body, auth)
    }

    /** POST `/v2/exist` — probe whether the number is already registered on this key material. */
    suspend fun checkExist(cc: String, number: String): ExistResult {
        // DEBUG: log token variants so we can compare to the offline reference (no SMS sent).
        runCatching {
            Log.i(TAG, "debug token national=$number -> ${RegistrationAttestation.computeToken(context, number)}")
            Log.i(TAG, "debug token full=$cc$number -> ${RegistrationAttestation.computeToken(context, "$cc$number")}")
        }
        val auth = WhatsAppAuthData.load(context)
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        // Bug fix (w2.md §3.1): /v2/exist REQUIRES `token`; it was previously omitted.
        p.a01("token", RegistrationAttestation.computeToken(context, number))
        p.addIntegrity(EndpointKind.EXIST)
        if (auth != null) p.bundle(RegistrationKeys.bundleFields(auth))
        val body = send("exist", p)
        val j = parse(body)
        Log.e(TAG, "W2 exist raw=${body.take(2000)} reason=${j.optStringOrNull("reason")} param=${j.optStringOrNull("param")}")
        return ExistResult(j.optString("status", "error"), j.optStringOrNull("reason"), j.optStringOrNull("param"), body)
    }

    /**
     * POST `/v2/consent` (§3.12). Records the age/consent decision. Needs the E2E encryption bundle
     * (`/v2/consent` is in the proto-2.0 encryption list, §2.1). [consentContext] is one of
     * `app_store_age|dob|parent_verification|consent`.
     */
    suspend fun consent(
        cc: String,
        number: String,
        consentContext: String,
        dob: String? = null,
        ageLowerBound: Int? = null,
        consentDecision: String? = null,
        consentId: String? = null,
        consentVersion: String? = null,
        securityCode: String? = null,
    ): ConsentResult {
        val auth = WhatsAppAuthData.load(context)
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        if (auth != null) p.bundle(RegistrationKeys.bundleFields(auth))
        p.a01("context", consentContext)
        p.a02("dob", dob)
        if (ageLowerBound != null) p.a01("age_lower_bound", ageLowerBound.toString())
        p.a02("consent_decision", consentDecision)
        p.a02("consent_id", consentId)
        p.a02("consent_version", consentVersion)
        p.a02("security_code", securityCode)
        val body = send("consent", p)
        val j = parse(body)
        return ConsentResult(
            status = j.optString("status", "error"),
            reason = j.optStringOrNull("reason"),
            pending = j.optStringOrNull("pending"),
            url = j.optStringOrNull("url"),
            raw = body,
        )
    }

    /**
     * POST `/v2/autoconf` (§3.8). Requests the autoconf verifier / attribute list. No E2E bundle
     * (autoconf is not in the §2.1 encryption list). [consent] is `1` or `2`.
     */
    suspend fun autoconf(
        cc: String,
        number: String,
        consent: Int,
        clientCapabilities: String? = null,
        consentShown: Boolean,
        createVerifier: Boolean,
    ): AutoconfResult {
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("consent", consent.toString())
        p.a02("client_capabilities", clientCapabilities)
        p.a00("consent_shown", consentShown)
        p.a00("create_verifier", createVerifier)
        val body = send("autoconf", p)
        val j = parse(body)
        return AutoconfResult(
            status = j.optString("status", "error"),
            reason = j.optStringOrNull("reason"),
            registerStartMessage = j.optStringOrNull("register_start_message"),
            raw = body,
        )
    }

    /**
     * POST `/v2/autoconf_verifier` (§3.9). Submits the encrypted verifier data.
     * [registrationMethod]: 0 token, 2 wa_old, 3 email, 4 flash, 5 sms.
     */
    suspend fun autoconfVerifier(
        cc: String,
        number: String,
        code: String,
        encryptedVerifierData: String,
        registrationMethod: Int,
    ): AutoconfVerifierResult {
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("code", code.filter { it.isDigit() })
        p.a01("encrypted_verifier_data", encryptedVerifierData)
        p.a01("registration_method", registrationMethod.toString())
        val body = send("autoconf_verifier", p)
        val j = parse(body)
        return AutoconfVerifierResult(j.optString("status", "error"), j.optStringOrNull("reason"), body)
    }

    /**
     * POST `/v2/captcha_verify` (§3.10). Verifies a fraud-checkpoint code. Needs only `authkey` from
     * the E2E bundle (per §3.10), not the full `e_*` set.
     */
    suspend fun captchaVerify(cc: String, number: String, fraudCheckpointCode: String): CaptchaVerifyResult {
        val auth = WhatsAppAuthData.load(context)
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        if (auth != null) {
            RegistrationKeys.bundleFields(auth)["authkey"]?.let { p.bundle(mapOf("authkey" to it)) }
        }
        p.a01("fraud_checkpoint_code", fraudCheckpointCode)
        val body = send("captcha_verify", p)
        val j = parse(body)
        return CaptchaVerifyResult(
            status = j.optString("status", "error"),
            reason = j.optStringOrNull("reason"),
            violationType = j.optStringOrNull("violation_type"),
            appealToken = j.optStringOrNull("appeal_token"),
            raw = body,
        )
    }

    /**
     * POST `/v2/challenge` (§3.14). Email/OAuth challenge step. No E2E bundle.
     * [challengeType]: `email_enter|email_verify`.
     */
    suspend fun challenge(
        cc: String,
        number: String,
        challengeType: String,
        email: String? = null,
        oauthToken: String? = null,
        code: String? = null,
    ): ChallengeResult {
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("challenge_type", challengeType)
        p.a02("email", email)
        p.a02("oauth_token", oauthToken)
        p.a02("code", code)
        val body = send("challenge", p)
        val j = parse(body)
        return ChallengeResult(j.optString("status", "error"), j.optStringOrNull("reason"), body)
    }

    /**
     * POST `/v2/client_log` (§3.6). Fire-and-forget onboarding funnel log; no encryption/integrity,
     * only `lg,lc,rc` + `cc?/in?` + the error context/type. Always returns `ok` server-side.
     */
    suspend fun clientLog(
        errorContext: String,
        errorType: String,
        cc: String? = null,
        number: String? = null,
    ): ClientLogResult {
        val p = RegParams()
        p.a02("cc", cc)
        p.a02("in", number)
        p.a01("lg", context.resources.configuration.locales[0].language.ifEmpty { "en" })
        p.a01("lc", context.resources.configuration.locales[0].country.ifEmpty { "US" })
        p.a01("rc", "0")
        p.a01("client_error_context", errorContext)
        p.a01("client_error_type", errorType)
        val body = send("client_log", p)
        val j = parse(body)
        return ClientLogResult(j.optString("status", "error"), body)
    }

    // ------------------------------------------------------------------ internals

    private fun finalizeRegister(body: String, auth: WhatsAppAuthData): RegisterResult {
        val j = parse(body)
        val status = j.optString("status", "error")
        val newJid = j.optStringOrNull("new_jid")
        val login = j.optStringOrNull("login")
        val lid = j.optStringOrNull("lid")
        // Success may omit `new_jid` (e.g. type:"existing" re-registration); the server returns the
        // phone in `login`, so derive the JID from it. Registration is complete whenever status==ok.
        val updated = if (status == "ok") {
            val wid = when {
                newJid != null -> normalizeJid(newJid)
                login != null -> "$login@s.whatsapp.net"
                else -> auth.wid
            }
            auth.copy(
                wid = wid,
                lid = lid?.let { if (it.contains("@")) it else "$it@lid" } ?: auth.lid,
                serverTime = j.optLongOrNull("server_time") ?: 0L,
                registered = true,
                loggedInAt = System.currentTimeMillis() / 1000,
            ).also { WhatsAppAuthData.save(context, it) }
        } else {
            null
        }
        val reason = j.optStringOrNull("reason")
        val param = j.optStringOrNull("param")
        if (status != "ok") Log.e(TAG, "W2 register raw=${body.take(2000)} reason=$reason param=$param")
        return RegisterResult(
            status = status,
            newJid = newJid ?: login?.let { "$it@s.whatsapp.net" },
            login = login,
            serverTime = j.optLongOrNull("server_time"),
            reason = reason,
            param = param,
            auth = updated,
            raw = body,
        )
    }

    /**
     * Mirrors `C34244EyE`: values are stored either PLAIN (A00/A01/A02/A03/A04) or PRE-ENCODED via
     * percent-encoding (A05, tracked in [raw]). At query build the raw values are appended as-is; all
     * others are URL-encoded — exactly as the APK's request transform does.
     */
    private inner class RegParams {
        val map = LinkedHashMap<String, String>()
        val raw = HashSet<String>()

        fun a00(key: String, value: Boolean) { map[key] = if (value) "true" else "false" }
        fun a01(key: String, value: String) { map[key] = value }
        fun a02(key: String, value: String?) { if (value != null) map[key] = value }
        fun a03(key: String, uuid: String) { map[key] = RegEncoding.b64Url(RegEncoding.uuidToBytes(uuid)) }
        fun a05(key: String, bytes: ByteArray) { map[key] = RegEncoding.percentEncode(bytes); raw.add(key) }
        fun bundle(fields: Map<String, String>) { map.putAll(fields) } // already url-b64 (A04), non-raw

        fun addCommon() {
            a01("lg", context.resources.configuration.locales[0].language.ifEmpty { "en" })
            a01("lc", context.resources.configuration.locales[0].country.ifEmpty { "US" })
            a01("fdid", fingerprint.fdid)
            a03("expid", fingerprint.expid)
            a05("id", fingerprint.recoveryToken)
            a05("backup_token", fingerprint.backupToken)
        }

        /**
         * Common/device params added by the APK's `F4L` layer (merged via `A06(map)`). Confirmed
         * against the native param-key table in libwhatsappmerged.so: WhatsApp does NOT send a
         * `platform` form param — the server derives platform from the User-Agent — so we must not
         * send one. These device fields are the real F4L set; values are URL-safe.
         */
        fun addDevice() {
            val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
            val netOp = tm?.networkOperator.orEmpty()
            val simOp = tm?.simOperator.orEmpty()
            a01("mcc", if (netOp.length >= 3) netOp.substring(0, 3) else "000")
            a01("mnc", if (netOp.length > 3) netOp.substring(3) else "000")
            a01("sim_mcc", if (simOp.length >= 3) simOp.substring(0, 3) else "000")
            a01("sim_mnc", if (simOp.length > 3) simOp.substring(3) else "000")
            a01("network_radio_type", "1")
            a01("simnum", "1")
            a01("hasinrc", "0")
            a01("pid", android.os.Process.myPid().toString())
            a01("rc", "0")
        }

        fun query(): String = map.entries.joinToString("&") { (k, v) ->
            val encoded = if (k in raw) v else URLEncoder.encode(v, "UTF-8")
            "$k=$encoded"
        }

        /**
         * Add the per-endpoint device-integrity signals (w2.md §2.3/§3.1-3.3, Phase B 2e) from
         * [RegistrationIntegrity]. `gpia`/`_gg` (Play Integrity) and `recaptcha` are intentionally
         * NOT sent — they are bound to the official signed WhatsApp app identity and an unofficial
         * client cannot mint server-valid tokens (documented, not faked).
         *
         * Inclusion per endpoint:
         *  - EXIST:    aid,_gi,_gp,_ge,_ga,_gs,db  (+profile_name; no t)
         *  - CODE:     aid,_gi,_gp,_ge,_ga,_gs,t,hasav
         *  - REGISTER: aid,_gi,_gp,_ge,_ga,_gs,t
         */
        fun addIntegrity(kind: EndpointKind) {
            val s = runCatching { RegistrationIntegrity.collect(context) }.getOrNull() ?: return
            if (s.aid.isNotEmpty()) a01("aid", s.aid)
            a02("_gi", s.gi)
            a01("_gp", s.gp)
            a01("_ge", s.ge)
            a01("_ga", s.ga)
            a01("_gs", s.gs)
            when (kind) {
                EndpointKind.EXIST -> {
                    a01("db", s.db)
                    WhatsAppAuthData.load(context)?.profileName?.takeIf { it.isNotEmpty() }
                        ?.let { a01("profile_name", it) }
                }
                EndpointKind.CODE -> {
                    a01("t", RegistrationIntegrity.tField(s.tSeconds))
                    a01("hasav", "0") // 0 = can't read SMS (we never read the user's SMS inbox)
                }
                EndpointKind.REGISTER -> {
                    a01("t", RegistrationIntegrity.tField(s.tSeconds))
                }
            }
        }
    }

    private suspend fun send(path: String, params: RegParams): String {
        val query = params.query()
        val body = if (useEncWrapper) {
            val encv = RegistrationAttestation.encryptQueryString(query)
            if (encv != null) {
                val h = RegistrationAttestation.signWithAttestation(encv, fingerprint.attestationKey)
                "ENC=${URLEncoder.encode(encv, "UTF-8")}&H=${URLEncoder.encode(h, "UTF-8")}"
            } else {
                query
            }
        } else {
            query
        }

        val headers = mapOf(
            "User-Agent" to userAgent(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "text/json",
        )
        return try {
            val resp = NetworkClient.performRequest(
                url = WhatsAppRegistrationConstants.endpoint(path),
                method = "POST",
                headers = headers,
                body = body,
                useSystemTrust = true,
            )
            Log.i(TAG, "/v2/$path -> ${resp.status}: ${resp.body.take(400)}")
            resp.body
        } catch (t: Throwable) {
            Log.e(TAG, "/v2/$path request failed", t)
            """{"status":"error","reason":"network:${t.message}"}"""
        }
    }

    /** WhatsApp/<ver> Android/<osrel> Device/<manufacturer>-<model> (server parses platform here). */
    private fun userAgent(): String {
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
        return "WhatsApp/${WhatsAppProtocol.WA_VERSION_NAME} Android/${Build.VERSION.RELEASE} Device/$device"
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        JSONObject().put("status", "error").put("reason", "unparseable")
    }

    private fun normalizeJid(jid: String): String =
        if (jid.contains("@")) jid else "$jid@s.whatsapp.net"

    companion object {
        private const val TAG = "WARegHttp"
    }

    /** WAMSYS endpoint kinds that carry per-endpoint integrity params (w2.md §3.1-3.3). */
    private enum class EndpointKind { EXIST, CODE, REGISTER }
}

// -- small JSONObject null-safe helpers --
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
