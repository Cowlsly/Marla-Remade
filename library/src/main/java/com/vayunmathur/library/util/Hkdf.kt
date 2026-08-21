package com.vayunmathur.library.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) over HMAC-SHA256: derive several fixed-purpose sub-keys from one secret so
 * that compromising a key derived for one use says nothing about the others.
 *
 * Shared rather than per-app because two features already need exactly this: `:backup` expands
 * the BIP-0039 seed into the AES-256 master key, and `:cast:protocol` expands the paired session
 * secret into the control-channel and per-stream media keys.
 */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun derive(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(HASH_LEN)): ByteArray {
        val prk = hmac(salt, ikm)
        val n = (length + HASH_LEN - 1) / HASH_LEN
        require(n <= 255) { "HKDF cannot derive more than 255 blocks" }
        val output = ByteArray(n * HASH_LEN)
        var previous = ByteArray(0)
        for (i in 1..n) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            previous = mac.doFinal()
            previous.copyInto(output, (i - 1) * HASH_LEN)
        }
        return output.copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
