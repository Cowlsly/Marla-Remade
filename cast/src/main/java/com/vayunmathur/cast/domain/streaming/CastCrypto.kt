package com.vayunmathur.cast.domain.streaming

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cast Streaming payload encryption: AES-128-CTR with a per-frame IV.
 *
 * From openscreen `cast/streaming/impl/frame_crypto.cc` (`FrameCrypto::Crypt`). The IV is *not*
 * random and *not* transmitted - both ends derive it from the frame id and the `aesIvMask` agreed
 * in the OFFER, which is why a wrong derivation shows up as a picture of pure noise rather than as
 * an error.
 *
 * **Deliberately not routed through `library/e2ee-p2p`'s `E2ee.kt`.** That is AES-256-GCM with a
 * random IV prepended to the ciphertext: authenticated, larger key, self-describing nonce. Every
 * one of those properties is wrong here - this needs unauthenticated AES-128-CTR with a *derived*
 * IV and no framing overhead at all, because the receiver's parser expects the payload bytes to be
 * exactly the ciphertext. They are different contracts that happen to share the letters "AES", so
 * consolidating them would break the wire format.
 *
 * Note this means the stream is unauthenticated. That is the protocol's choice, not ours; there is
 * no way to interoperate and also add a MAC.
 */
class CastCrypto(key: ByteArray, private val ivMask: ByteArray) {

    init {
        require(key.size == 16) { "Cast Streaming uses AES-128; got a ${key.size}-byte key" }
        require(ivMask.size == 16) { "the IV mask is one AES block; got ${ivMask.size} bytes" }
    }

    private val keySpec = SecretKeySpec(key, "AES")

    /**
     * The counter block for [frameId].
     *
     * Sixteen zero bytes, the frame id's lower 32 bits written big-endian at offset 8, then the
     * whole thing XORed with the mask. The offset is what makes the counter increment within a
     * frame without colliding with the next frame's blocks.
     */
    fun ivForFrame(frameId: FrameId): ByteArray {
        val iv = ByteArray(16)
        val lower32 = frameId.lower32
        iv[8] = (lower32 ushr 24).toByte()
        iv[9] = (lower32 ushr 16).toByte()
        iv[10] = (lower32 ushr 8).toByte()
        iv[11] = lower32.toByte()
        for (i in iv.indices) iv[i] = (iv[i].toInt() xor ivMask[i].toInt()).toByte()
        return iv
    }

    /**
     * Encrypt one frame in place of a copy.
     *
     * CTR is its own inverse, so this is also the decrypt path - which is only used by tests, the
     * sender never decrypts.
     */
    fun crypt(frameId: FrameId, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivForFrame(frameId)))
        return cipher.doFinal(payload)
    }
}
