package com.vayunmathur.passwords.cable

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Post-handshake transport encryption for caBLE v2, matching Chromium `cablev2::Crypter`
 * (`//device/fido/cable/v2_handshake.cc`).
 *
 * Each direction has its own 32-byte key and a 32-bit sequence counter. The AES-256-GCM nonce is
 * `8 zero bytes || big-endian uint32(sequence)` (note: this differs from the Noise *handshake*
 * nonce, which puts the counter in the first 4 bytes). No associated data is used. Before
 * encryption the plaintext is padded to a multiple of 32 bytes: zero bytes are appended and the
 * final byte records how many zeros were added.
 *
 * For the responder (this app): `readKey` = first traffic key, `writeKey` = second.
 */
class Crypter(private val readKey: ByteArray, private val writeKey: ByteArray) {
    private var readSeq = 0
    private var writeSeq = 0

    fun encrypt(plaintext: ByteArray): ByteArray {
        val padded = pad(plaintext)
        val nonce = nonce(writeSeq++)
        return gcm(Cipher.ENCRYPT_MODE, writeKey, nonce).doFinal(padded)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val nonce = nonce(readSeq++)
        val padded = gcm(Cipher.DECRYPT_MODE, readKey, nonce).doFinal(ciphertext)
        return unpad(padded)
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }

    companion object {
        private const val PADDING_GRANULARITY = 32

        /** `8 zero bytes || big-endian uint32(sequence)`. */
        fun nonce(sequence: Int): ByteArray {
            val n = ByteArray(12)
            n[8] = ((sequence ushr 24) and 0xFF).toByte()
            n[9] = ((sequence ushr 16) and 0xFF).toByte()
            n[10] = ((sequence ushr 8) and 0xFF).toByte()
            n[11] = (sequence and 0xFF).toByte()
            return n
        }

        /** Appends zeros to reach a multiple of 32; the final byte is the number of zeros added. */
        fun pad(message: ByteArray): ByteArray {
            val paddedSize = ((message.size + 1 + PADDING_GRANULARITY - 1) / PADDING_GRANULARITY) * PADDING_GRANULARITY
            val numZeros = paddedSize - message.size - 1
            val out = ByteArray(paddedSize)
            System.arraycopy(message, 0, out, 0, message.size)
            out[paddedSize - 1] = numZeros.toByte()
            return out
        }

        /** Strips the padding written by [pad]. */
        fun unpad(padded: ByteArray): ByteArray {
            require(padded.isNotEmpty()) { "empty caBLE message" }
            val paddingLength = padded[padded.size - 1].toInt() and 0xFF
            require(paddingLength + 1 <= padded.size) { "invalid caBLE padding" }
            return padded.copyOfRange(0, padded.size - paddingLength - 1)
        }
    }
}
