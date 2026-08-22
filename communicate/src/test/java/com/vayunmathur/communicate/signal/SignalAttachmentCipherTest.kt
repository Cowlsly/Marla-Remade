package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalAttachmentCipher
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip and tamper vectors for attachment encryption. The blob layout is
 * `IV(16) || AES-256-CBC(plaintext) || HMAC-SHA256(IV || ciphertext)(32)`, the key is 32 bytes of AES
 * followed by 32 of HMAC, and the digest is SHA-256 over the whole blob.
 */
class SignalAttachmentCipherTest {
    private val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()

    @Test
    fun roundTrips() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        val out = SignalAttachmentCipher.decrypt(enc.blob, enc.key, enc.digest, enc.plaintextSize)
        assertContentEquals(plaintext, out)
    }

    @Test
    fun roundTripsAcrossSizesIncludingBlockBoundaries() {
        for (size in intArrayOf(0, 1, 15, 16, 17, 31, 32, 33, 1000)) {
            val body = ByteArray(size) { (it % 251).toByte() }
            val enc = SignalAttachmentCipher.encrypt(body)
            val out = SignalAttachmentCipher.decrypt(enc.blob, enc.key, enc.digest, enc.plaintextSize)
            assertContentEquals(body, out, "round-trip failed at size=$size")
        }
    }

    @Test
    fun keyIsSixtyFourBytesAndDigestIsThirtyTwo() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        assertEquals(64, enc.key.size)
        assertEquals(32, enc.digest.size)
        assertEquals(plaintext.size, enc.plaintextSize)
    }

    @Test
    fun blobLayoutIsIvThenCiphertextThenMac() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        // CBC pads to a block, so the ciphertext is at least the plaintext rounded up.
        val expectedCiphertext = ((plaintext.size / 16) + 1) * 16
        assertEquals(16 + expectedCiphertext + 32, enc.blob.size)
        // The digest covers the entire blob, MAC included.
        val digest = MessageDigest.getInstance("SHA-256").digest(enc.blob)
        assertContentEquals(digest, enc.digest)
    }

    @Test
    fun tamperedCiphertextFailsTheMac() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        val tampered = enc.blob.copyOf().also { it[20] = (it[20].toInt() xor 0xFF).toByte() }
        assertFailsWith<SignalAttachmentCipher.MacMismatchException> {
            // No digest, so the MAC is what has to catch it.
            SignalAttachmentCipher.decrypt(tampered, enc.key, digest = null)
        }
    }

    @Test
    fun tamperedBlobFailsTheDigestBeforeTheMac() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        val tampered = enc.blob.copyOf().also { it[20] = (it[20].toInt() xor 0xFF).toByte() }
        assertFailsWith<SignalAttachmentCipher.DigestMismatchException> {
            SignalAttachmentCipher.decrypt(tampered, enc.key, enc.digest)
        }
    }

    @Test
    fun wrongKeyIsRejected() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        val wrong = SignalAttachmentCipher.generateKey()
        assertFailsWith<SignalAttachmentCipher.MacMismatchException> {
            SignalAttachmentCipher.decrypt(enc.blob, wrong, digest = null)
        }
    }

    @Test
    fun swappingKeyHalvesIsRejected() {
        // Catches getting the AES/HMAC halves the wrong way round.
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        val swapped = enc.key.copyOfRange(32, 64) + enc.key.copyOfRange(0, 32)
        assertFailsWith<SignalAttachmentCipher.MacMismatchException> {
            SignalAttachmentCipher.decrypt(enc.blob, swapped, digest = null)
        }
    }

    @Test
    fun rejectsKeysAndBlobsOfTheWrongSize() {
        val enc = SignalAttachmentCipher.encrypt(plaintext)
        assertFailsWith<IllegalArgumentException> {
            SignalAttachmentCipher.decrypt(enc.blob, ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            SignalAttachmentCipher.decrypt(ByteArray(40), enc.key)
        }
        assertFailsWith<IllegalArgumentException> {
            SignalAttachmentCipher.encrypt(plaintext, ByteArray(32))
        }
    }

    @Test
    fun eachEncryptionUsesAFreshIv() {
        val key = SignalAttachmentCipher.generateKey()
        val a = SignalAttachmentCipher.encrypt(plaintext, key)
        val b = SignalAttachmentCipher.encrypt(plaintext, key)
        assertFalse(a.blob.copyOfRange(0, 16).contentEquals(b.blob.copyOfRange(0, 16)))
        // Same key and plaintext must still not produce the same blob.
        assertFalse(a.blob.contentEquals(b.blob))
        assertTrue(a.key.contentEquals(b.key))
    }
}
