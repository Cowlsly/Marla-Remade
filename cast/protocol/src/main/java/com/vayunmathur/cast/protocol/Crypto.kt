package com.vayunmathur.cast.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media payload encryption: AES-128-CTR with a per-frame IV.
 *
 * Kept exactly as it was written against openscreen `cast/streaming/impl/frame_crypto.cc`
 * (`FrameCrypto::Crypt`), because it is golden-byte tested and now has a decrypting half in this
 * same module. The IV is *not* random and *not* transmitted - both ends derive it from the frame id
 * and the mask agreed in the handshake, which is why a wrong derivation shows up as a picture of
 * pure noise rather than as an error.
 *
 * **Deliberately not routed through `library/e2ee-p2p`'s `E2ee.kt`.** That is AES-256-GCM with a
 * random IV prepended to the ciphertext: authenticated, larger key, self-describing nonce. Every
 * one of those properties is wrong here - a derived IV costs no bytes on the wire, and CTR lets a
 * frame be decrypted from its id alone, without the packets having to arrive in order.
 *
 * **This leaves the media stream unauthenticated, and that is a real limitation.** Confidentiality
 * is delivered; integrity is not, so an attacker able to inject UDP could corrupt the picture
 * without being detected. The *control* channel is AES-256-GCM (see [ControlCodec]), so this cannot
 * be used to hijack a session or force a downgrade. Moving to per-packet AEAD is a contained change
 * if that trade stops being acceptable - roughly 16 bytes per packet, about 1% overhead.
 */
class Crypto(key: ByteArray, private val ivMask: ByteArray) {

    init {
        require(key.size == KEY_BYTES) { "AES-128 needs a 16-byte key; got ${key.size}" }
        require(ivMask.size == KEY_BYTES) { "the IV mask is one AES block; got ${ivMask.size}" }
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
        val iv = ByteArray(KEY_BYTES)
        val lower32 = frameId.lower32
        iv[8] = (lower32 ushr 24).toByte()
        iv[9] = (lower32 ushr 16).toByte()
        iv[10] = (lower32 ushr 8).toByte()
        iv[11] = lower32.toByte()
        for (i in iv.indices) iv[i] = (iv[i].toInt() xor ivMask[i].toInt()).toByte()
        return iv
    }

    /**
     * Encrypt or decrypt one frame.
     *
     * CTR is its own inverse, so the sender and the receiver call the identical method - which is
     * also why a round-trip test proving `crypt(crypt(x)) == x` genuinely covers both ends.
     */
    fun crypt(frameId: FrameId, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivForFrame(frameId)))
        return cipher.doFinal(payload)
    }

    private companion object {
        const val KEY_BYTES = 16
    }
}
