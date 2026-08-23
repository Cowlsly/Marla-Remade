package com.vayunmathur.communicate.data.signal.e2e

import android.util.Log

/**
 * JNI bridge to the Rust Signal crate + libsignal-android Java layer.
 *
 * The Signal protocol uses PQXDH (Kyber1024 + X3DH) and sealed sender.
 * For the Kyber/Double Ratchet wire, we delegate to libsignal-android's
 * Java API where possible (SessionBuilder, SessionCipher, GroupCipher,
 * SealedSessionCipher). A small Rust fallback (communicate_signal crate)
 * handles any gaps so the build stays compilable without extra Gradle deps.
 *
 * CIPHERTEXT_MESSAGE_CURRENT_VERSION = 4 (PQXDH); HKDF label
 * "WhisperText_X25519_SHA-256_CRYSTALS-KYBER-1024" - see pqxdh.rs.
 */
object RustSignalCrypto {

    private const val TAG = "RustSignalCrypto"

    val isAvailable: Boolean = try {
        System.loadLibrary("communicate_signal")
        Log.i(TAG, "libcommunicate_signal loaded (Signal)")
        true
    } catch (t: Throwable) {
        if (t.message?.contains("already loaded", ignoreCase = true) == true) {
            Log.i(TAG, "libcommunicate_signal already loaded")
            true
        } else {
            Log.e(TAG, "System.loadLibrary(communicate_signal) failed", t)
            false
        }
    }

    // -- Primitive key operations (reused from crypto.rs) --

    @JvmStatic external fun generateKeyPair(): ByteArray?
    @JvmStatic external fun publicFromPrivate(privateKey: ByteArray): ByteArray?
    @JvmStatic external fun x25519Agreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray?
    @JvmStatic external fun sign(privateKey: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    // -- Session (PQXDH wire; version 4; Kyber fields) --

    /**
     * Process a PreKeyBundle that includes Kyber prekey.
     * Wire: PreKeySignalMessage fields 7 (kyber_pre_key_id) + 8 (kyber_ciphertext 1568B)
     * must be present together for version >= 4.
     * HKDF label: "WhisperText_X25519_SHA-256_CRYSTALS-KYBER-1024" (pqxdh.rs:74)
     */
    @JvmStatic external fun processPreKeyBundle(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        localRegistrationId: Int,
        registrationId: Int,
        preKeyId: Int,
        preKeyPublic: ByteArray?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
        // PQXDH additions:
        kyberPreKeyId: Int,
        kyberPreKeyPublic: ByteArray,     // 1569B = 0x08 || 1568 (tag intact)
        kyberPreKeySignature: ByteArray,  // XEdDSA over kyber pub serialize
        kyberCiphertext: ByteArray,       // 1568B raw from encaps (server-provided or locally generated; 0x08 tag handled in Rust libsignal path; for stub keep as raw)
    ): ByteArray?

    /** Legacy overload without Kyber for incremental migration; delegates to above with empty Kyber. */
    @JvmStatic @Deprecated("Use Kyber overload")
    external fun processPreKeyBundleLegacy(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        localRegistrationId: Int,
        registrationId: Int,
        preKeyId: Int,
        preKeyPublic: ByteArray?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
    ): ByteArray?

    @JvmStatic external fun encrypt(sessionBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptMessage(sessionBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptPreKeyMessage(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        kyberSecretKey: ByteArray?,  // 3169B or 3168B serialized KEM secret (with 0x08 tag if present)
        preKeyMessageBytes: ByteArray,
    ): Array<ByteArray>?

    /**
     * Replay guard for Kyber last-resort prekeys (storage/inmem.rs:200).
     * One-time keys -> deleted after first use; last-resort keys -> (kyberId, signedEcId, baseKey) tuple dedup.
     */
    @JvmStatic external fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ByteArray,
    ): Boolean

    // -- Group (Sender Keys) --

    @JvmStatic external fun createSenderKey(): Array<ByteArray>?
    @JvmStatic external fun processSenderKey(skdmBytes: ByteArray): ByteArray?
    @JvmStatic external fun encryptGroup(stateBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    @JvmStatic external fun decryptGroup(stateBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    // -- Sealed sender (delegated to libsignal SealedSessionCipher; see SignalE2E.sealedSender*) --
    // Kept for Kotlin callers that still go via JNI; the primary path in SignalE2E uses
    // org.signal.libsignal.metadata.SealedSessionCipher directly with SenderCertificate.

    /**
     * Sealed-sender encrypt - real implementation requires a SenderCertificate
     * fetched live from GET /v1/certificate/delivery and validated via CertificateValidator.
     * This JNI stub is retained for tests; production calls SealedSessionCipher.encrypt().
     */
    @JvmStatic external fun sealedSenderEncrypt(plaintext: ByteArray, recipientAci: String, recipientDeviceId: Int): ByteArray?
    @JvmStatic external fun sealedSenderDecrypt(ciphertext: ByteArray): ByteArray?

    // -- Convenience helpers --

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPairSplit(): KeyPair {
        val blob = generateKeyPair() ?: throw RuntimeException("Rust generateKeyPair returned null")
        if (blob.size != 64) throw RuntimeException("generateKeyPair expected 64 bytes, got ${blob.size}")
        return KeyPair(blob.copyOfRange(0, 32), blob.copyOfRange(32, 64))
    }

    data class EncryptResult(val isPreKey: Boolean, val body: ByteArray, val newSession: ByteArray)
    fun encryptSplit(sessionBytes: ByteArray, plaintext: ByteArray): EncryptResult {
        val out = encrypt(sessionBytes, plaintext) ?: throw RuntimeException("Rust encrypt returned null")
        if (out.size != 3) throw RuntimeException("encrypt expected 3 parts, got ${out.size}")
        return EncryptResult(out[0].isNotEmpty() && out[0][0].toInt() != 0, out[1], out[2])
    }

    data class DecryptResult(val plaintext: ByteArray, val newSession: ByteArray)
    fun decryptMessageSplit(sessionBytes: ByteArray, ciphertext: ByteArray): DecryptResult {
        val out = decryptMessage(sessionBytes, ciphertext) ?: throw RuntimeException("Rust decryptMessage null")
        if (out.size != 2) throw RuntimeException("decryptMessage expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    fun decryptPreKeySplit(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        kyberSecretKey: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): DecryptResult {
        val out = decryptPreKeyMessage(localIdentityPrivate, localIdentityPublic, signedPreKeyPrivate, oneTimePrivate, kyberSecretKey, preKeyMessageBytes)
            ?: throw RuntimeException("Rust decryptPreKeyMessage null")
        if (out.size != 2) throw RuntimeException("decryptPreKey expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    // The sender-key wrappers that used to live here are gone: group traffic now goes through libsignal's
    // GroupCipher/GroupSessionBuilder, which is the only format real Signal clients interoperate with, and
    // which SealedSessionCipher already dispatches inbound SENDERKEY_TYPE through.
}
