package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.signal.libsignal.metadata.certificate.SenderCertificate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Sealed sender support: the delivery certificate that proves who we are to the *recipient* without
 * telling the server, and the unidentified-access key that authorises an unauthenticated send.
 *
 * The certificate comes from `GET /v1/certificate/delivery` and is cached until shortly before it
 * expires. The access key is derived from the recipient's profile key.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalSealedSender {
    private const val TAG = "SignalSealedSender"

    /** Refresh this far ahead of expiry so a send never races the rotation. */
    private const val EXPIRY_BUFFER_MS = 24 * 60 * 60 * 1000L

    /** The header that carries the access key on an unauthenticated send. */
    const val ACCESS_KEY_HEADER = "Unidentified-Access-Key"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The cached certificate, refetched when absent or within [EXPIRY_BUFFER_MS] of expiry. Returns
     * null when it cannot be obtained, which means "send identified instead".
     */
    suspend fun senderCertificate(
        db: SignalDatabase,
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
        includeE164: Boolean = false,
    ): SenderCertificate? {
        val cached = try { db.senderCertificateDao().get() } catch (_: Exception) { null }
        val now = System.currentTimeMillis()
        if (cached != null && cached.includesE164 == includeE164 && cached.expiration - EXPIRY_BUFFER_MS > now) {
            val parsed = try { SenderCertificate(cached.record) } catch (_: Exception) { null }
            if (parsed != null) return parsed
        }

        val fetched = fetchCertificate(authHeader, sslSocketFactory, includeE164) ?: return null
        try {
            db.senderCertificateDao().upsert(
                SignalSenderCertificate(
                    record = fetched.serialized,
                    expiration = fetched.expiration,
                    // Record what the certificate actually carries, not what we asked for, so the
                    // freshness check above can't treat a mismatched certificate as a cache hit.
                    includesE164 = fetched.senderE164.isPresent,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not cache the sender certificate", t)
        }
        return fetched
    }

    private suspend fun fetchCertificate(
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
        includeE164: Boolean,
    ): SenderCertificate? {
        // Two variants; the no-E164 one is what you want unless the user shares their phone number.
        val path = if (includeE164) "/v1/certificate/delivery" else "/v1/certificate/delivery?includeE164=false"
        val resp = try {
            NetworkClient.execute(
                "https://chat.signal.org$path",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "delivery certificate fetch failed", t)
            return null
        }
        if (!resp.isSuccess) {
            Log.w(TAG, "delivery certificate fetch failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return parseCertificate(resp.text)
    }

    /** Response is `{"certificate":"<base64>"}`. [warn] is injectable so this stays testable off-device. */
    internal fun parseCertificate(
        body: String,
        warn: (String) -> Unit = { Log.w(TAG, it) },
    ): SenderCertificate? {
        val encoded = try {
            json.parseToJsonElement(body).jsonObject["certificate"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        if (encoded.isNullOrEmpty()) {
            warn("delivery certificate response had no certificate field")
            return null
        }
        return try {
            SenderCertificate(decodeBase64(encoded))
        } catch (t: Throwable) {
            warn("delivery certificate did not parse: ${t.message}")
            null
        }
    }

    /**
     * The recipient's unidentified-access key, or null when we do not hold their profile key and so
     * must send identified.
     */
    suspend fun accessKeyFor(db: SignalDatabase, aci: String): ByteArray? {
        val stored = try { db.profileKeyDao().get(aci) } catch (_: Exception) { null } ?: return null
        return deriveAccessKey(stored.profileKey)
    }

    /** Remember a profile key learned from an inbound message. */
    suspend fun rememberProfileKey(db: SignalDatabase, aci: String, profileKey: ByteArray) {
        if (profileKey.size != PROFILE_KEY_SIZE) {
            Log.w(TAG, "ignoring a ${profileKey.size}-byte profile key for $aci")
            return
        }
        try {
            db.profileKeyDao().upsert(SignalProfileKey(address = aci, profileKey = profileKey))
        } catch (t: Throwable) {
            Log.w(TAG, "could not store the profile key for $aci", t)
        }
    }

    private const val PROFILE_KEY_SIZE = 32

    /**
     * AES-256-GCM of 16 zero bytes under the profile key with a 12-byte zero nonce, truncated to the
     * first 16 bytes. Deliberately not an HKDF — this matches the official derivation exactly.
     */
    fun deriveAccessKey(profileKey: ByteArray): ByteArray? {
        if (profileKey.size != PROFILE_KEY_SIZE) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(profileKey, "AES"),
                GCMParameterSpec(128, ByteArray(12)),
            )
            cipher.doFinal(ByteArray(16)).copyOf(16)
        } catch (t: Throwable) {
            Log.w(TAG, "access key derivation failed", t)
            null
        }
    }

    fun accessKeyHeader(accessKey: ByteArray): String =
        "$ACCESS_KEY_HEADER:${Base64.Default.encode(accessKey)}"

    private fun decodeBase64(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return Base64.Default.decode(padded)
    }
}
