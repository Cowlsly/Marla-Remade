package com.vayunmathur.email.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.vayunmathur.email.BuildConfig
import com.vayunmathur.email.EmailAccount
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outlook / Microsoft 365 OAuth2 — Authorization Code + PKCE for our own Azure
 * app registered as Mobile and desktop applications with redirect
 * `com.vayunmathur.email://oauth` (RFC8252 compliant native flow, same as
 * Thunderbird Desktop `useExternalBrowser=true` in `OAuth2Providers.sys.mjs`).
 *
 * Flow:
 * 1. `start()` — generate verifier (64 base64Url), challenge S256, state 24,
 *    persist to `outlook_oauth` prefs, open Custom Tab at Microsoft authorize endpoint.
 * 2. Redirect `com.vayunmathur.email://oauth?code=...&state=...` → [com.vayunmathur.email.ui.OAuthActivity]
 *    → `complete()` validates state, POSTs to token endpoint (form-urlencoded),
 *    parses access_token + refresh_token + id_token.email, persists account with `authType=oauth2`.
 * 3. `freshAccessToken()` — refreshes <1min expiry via `grant_type=refresh_token`.
 *
 * Fix for "browser closed but not signed in":
 * - Intent-filter for `com.vayunmathur.email://oauth` now exists (was missing) + bare `/oauth` path variant
 * - Fallback `extractQueryParam()` for OEMs that lose query in single-slash URIs
 * - Explicit error logging of `error`, `error_description` from Microsoft, clearing stale prefs only after failure
 * - Email from id_token (`preferred_username` for Entra) + fallback to emailHint if user typed it
 * - CustomTabs failure fallback to VIEW intent + FLAG_ACTIVITY_NEW_TASK
 */
object OutlookOAuth {
    private const val TAG = "OutlookOAuth"
    private const val AUTH_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    private const val PREFS = "outlook_oauth"

    private val SCOPES = listOf(
        "https://outlook.office.com/IMAP.AccessAsUser.All",
        "https://outlook.office.com/SMTP.Send",
        "offline_access",
        "openid",
        "email",
        "profile",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(): Boolean = BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.isNotBlank()

    fun start(context: Context, emailHint: String = "") {
        if (!isConfigured()) return
        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("verifier", verifier)
            putString("state", state)
            putString("emailHint", emailHint)
        }

        val redirectUri = BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank { BuildConfig.OAUTH_REDIRECT_URI.ifBlank { "com.vayunmathur.email://oauth" } }

        val url = AUTH_ENDPOINT.toUri().buildUpon()
            .appendQueryParameter("client_id", BuildConfig.OUTLOOK_OAUTH_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply {
                if (emailHint.isNotBlank()) appendQueryParameter("login_hint", emailHint)
            }
            .build()

        Log.d(TAG, "Starting Outlook OAuth -> $url redirect=$redirectUri")
        try {
            CustomTabsIntent.Builder().build().apply {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, url)
        } catch (e: Exception) {
            Log.w(TAG, "CustomTabs failed, fallback to VIEW: ${e.message}")
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, url).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    sealed class OAuthResult {
        data class Success(val email: String) : OAuthResult()
        data class Failure(
            val reason: String,
            val error: String? = null,
            val errorDescription: String? = null,
        ) : OAuthResult()
    }

    suspend fun complete(context: Context, redirect: Uri): OAuthResult {
        val rawStr = redirect.toString()
        Log.d(TAG, "complete redirect=$redirect host=${redirect.host} path=${redirect.path} query=${redirect.query} raw=$rawStr")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verifier = prefs.getString("verifier", null)
        if (verifier == null) {
            Log.e(TAG, "No verifier — prefs $PREFS missing; wrong flow or cleared?")
            return OAuthResult.Failure("No PKCE verifier found — please try signing in again")
        }
        val expectedState = prefs.getString("state", null)
        val emailHintSaved = prefs.getString("emailHint", "") ?: ""

        val code = redirect.getQueryParameter("code") ?: extractQueryParam(rawStr, "code")
        if (code == null) {
            val err = redirect.getQueryParameter("error") ?: extractQueryParam(rawStr, "error")
            val desc = redirect.getQueryParameter("error_description") ?: extractQueryParam(rawStr, "error_description")
            Log.e(TAG, "No code, error=$err desc=$desc raw=$rawStr")
            if (err != null) prefs.edit { clear() }
            val reason = err ?: "No authorization code from Microsoft"
            return OAuthResult.Failure(reason, err, desc)
        }

        val returnedState = redirect.getQueryParameter("state") ?: extractQueryParam(rawStr, "state")
        if (expectedState != null && returnedState != null && returnedState != expectedState) {
            Log.e(TAG, "State mismatch exp=$expectedState got=$returnedState")
            prefs.edit { clear() }
            return OAuthResult.Failure("State mismatch — possible CSRF, please retry", "state_mismatch", "expected=$expectedState got=$returnedState")
        }

        val exchangeResult = exchangeWithError(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank { BuildConfig.OAUTH_REDIRECT_URI.ifBlank { "com.vayunmathur.email://oauth" } },
                "code_verifier" to verifier,
                "scope" to SCOPES.joinToString(" "),
            ),
        )
        val tokens = exchangeResult.tokens
        if (tokens == null) {
            Log.e(TAG, "Token exchange failed: ${exchangeResult.error} desc=${exchangeResult.errorDescription} raw=${exchangeResult.rawBody}")
            prefs.edit { clear() }
            val reason = exchangeResult.error ?: "Token exchange failed"
            return OAuthResult.Failure(reason, exchangeResult.error, exchangeResult.errorDescription ?: exchangeResult.rawBody)
        }

        prefs.edit { clear() }

        val email = tokens.idTokenEmail ?: emailHintSaved.takeIf { it.contains("@") }
        if (email.isNullOrBlank()) {
            Log.e(TAG, "No email from id_token, hint='$emailHintSaved'")
            return OAuthResult.Failure("No email found in id_token — try entering your email before signing in", "no_email_in_id_token", null)
        }

        val account = EmailAccount(
            email = email,
            provider = PROVIDER_OUTLOOK,
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapUseSsl = true,
            smtpHost = "smtp-mail.outlook.com",
            smtpPort = 587,
            smtpUseSsl = false,
            authType = "oauth2",
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAtMs,
        )
        EmailRepository.get(context).getDatabase().emailDao().insertAccount(account)
        EmailSyncWorker.scheduleHourlyNonInboxSync(context)
        EmailSyncWorker.runOneOffSync(context)
        ImapIdleService.start(context)
        Log.d(TAG, "Outlook persisted: $email")
        return OAuthResult.Success(email)
    }

    suspend fun freshAccessToken(context: Context, account: EmailAccount): String? {
        if (account.expiresAt > System.currentTimeMillis() + 60_000 && account.accessToken.isNotBlank()) {
            return account.accessToken
        }
        val refresh = account.refreshToken?.takeIf { it.isNotBlank() } ?: return account.accessToken.ifBlank { null }

        val tokens = exchange(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank { BuildConfig.OAUTH_REDIRECT_URI.ifBlank { "com.vayunmathur.email://oauth" } },
                "scope" to SCOPES.joinToString(" "),
            ),
        ) ?: return account.accessToken.ifBlank { null }

        val updated = account.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: account.refreshToken,
            expiresAt = tokens.expiresAtMs,
        )
        EmailRepository.get(context).getDatabase().emailDao().insertAccount(updated)
        return updated.accessToken
    }

    private data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long, val idTokenEmail: String?)
    private data class ExchangeResult(
        val tokens: Tokens?,
        val error: String?,
        val errorDescription: String?,
        val rawBody: String?,
    )

    private suspend fun exchange(form: Map<String, String>): Tokens? = exchangeWithError(form).tokens

    private suspend fun exchangeWithError(form: Map<String, String>): ExchangeResult = withContext(Dispatchers.IO) {
        try {
            val body = form.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }
            val conn = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val respCode = conn.responseCode
            val text = if (respCode in 200..299) conn.inputStream.bufferedReader().readText() else conn.errorStream?.bufferedReader()?.readText() ?: ""
            Log.d(TAG, "token $respCode body=$text formKeys=${form.keys.filter { it != "code" && it != "refresh_token" && it != "code_verifier" }}")
            if (respCode !in 200..299) {
                Log.e(TAG, "token exchange $respCode: $text")
                var err: String? = null
                var errDesc: String? = null
                try {
                    val root = json.parseToJsonElement(text) as? JsonObject
                    err = root?.get("error")?.jsonPrimitive?.contentOrNull()
                    errDesc = root?.get("error_description")?.jsonPrimitive?.contentOrNull()
                } catch (_: Exception) {}
                return@withContext ExchangeResult(null, err, errDesc, text)
            }
            val root = json.parseToJsonElement(text) as? JsonObject ?: return@withContext ExchangeResult(null, "invalid_json", text, text)
            val access = root["access_token"]?.jsonPrimitive?.contentOrNull() ?: return@withContext ExchangeResult(null, root["error"]?.jsonPrimitive?.contentOrNull() ?: "no_access_token", root["error_description"]?.jsonPrimitive?.contentOrNull() ?: text, text)
            val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
                ?: root["expires_in"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 3600L
            ExchangeResult(Tokens(access, root["refresh_token"]?.jsonPrimitive?.contentOrNull(), System.currentTimeMillis() + expiresIn * 1000, root["id_token"]?.jsonPrimitive?.contentOrNull()?.let { emailFromIdToken(it) }), null, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "exchange exception", e)
            ExchangeResult(null, e.javaClass.simpleName, e.message, null)
        }
    }

    private fun emailFromIdToken(idToken: String): String? = try {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val obj = json.parseToJsonElement(decoded) as? JsonObject ?: return null
        (obj["email"] ?: obj["preferred_username"] ?: obj["upn"] ?: obj["unique_name"])?.jsonPrimitive?.contentOrNull()
    } catch (e: Exception) {
        Log.e(TAG, "id_token decode", e)
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()?.ifBlank { null }

    private fun extractQueryParam(rawUrl: String, key: String): String? = try {
        val qIdx = rawUrl.indexOf('?'); if (qIdx == -1) return null
        val hIdx = rawUrl.indexOf('#', qIdx)
        val query = if (hIdx == -1) rawUrl.substring(qIdx + 1) else rawUrl.substring(qIdx + 1, hIdx)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq != -1) {
                val k = Uri.decode(pair.substring(0, eq))
                if (k == key) return Uri.decode(pair.substring(eq + 1))
            }
        }
        null
    } catch (_: Exception) { null }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
