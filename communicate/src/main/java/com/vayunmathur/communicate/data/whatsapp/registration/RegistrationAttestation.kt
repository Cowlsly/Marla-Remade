package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reproduces WhatsApp's registration attestation, recovered as pure Java from the pinned APK
 * (see [WhatsAppRegistrationConstants] + communicate/whatsapp-documentation.md).
 *
 * Three layers:
 *  - [computeToken]      → the `token` form field validated by `/v2/code` (gate for `bad_token`).
 *  - [encryptQueryString]→ the optional `ENC` wrapper (X25519 ECDH → AES-256-GCM).
 *  - [signWithAttestation]→ the optional `H` HMAC over the (encrypted) body.
 *
 * The token is the real gate; ENC/H are additional obfuscation layers that the official client
 * itself falls back away from on failure, so [RegistrationHttpClient] defaults to plain form
 * bodies and offers ENC/H as an opt-in.
 */
object RegistrationAttestation {

    private const val TAG = "WARegAttestation"

    // ---------------------------------------------------------------------------------------------
    // token  (C34029EuU.A01)
    // ---------------------------------------------------------------------------------------------

    /**
     * token = Base64 standard NO_WRAP (flag 2, not url-safe) over
     *   HMAC-SHA1(
     *     key = PBKDF2-HMAC-SHA1(password = packageName || about_logo.png,
     *                            salt = ES3, iters = 128, dkLen = 512 bits),
     *     msg = signingCertDer || MD5(classes.dex) || phoneDigits)
     * (APK: AbstractC169977f8.A0n = Base64 flag 2 = NO_WRAP; A0o/flag 11 url-safe is ONLY for
     * expid + e2e bundle.)
     *
     * @param phoneDigits the NATIONAL number only (WhatsApp's token uses `$phoneNumber` = the `in`
     *   field, with country code kept separate), e.g. cc="1", number="5551234567" → pass
     *   "5551234567".
     */
    fun computeToken(context: Context, phoneDigits: String): String {
        val logo = context.assets.open(WhatsAppRegistrationConstants.ASSET_ABOUT_LOGO)
            .use { it.readBytes() }
        val certDer = context.assets.open(WhatsAppRegistrationConstants.ASSET_SIGNING_CERT_DER)
            .use { it.readBytes() }

        val password = WhatsAppRegistrationConstants.PACKAGE_NAME.toByteArray(Charsets.UTF_8) + logo
        val salt = Base64.decode(WhatsAppRegistrationConstants.TOKEN_SALT_B64, Base64.DEFAULT)

        val derivedKey = pbkdf2HmacSha1(
            password = password,
            salt = salt,
            iterations = WhatsAppRegistrationConstants.TOKEN_PBKDF2_ITERATIONS,
            dkLenBytes = WhatsAppRegistrationConstants.TOKEN_PBKDF2_KEYLEN_BITS / 8,
        )

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(derivedKey, "HmacSHA1"))
        mac.update(certDer)
        mac.update(hexToBytes(WhatsAppRegistrationConstants.CLASSES_DEX_MD5_HEX))
        mac.update(phoneDigits.toByteArray(Charsets.UTF_8))
        val tokenBytes = mac.doFinal()

        // Token uses STANDARD base64 with padding (APK: AbstractC169977f8.A0n = Base64 flag 2 =
        // NO_WRAP). NOT url-safe/no-padding (that's A0o/flag 11, used only for expid + the e2e
        // bundle). The '+/=' chars are handled by the form url-encoder at query-build time.
        return Base64.encodeToString(tokenBytes, Base64.NO_WRAP)
    }

    /**
     * Manual PBKDF2-HMAC-SHA1 taking the password as raw bytes. This avoids the char[] encoding
     * ambiguity of the JCE "PBKDF2WithHmacSHA1And8BIT" alias (whose availability + high-byte
     * handling varies across API levels) and is byte-exact against the recovered algorithm.
     */
    private fun pbkdf2HmacSha1(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLenBytes: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val hLen = mac.macLength // 20
        val blocks = (dkLenBytes + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val intBuf = ByteArray(4)
        for (i in 1..blocks) {
            intBuf[0] = (i ushr 24).toByte()
            intBuf[1] = (i ushr 16).toByte()
            intBuf[2] = (i ushr 8).toByte()
            intBuf[3] = i.toByte()
            mac.reset()
            mac.update(salt)
            mac.update(intBuf)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (c in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLenBytes)
    }

    // ---------------------------------------------------------------------------------------------
    // ENC  (RegistrationEncryption/encryptQueryString)
    // ---------------------------------------------------------------------------------------------

    /**
     * ENC = base64( ephemeralX25519Pub(32) || AES-256-GCM(key = X25519(ephPriv, ES4_serverPub),
     *                                                     iv = 12 zero bytes, plaintext = queryString) )
     * IV is 12 zero bytes and the AES key IS the raw ECDH shared secret (no HKDF), per
     * RetryingHttpClient.java:236-281.
     */
    fun encryptQueryString(queryString: String): String? {
        return try {
            val serverPub = hexToBytes(WhatsAppRegistrationConstants.REG_SERVER_X25519_PUBKEY_HEX)
            val eph = RustWhatsAppCrypto.generateKeyPairSplit()
            val shared = RustWhatsAppCrypto.x25519Agreement(eph.privateKey, serverPub)
                ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(shared, "AES"),
                GCMParameterSpec(128, ByteArray(12)),
            )
            val ct = cipher.doFinal(queryString.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(eph.publicKey + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "encryptQueryString failed; falling back to plain", t)
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // H  (RegistrationBodyBuilder/signWithKeyAttestation)
    // ---------------------------------------------------------------------------------------------

    /**
     * H = base64( HMAC-SHA256(key = attestation key, msg = body) ).
     *
     * Prefers the non-exportable AndroidKeyStore HMAC key
     * ([WhatsAppAttestationKeyStore]); falls back to the software [softwareKey]
     * ([WhatsAppDeviceFingerprint.attestationKey]) when the KeyStore is unavailable. The server
     * treats `H` as best-effort (the client warns + continues when it is null).
     */
    fun signWithAttestation(body: String, softwareKey: ByteArray): String {
        val mac = WhatsAppAttestationKeyStore.signWithFallback(body.toByteArray(Charsets.UTF_8), softwareKey)
        return Base64.encodeToString(mac, Base64.NO_WRAP)
    }

    /**
     * H over a raw software key only (no KeyStore). Retained for callers/tests that supply an
     * explicit key; prefer the [signWithAttestation]`(body, softwareKey)` overload in production.
     */
    fun signWithAttestation(attestationKey: ByteArray, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(attestationKey, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------------------------------------

    fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
