package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import java.security.KeyPair
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handshake tests for [NoiseResponder] + [P256] + [Crypter]. The in-test initiator mirrors
 * Chromium `HandshakeInitiator` (KNpsk0 / QR variant) from `//device/fido/cable/v2_handshake.cc`,
 * so a successful handshake + transport round-trip exercises the exact protocol Chromium speaks.
 */
class NoiseTest {

    @Test fun p256CompressDecompressRoundTrip() {
        val kp = P256.generateKeyPair()
        val compressed = P256.toCompressed(kp.public)
        assertEquals(P256.COMPRESSED_SIZE, compressed.size)
        assertContentEquals(P256.toUncompressed(kp.public), P256.toUncompressed(P256.decodePoint(compressed)))
    }

    @Test fun p256EcdhIsSymmetric() {
        val a = P256.generateKeyPair()
        val b = P256.generateKeyPair()
        assertContentEquals(P256.ecdh(a.private, b.public), P256.ecdh(b.private, a.public))
    }

    @Test fun crypterRoundTripAndPadding() {
        val readKey = ByteArray(32) { it.toByte() }
        val writeKey = ByteArray(32) { (it + 1).toByte() }
        // A pair of counterpart Crypters (keys swapped) model the two ends.
        val a = Crypter(readKey, writeKey)
        val b = Crypter(writeKey, readKey)
        for (i in 0 until 4) {
            val msg = "msg-$i".toByteArray()
            assertContentEquals(msg, b.decrypt(a.encrypt(msg)))
            assertContentEquals(msg, a.decrypt(b.encrypt(msg)))
        }
    }

    @Test fun padUnpadRoundTrip() {
        for (len in 0..40) {
            val msg = ByteArray(len) { it.toByte() }
            val padded = Crypter.pad(msg)
            assertEquals(0, padded.size % 32)
            assertContentEquals(msg, Crypter.unpad(padded))
        }
    }

    @Test fun fullHandshakeAndTransport() {
        val initiatorStatic = P256.generateKeyPair()
        val qrPeerKey = P256.decodePoint(P256.toCompressed(initiatorStatic.public))
        val psk = ByteArray(32) { (it * 7 + 1).toByte() }

        val responder = NoiseResponder(qrPeerKey, psk)
        val initiator = TestInitiator(initiatorStatic, psk)

        responder.readMessage1(initiator.buildInitialMessage())
        val (msg2, respCrypter) = responder.writeMessage2()
        val initCrypter = initiator.processResponse(msg2)

        // initiator -> responder
        val req = "ctap request".toByteArray()
        assertContentEquals(req, respCrypter.decrypt(initCrypter.encrypt(req)))
        // responder -> initiator
        val resp = "ctap response".toByteArray()
        assertContentEquals(resp, initCrypter.decrypt(respCrypter.encrypt(resp)))
    }

    /** KNpsk0 initiator mirroring Chromium HandshakeInitiator (QR variant). */
    private class TestInitiator(private val staticKey: KeyPair, psk: ByteArray) {
        private var ck: ByteArray
        private var h: ByteArray
        private var key: ByteArray? = null
        private var nonce = 0
        private val ephemeral = P256.generateKeyPair()

        init {
            val name = NoiseResponder.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            h = name + ByteArray(32 - name.size)
            ck = h.copyOf()
            mixHash(byteArrayOf(1))                          // prologue
            mixHash(P256.toUncompressed(staticKey.public))   // MixHashPoint(local identity)
            mixKeyAndHash(psk)
        }

        fun buildInitialMessage(): ByteArray {
            val ePub = P256.toUncompressed(ephemeral.public)
            mixHash(ePub); mixKey(ePub)
            // peer_identity_ is null for the QR initiator, so no `es` here.
            return ePub + encryptAndHash(ByteArray(0))
        }

        fun processResponse(message: ByteArray): Crypter {
            val peerPub = message.copyOfRange(0, P256.UNCOMPRESSED_SIZE)
            val peer = P256.decodePoint(peerPub)
            mixHash(peerPub); mixKey(peerPub)
            mixKey(P256.ecdh(ephemeral.private, peer))          // ee
            mixKey(P256.ecdh(staticKey.private, peer))          // se
            val payload = decryptAndHash(message.copyOfRange(P256.UNCOMPRESSED_SIZE, message.size))
            require(payload.isEmpty())
            val (writeKey, readKey) = hkdf2(ck, ByteArray(0))   // note: initiator swaps
            return Crypter(readKey, writeKey)
        }

        private fun mixHash(data: ByteArray) { h = sha256(h + data) }
        private fun mixKey(ikm: ByteArray) { val (c, k) = hkdf2(ck, ikm); ck = c; key = k; nonce = 0 }
        private fun mixKeyAndHash(ikm: ByteArray) {
            val (c, hh, k) = hkdf3(ck, ikm); ck = c; mixHash(hh); key = k; nonce = 0
        }
        private fun encryptAndHash(pt: ByteArray): ByteArray {
            val k = key ?: return pt.also { mixHash(it) }
            return aead(Cipher.ENCRYPT_MODE, k, pt).also { mixHash(it) }
        }
        private fun decryptAndHash(ct: ByteArray): ByteArray {
            val k = key ?: return ct.also { mixHash(it) }
            return aead(Cipher.DECRYPT_MODE, k, ct).also { mixHash(ct) }
        }
        private fun aead(mode: Int, k: ByteArray, input: ByteArray): ByteArray {
            val n = ByteArray(12)
            n[0] = ((nonce ushr 24) and 0xFF).toByte(); n[1] = ((nonce ushr 16) and 0xFF).toByte()
            n[2] = ((nonce ushr 8) and 0xFF).toByte(); n[3] = (nonce and 0xFF).toByte()
            nonce++
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(k, "AES"), GCMParameterSpec(128, n)); updateAAD(h); doFinal(input)
            }
        }
    }

    companion object {
        private fun sha256(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d)
        private fun hmac(key: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(d)
        }
        private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tk = hmac(ck, ikm); val o1 = hmac(tk, byteArrayOf(1)); val o2 = hmac(tk, o1 + 2)
            return o1 to o2
        }
        private fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
            val tk = hmac(ck, ikm); val o1 = hmac(tk, byteArrayOf(1))
            val o2 = hmac(tk, o1 + 2); val o3 = hmac(tk, o2 + 3)
            return Triple(o1, o2, o3)
        }
    }
}
