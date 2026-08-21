package com.vayunmathur.cast.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {

    private val key = "000102030405060708090a0b0c0d0e0f".hex()
    private val mask = "101112131415161718191a1b1c1d1e1f".hex()

    @Test
    fun `the iv is the frame id big-endian at offset eight, xored with the mask`() {
        // Hand-computed from FrameCrypto::Crypt: a zeroed block, the frame id's lower 32 bits
        // written big-endian at byte 8, then the whole block xored with the mask. Frame 1 differs
        // from the mask in exactly byte 11.
        val crypto = Crypto(key, mask)
        assertContentEquals(mask, crypto.ivForFrame(FrameId.First))
        assertContentEquals(
            "101112131415161718191a1a1c1d1e1f".hex(),
            crypto.ivForFrame(FrameId(1)),
        )
        // 0x01020304 lands across bytes 8..11: 01^18=19, 02^19=1b, 03^1a=19, 04^1b=1f.
        assertContentEquals(
            "1011121314151617191b191f1c1d1e1f".hex(),
            crypto.ivForFrame(FrameId(0x01020304)),
        )
    }

    @Test
    fun `the iv uses the lower 32 bits, not the 8 bits that go on the wire`() {
        // The trap this file exists to catch. Frame 0 and frame 256 share a wire frame id, so an
        // implementation that derived the IV from the truncated value would reuse a keystream -
        // which with CTR leaks the xor of two frames outright.
        val crypto = Crypto(key, mask)
        assertEquals(FrameId.First.lower8, FrameId(256).lower8)
        assertFalse(
            crypto.ivForFrame(FrameId.First).contentEquals(crypto.ivForFrame(FrameId(256))),
        )
    }

    @Test
    fun `ctr matches a keystream built independently from aes-ecb`() {
        // Not a round-trip test: a wrong-but-symmetric implementation passes those. This builds
        // counter mode by hand out of the AES block cipher - incrementing the 16-byte counter and
        // xoring - and requires the Cipher-based path to agree byte for byte.
        val crypto = Crypto(key, mask)
        val frameId = FrameId(0x0000_1234)
        val plaintext = ByteArray(70) { it.toByte() }

        val actual = crypto.crypt(frameId, plaintext)
        val expected = referenceCtr(key, crypto.ivForFrame(frameId), plaintext)

        assertContentEquals(expected, actual)
        // And it is genuinely encrypting, not passing bytes through.
        assertFalse(plaintext.contentEquals(actual))
    }

    @Test
    fun `ctr is its own inverse`() {
        val crypto = Crypto(key, mask)
        val plaintext = "The quick brown fox jumps over the lazy dog.".toByteArray()
        val encrypted = crypto.crypt(FrameId(9), plaintext)
        assertContentEquals(plaintext, crypto.crypt(FrameId(9), encrypted))
    }

    @Test
    fun `two frames with the same payload encrypt differently`() {
        val crypto = Crypto(key, mask)
        val payload = ByteArray(32) { 7 }
        assertFalse(
            crypto.crypt(FrameId.First, payload).contentEquals(crypto.crypt(FrameId(1), payload)),
        )
    }

    @Test
    fun `a key of the wrong length is refused rather than silently truncated`() {
        // AES-256 keys are what the rest of the repo uses, so handing one over is a live mistake.
        assertFailsWith<IllegalArgumentException> { Crypto(ByteArray(32), mask) }
        assertFailsWith<IllegalArgumentException> { Crypto(key, ByteArray(12)) }
    }

    @Test
    fun `an empty payload encrypts to nothing rather than throwing`() {
        // Some audio codecs emit zero bytes for silence, and the packetizer still sends a packet.
        assertTrue(Crypto(key, mask).crypt(FrameId(3), ByteArray(0)).isEmpty())
    }

    /** Counter mode assembled from the raw block cipher, as an independent oracle. */
    private fun referenceCtr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(input.size)
        val counter = iv.copyOf()
        var offset = 0
        while (offset < input.size) {
            val block = ecb.doFinal(counter)
            val n = minOf(16, input.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += n
            // Increment the whole 128-bit counter, big-endian, as AES_ctr128_encrypt does.
            for (i in 15 downTo 0) {
                counter[i] = (counter[i] + 1).toByte()
                if (counter[i] != 0.toByte()) break
            }
        }
        return out
    }
}

internal fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
