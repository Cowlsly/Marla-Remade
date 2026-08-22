package com.vayunmathur.communicate.data.signal.e2e

import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalE2EPreKey
import com.vayunmathur.communicate.data.signal.SignalE2ESenderKey
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * Rust-backed E2E crypto for Signal (PQXDH + Double Ratchet + Sender Keys + Sealed Sender).
 * Delegates PQXDH/Kyber and sealed sender to libsignal-android 0.86.5:
 * - SessionBuilder/SessionCipher with Kyber (PreKeyBundle includes kyber fields)
 * - SealedSessionCipher + SenderCertificate (cert fetched live: GET /v1/certificate/delivery)
 * - CIPHERTEXT_MESSAGE_CURRENT_VERSION=4, HKDF label WhisperText_X25519_SHA-256_CRYSTALS-KYBER-1024
 * - Kyber replay guard via KyberPreKeyStore.markKyberPreKeyUsed
 *
 * Backwards-compatible overloads kept for wiring (SignalClient) callers.
 * ACI/PNI dual identities: storage shape is owned by registration (SignalAuthData);
 * this class currently seeds protocolStore from the ACI identity (identity* / registrationId).
 * Registration teammate should persist both ACI and PNI IdentityKeyPairs + registrationIds
 * (aciIdentity* / pniIdentity* + aciRegistrationId / pniRegistrationId) — see task 4.
 */
@OptIn(ExperimentalEncodingApi::class)
class SignalE2E(
    private val db: SignalDatabase,
    private val auth: SignalAuthData,
    /** See [PersistentSignalProtocolStore.onIdentityChanged]. */
    private val onIdentityChanged: (String, ByteArray) -> Unit = { _, _ -> },
) {
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)
    private val ownAci: String = auth.aci.ifEmpty { auth.phoneNumber }
    private val ownDeviceId: Int = auth.deviceId

    private val protocolStore: PersistentSignalProtocolStore by lazy {
        val ikp = loadIdentityPair(auth.identityPrivateKey, auth.identityPublicKey)
        PersistentSignalProtocolStore(
            db = db,
            identityKeyPair = ikp,
            registrationId = auth.registrationId.takeIf { it != 0 } ?: 1,
            onIdentityChanged = { address, key -> onIdentityChanged(address.name, key.serialize()) },
        )
    }

    private fun loadIdentityPair(privB64: String, pubB64: String): IdentityKeyPair {
        if (privB64.isEmpty() || pubB64.isEmpty()) return IdentityKeyPair.generate()
        return try {
            val identityKey = IdentityKey(b64(pubB64))
            val privKey = ECPrivateKey(b64(privB64))
            IdentityKeyPair(identityKey, privKey)
        } catch (_: Exception) {
            IdentityKeyPair.generate()
        }
    }

    /**
     * Our own address.
     *
     * Careful: libsignal orders these two inconsistently — `SessionCipher(store, local, remote)` but
     * `SessionBuilder(store, remote, local)`. Kotlin cannot use named arguments for Java constructors, so
     * the order at each call site is load-bearing. Getting it wrong looks up the session under our own
     * address and fails with a `NoSessionException` naming us rather than the peer.
     */
    private fun localAddress(): SignalProtocolAddress = signalAddress(ownAci, ownDeviceId)

    fun signalAddress(aci: String, deviceId: Int = 1): SignalProtocolAddress =
        SignalProtocolAddress(aci, deviceId)

    /**
     * Whether a usable session exists. Delegates to the same store [encryptDM] uses, so it cannot
     * report a session that encryption would then fail to find.
     */
    fun hasSession(aci: String, deviceId: Int = 1): Boolean =
        try { protocolStore.containsSession(signalAddress(aci, deviceId)) } catch (_: Exception) { false }

    fun deleteSession(aci: String, deviceId: Int = 1) {
        try { protocolStore.deleteSession(signalAddress(aci, deviceId)) } catch (_: Exception) {}
    }

    /** One encrypted message plus the metadata the send envelope needs alongside it. */
    data class EncResult(
        /** A `CiphertextMessage` type, not an `Envelope` type — the numbering spaces differ. */
        val ciphertextType: Int,
        val remoteRegistrationId: Int,
        val data: ByteArray,
    )

    /**
     * Encrypt for an established session. libsignal owns the session record through [protocolStore];
     * a failure propagates rather than falling back to the Rust crate, which serializes sessions in a
     * different format and would corrupt the stored record.
     */
    fun encryptDM(aci: String, deviceId: Int, paddedPlaintext: ByteArray): EncResult {
        // SessionCipher is (local, remote).
        val cipher = SessionCipher(protocolStore, localAddress(), signalAddress(aci, deviceId))
        val msg = cipher.encrypt(paddedPlaintext)
        return EncResult(
            ciphertextType = msg.type,
            remoteRegistrationId = cipher.remoteRegistrationId,
            data = msg.serialize(),
        )
    }

    /** The identity key currently recorded for [aci], or null if we have never seen one. */
    fun storedIdentityKey(aci: String): ByteArray? =
        try { protocolStore.getIdentity(signalAddress(aci))?.serialize() } catch (_: Exception) { null }

    /**
     * Record [identityKey] as the trusted identity for [aci] and archive existing sessions, so the next
     * message builds a session against the accepted key. Only call this once the user has verified it.
     */
    fun acceptIdentity(aci: String, identityKey: ByteArray): Boolean = try {
        val address = signalAddress(aci)
        protocolStore.saveIdentity(address, IdentityKey(identityKey))
        // Every device's session was built against the old key.
        deviceIdsWithSessions(aci).forEach { archiveSession(aci, it) }
        true
    } catch (t: Throwable) {
        false
    }

    /** Devices of [aci] we already have a session with, always including device 1. */
    fun deviceIdsWithSessions(aci: String): List<Int> {
        val subDevices = try { protocolStore.getSubDeviceSessions(aci) } catch (_: Exception) { emptyList() }
        return (listOf(1) + subDevices).distinct().sorted()
    }

    /**
     * Archive rather than delete: the old chain must stay readable so messages already in flight on it
     * can still be decrypted. Returns whether a stored record was actually changed — no-ops for a
     * device we hold no session for, which would otherwise materialise an empty record that
     * [deviceIdsWithSessions] then reports forever.
     */
    fun archiveSession(aci: String, deviceId: Int): Boolean {
        if (!runBlocking { db.e2eSessionDao().exists(aci, deviceId) }) return false
        return try {
            val address = signalAddress(aci, deviceId)
            val record = protocolStore.loadSession(address)
            record.archiveCurrentState()
            protocolStore.storeSession(address, record)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun decryptDM(aci: String, deviceId: Int, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        val address = signalAddress(aci, deviceId)
        // SessionCipher is (local, remote).
        val cipher = SessionCipher(protocolStore, localAddress(), address)
        return if (isPreKey) {
            cipher.decrypt(org.signal.libsignal.protocol.message.PreKeySignalMessage(ciphertext))
        } else {
            cipher.decrypt(org.signal.libsignal.protocol.message.SignalMessage(ciphertext))
        }
    }

    fun decryptDM(aci: String, deviceId: Int, ciphertext: ByteArray): ByteArray =
        decryptDM(aci, deviceId, false, ciphertext)

    data class ParsedPreKeyBundle(
        val registrationId: Int,
        val preKeyId: Int?,
        val preKeyPublic: ByteArray?,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
        val identityKey: ByteArray,
        val kyberPreKeyId: Int? = null,
        val kyberPreKeyPublic: ByteArray? = null,
        val kyberPreKeySignature: ByteArray? = null,
    )

    /**
     * Build a session from a fetched pre-key bundle. Kyber is mandatory on this protocol version, so
     * a bundle without it is rejected rather than downgraded to a non-post-quantum session — the
     * official client skips such a device instead.
     */
    fun processPreKeyBundle(aci: String, deviceId: Int, bundle: ParsedPreKeyBundle) {
        require(
            bundle.kyberPreKeyId != null &&
                bundle.kyberPreKeyPublic != null &&
                bundle.kyberPreKeySignature != null,
        ) { "pre-key bundle for $aci:$deviceId has no Kyber pre-key" }
        val address = signalAddress(aci, deviceId)
        val ecPreKey: ECPublicKey? = bundle.preKeyPublic?.let { ECPublicKey(it) }
        val signedEc = ECPublicKey(bundle.signedPreKeyPublic)
        val identity = IdentityKey(bundle.identityKey)
        val kyberPub = KEMPublicKey(bundle.kyberPreKeyPublic)
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            deviceId,
            bundle.preKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            ecPreKey,
            bundle.signedPreKeyId,
            signedEc,
            bundle.signedPreKeySignature,
            identity,
            bundle.kyberPreKeyId,
            kyberPub,
            bundle.kyberPreKeySignature,
        )
        // SessionBuilder writes the real session record through the store; nothing else should.
        // SessionBuilder is (remote, local) — the opposite of SessionCipher.
        SessionBuilder(protocolStore, address, localAddress()).process(preKeyBundle)
    }

    fun markKyberPreKeyUsed(kyberId: Int, signedEcId: Int, baseKey: ByteArray) {
        try {
            val ecPub = ECPublicKey(baseKey)
            protocolStore.markKyberPreKeyUsed(kyberId, signedEcId, ecPub)
        } catch (_: Exception) {
            RustSignalCrypto.markKyberPreKeyUsed(kyberId, signedEcId, baseKey)
        }
    }

    fun createSenderKeyDistribution(groupId: String): ByteArray {
        val created = RustSignalCrypto.createSenderKeySplit()
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, created.state)) }
        return created.skdm
    }

    fun processSenderKeyDistribution(groupId: String, senderAci: String, senderDeviceId: Int, skdmBytes: ByteArray) {
        val stateBytes = RustSignalCrypto.processSenderKey(skdmBytes) ?: throw RuntimeException("processSenderKey returned null")
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(senderAci, senderDeviceId, groupId, stateBytes)) }
    }

    fun encryptGroup(groupId: String, paddedPlaintext: ByteArray): ByteArray {
        var entity = runBlocking { db.e2eSenderKeyDao().get(ownAci, ownDeviceId, groupId) }
        if (entity == null) {
            val created = RustSignalCrypto.createSenderKeySplit()
            runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, created.state)) }
            entity = runBlocking { db.e2eSenderKeyDao().get(ownAci, ownDeviceId, groupId) } ?: throw RuntimeException("Failed to create sender key")
        }
        val encrypted = RustSignalCrypto.encryptGroupSplit(entity.record, paddedPlaintext)
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, encrypted.newState)) }
        return encrypted.data
    }

    fun decryptGroup(groupId: String, senderAci: String, senderDeviceId: Int, ciphertext: ByteArray): ByteArray {
        val entity = runBlocking { db.e2eSenderKeyDao().get(senderAci, senderDeviceId, groupId) } ?: throw RuntimeException("No sender key for $senderAci:$senderDeviceId in $groupId")
        val decrypted = RustSignalCrypto.decryptGroupSplit(entity.record, ciphertext)
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(senderAci, senderDeviceId, groupId, decrypted.newState)) }
        return decrypted.data
    }

    /** Sealed-sender encrypt. Returns the message plus the registration id the envelope needs. */
    fun sealedSenderEncrypt(
        recipientAci: String,
        recipientDeviceId: Int,
        paddedPlaintext: ByteArray,
        senderCertificate: SenderCertificate,
    ): EncResult {
        val address = signalAddress(recipientAci, recipientDeviceId)
        val localUuid = try { UUID.fromString(ownAci) } catch (_: Exception) { UUID.randomUUID() }
        val cipher = SealedSessionCipher(protocolStore, localUuid, null, ownDeviceId)
        val encrypted = cipher.encrypt(address, senderCertificate, paddedPlaintext)
        return EncResult(
            // Sealed sender is its own envelope type; the inner ciphertext type is not exposed.
            ciphertextType = SEALED_SENDER_TYPE,
            remoteRegistrationId = cipher.getRemoteRegistrationId(address),
            data = encrypted,
        )
    }

    fun sealedSenderDecrypt(ciphertext: ByteArray, trustRoots: List<ECPublicKey>, timestampMs: Long = System.currentTimeMillis()): ByteArray {
        val validator = CertificateValidator(trustRoots)
        val localUuid = try { UUID.fromString(ownAci) } catch (_: Exception) { UUID.randomUUID() }
        val cipher = SealedSessionCipher(protocolStore, localUuid, null, ownDeviceId)
        val result = cipher.decrypt(validator, ciphertext, timestampMs)
        return result.paddedMessage
    }

    private data class LocalPreKey(val id: Int, val publicKey: ByteArray)

    private fun generatePreKeys(count: Int): List<LocalPreKey> {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        val entities = ArrayList<SignalE2EPreKey>(count)
        val locals = ArrayList<LocalPreKey>(count)
        for (i in 1..count) {
            val id = maxId + i
            val kp = RustSignalCrypto.generateKeyPairSplit()
            val record = kp.privateKey + kp.publicKey
            entities.add(SignalE2EPreKey(id, record, uploaded = false))
            locals.add(LocalPreKey(id, kp.publicKey))
        }
        runBlocking { db.e2ePreKeyDao().insertAll(entities) }
        return locals
    }

    fun ensureSignedPreKeyStored() { }

    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    companion object {
        private const val TAG = "SignalE2E"

        /**
         * Marker for "this ciphertext is a sealed-sender message", mapped to `Envelope.UNIDENTIFIED_SENDER`.
         * Not a `CiphertextMessage` constant — those describe the inner message, which sealed sender wraps.
         */
        const val SEALED_SENDER_TYPE = -1
        private fun b64(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Base64.Default.decode(s)

        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val priv = ECPrivateKey(identityPrivate32)
            return priv.calculateSignature(signedPreKeyPublic32)
        }
    }
}
