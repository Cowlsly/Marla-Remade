package com.vayunmathur.backup.crypto

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Round-trip, multi-segment, and wrong-key/tamper checks for [Crypto]. */
class CryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun roundTripSmallPayload() {
        val crypto = Crypto(key)
        val plain = "hello encrypted backup".toByteArray()
        assertContentEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun roundTripEmptyPayload() {
        val crypto = Crypto(key)
        assertContentEquals(ByteArray(0), crypto.decrypt(crypto.encrypt(ByteArray(0))))
    }

    @Test
    fun roundTripMultiSegmentPayload() {
        val crypto = Crypto(key)
        // Larger than one segment to exercise the framing + index sequencing.
        val plain = ByteArray(Crypto.SEGMENT_SIZE * 2 + 1234) { (it * 31).toByte() }
        assertContentEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun ciphertextDiffersFromPlaintext() {
        val crypto = Crypto(key)
        val plain = ByteArray(1000) { 7 }
        val blob = crypto.encrypt(plain)
        assertFalse(blob.copyOfRange(1, 1 + plain.size).contentEquals(plain))
    }

    @Test
    fun wrongKeyFailsToDecrypt() {
        val blob = Crypto(key).encrypt("secret".toByteArray())
        assertFailsWith<AEADBadTagException> { Crypto(otherKey).decrypt(blob) }
    }

    @Test
    fun tamperedSegmentFailsToDecrypt() {
        val crypto = Crypto(key)
        val blob = crypto.encrypt("secret payload".toByteArray())
        // Flip a byte inside the first segment's ciphertext (past version + length header).
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<AEADBadTagException> { crypto.decrypt(blob) }
    }

    @Test
    fun versionByteIsWritten() {
        val blob = Crypto(key).encrypt("x".toByteArray())
        assertEquals(Crypto.VERSION, blob[0].toInt())
    }

    @Test
    fun rejectsNon32ByteKey() {
        assertFailsWith<IllegalArgumentException> { Crypto(ByteArray(16)) }
    }
}
