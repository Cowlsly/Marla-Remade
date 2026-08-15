package com.vayunmathur.passwords.cable

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * caBLE v2 key derivations from the 16-byte QR secret.
 *
 * Mirrors Chromium's `device::cablev2::Derive` (`//device/fido/cable/v2_handshake.cc`):
 * HKDF-SHA256 with `IKM = secret`, `salt = nonce` (empty for the QR-derived keys), and
 * `info = little-endian uint32(purpose)`.
 *
 * NOTE: the numeric [Purpose] values, output lengths, and the EID layout are byte-exact protocol
 * constants copied from Chromium. They must be validated against Chromium/CTAP test vectors before
 * this is relied upon (see the plan's verification section) — a mismatch surfaces only as the
 * generic "make sure Bluetooth is on" browser error.
 */
object CableKeys {

    /** `DerivedValueType` enum values from Chromium. */
    enum class Purpose(val value: Int) {
        EID_KEY(1),
        TUNNEL_ID(2),
        PSK(3),
        PAIRED_SECRET(4),
        IDENTITY_KEY_SEED(5),
        PER_CONTACT_ID_SECRET(6),
    }

    // Output sizes (bytes).
    const val EID_KEY_SIZE = 64      // AES key + HMAC key material for the BLE EID
    const val TUNNEL_ID_SIZE = 16
    const val PSK_SIZE = 32
    const val NONCE_SIZE = 10        // BLE EID nonce

    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * HKDF-SHA256 (RFC 5869) as used by caBLE, on the platform [Mac] (Conscrypt) —
     * no Bouncy Castle. Empty/`null` salt = RFC 5869 zero salt (BoringSSL parity).
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "HKDF length too large" }
        // Extract.
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = hmac(actualSalt, ikm)
        // Expand.
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

    /** Chromium `Derive<N>(secret, salt, purpose)`. */
    fun derive(secret: ByteArray, salt: ByteArray?, purpose: Purpose, length: Int): ByteArray =
        hkdf(secret, salt, leUint32(purpose.value), length)

    /** Key that encrypts/authenticates the BLE ephemeral ID (EID). */
    fun eidKey(qrSecret: ByteArray): ByteArray =
        derive(qrSecret, null, Purpose.EID_KEY, EID_KEY_SIZE)

    /** Tunnel routing identifier used to pick + address the tunnel server. */
    fun tunnelId(qrSecret: ByteArray): ByteArray =
        derive(qrSecret, null, Purpose.TUNNEL_ID, TUNNEL_ID_SIZE)

    /**
     * Pre-shared key mixed into the Noise KNpsk0 handshake. Per Chromium
     * `v2_authenticator.cc`, the salt is the 16-byte plaintext EID that is BLE-advertised, binding
     * the handshake to the advertised beacon.
     */
    fun psk(qrSecret: ByteArray, plaintextEid: ByteArray): ByteArray =
        derive(qrSecret, plaintextEid, Purpose.PSK, PSK_SIZE)

    /** Encodes an int as a little-endian uint32 (the HKDF `info` field). */
    fun leUint32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )
}
