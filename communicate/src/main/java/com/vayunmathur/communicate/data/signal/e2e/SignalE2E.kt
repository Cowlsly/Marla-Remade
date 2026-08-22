package com.vayunmathur.communicate.data.signal.e2e

import android.util.Log
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
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

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
    private val ownIdentityPrivate: ByteArray = b64(auth.identityPrivateKey)
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
            val message = PreKeySignalMessage(ciphertext)
            // Which of our keys the sender used. A decryption failure here is almost always a mismatch
            // between these ids and what the store holds, so name them rather than guessing later.
            Log.i(
                TAG,
                "inbound prekey message from $aci:$deviceId " +
                    "signedPreKeyId=${message.signedPreKeyId} " +
                    "preKeyId=${message.preKeyId.orElse(null)} " +
                    "registrationId=${message.registrationId} " +
                    "ourRegistrationId=${protocolStore.localRegistrationId} " +
                    "theirIdentity=${message.identityKey.serialize().size}B",
            )
            cipher.decrypt(message)
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

    /**
     * What needs registering with the server after [ensureLocalPreKeys].
     *
     * Keys are the serialized public halves; signatures are over those, by our identity key.
     */
    data class PreKeyUpload(
        val signedPreKey: KeyEntity?,
        val lastResortKyber: KeyEntity?,
        val oneTimeEcPreKeys: List<KeyEntity>,
    ) {
        data class KeyEntity(val id: Int, val publicKey: ByteArray, val signature: ByteArray? = null)

        val isEmpty: Boolean
            get() = signedPreKey == null && lastResortKyber == null && oneTimeEcPreKeys.isEmpty()
    }

    /**
     * Make sure our own pre-keys are in the protocol store, which is what inbound pre-key messages need
     * to decrypt. Registration only wrote them to preferences, so without this a first message from a
     * peer fails with `InvalidKeyIdException: no signed pre-key <id>`.
     *
     * Stored material is reused only when it verifies against itself — the private half must derive the
     * public half, and the signature must check out under our identity key. Anything that fails is
     * regenerated and re-registered, because a key the server serves but we cannot use produces a
     * decryption failure with no way to tell from the error which half was wrong.
     *
     * A Kyber pre-key is always regenerated: libsignal only exposes `KEMKeyPair.generate()`, with no path
     * back from persisted bytes.
     */
    fun ensureLocalPreKeys(): PreKeyUpload {
        val signed = ensureSignedPreKey()
        val kyber = ensureLastResortKyberPreKey()
        val oneTime = ensureOneTimePreKeys()
        return PreKeyUpload(signedPreKey = signed, lastResortKyber = kyber, oneTimeEcPreKeys = oneTime)
    }

    /** Whether our store holds a signed pre-key [id] whose public half is exactly [publicKey]. */
    fun hasSignedPreKeyMatching(id: Int, publicKey: ByteArray?): Boolean {
        if (publicKey == null) return true
        return try {
            val record = protocolStore.loadSignedPreKey(id)
            record.keyPair.publicKey.serialize().contentEquals(publicKey)
        } catch (_: Throwable) {
            false
        }
    }

    /** Rotate the signed pre-key regardless of what is stored, for when the server's copy is unusable. */
    fun rotateSignedPreKeyNow(): PreKeyUpload.KeyEntity? = rotateSignedPreKey()

    /**
     * Returns a payload when the signed pre-key had to be regenerated and needs registering, null when the
     * stored one was sound and simply seeded.
     */
    private fun ensureSignedPreKey(): PreKeyUpload.KeyEntity? {
        val storedId = auth.signedPreKeyId
        if (storedId != 0 && protocolStore.containsSignedPreKey(storedId)) return null
        if (storedId != 0 && seedStoredSignedPreKey(storedId)) return null
        return rotateSignedPreKey()
    }

    /** True when the stored material verified and was seeded into the store. */
    private fun seedStoredSignedPreKey(id: Int): Boolean {
        val pub = b64(auth.signedPreKeyPublic)
        val priv = b64(auth.signedPreKeyPrivate)
        val signature = b64(auth.signedPreKeySignature)
        if (pub.isEmpty() || priv.isEmpty()) {
            Log.w(TAG, "no stored signed pre-key material; rotating")
            return false
        }
        return try {
            val privateKey = ECPrivateKey(priv)
            val derived = privateKey.getPublicKey().serialize()
            if (!derived.contentEquals(pub)) {
                // The server serves the public half; without the matching private half every inbound
                // pre-key message fails in the key agreement rather than at lookup.
                Log.w(TAG, "stored signed pre-key $id is inconsistent (private half derives a different public key); rotating")
                return false
            }
            val identityOk = signature.isNotEmpty() &&
                ECPublicKey(ownIdentityPublicKey).verifySignature(pub, signature)
            if (!identityOk) {
                Log.w(TAG, "stored signed pre-key $id signature does not verify under our identity key; rotating")
                return false
            }
            protocolStore.storeSignedPreKey(
                id,
                SignedPreKeyRecord(id, System.currentTimeMillis(), ECKeyPair(ECPublicKey(pub), privateKey), signature),
            )
            Log.i(TAG, "seeded signed pre-key $id into the protocol store")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not rebuild signed pre-key $id; rotating", t)
            false
        }
    }

    private fun rotateSignedPreKey(): PreKeyUpload.KeyEntity? = try {
        val keyPair = ECKeyPair.generate()
        val publicKey = keyPair.publicKey.serialize()
        val signature = signSignedPreKey(ownIdentityPrivate, publicKey)
        val id = auth.signedPreKeyId.takeIf { it != 0 }?.plus(1) ?: 1
        protocolStore.storeSignedPreKey(
            id,
            SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature),
        )
        Log.i(TAG, "rotated signed pre-key to $id; needs registering")
        PreKeyUpload.KeyEntity(id, publicKey, signature)
    } catch (t: Throwable) {
        Log.e(TAG, "could not rotate the signed pre-key", t)
        null
    }

    private fun ensureLastResortKyberPreKey(): PreKeyUpload.KeyEntity? {
        val existing = runBlocking { db.e2eKyberPreKeyDao().getAll() }
        if (existing.any { it.lastResort }) return null
        return try {
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val publicKey = keyPair.publicKey.serialize()
            val signature = signSignedPreKey(ownIdentityPrivate, publicKey)
            val id = (runBlocking { db.e2eKyberPreKeyDao().getAll() }.maxOfOrNull { it.id } ?: 0) + 1
            val record = KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
            protocolStore.storeKyberPreKey(id, record, lastResort = true)
            Log.i(TAG, "generated last-resort Kyber pre-key $id; needs registering")
            PreKeyUpload.KeyEntity(id, publicKey, signature)
        } catch (t: Throwable) {
            Log.w(TAG, "could not generate a last-resort Kyber pre-key", t)
            null
        }
    }

    private fun ensureOneTimePreKeys(): List<PreKeyUpload.KeyEntity> {
        val have = runBlocking { db.e2ePreKeyDao().getAll() }.size
        if (have >= ONE_TIME_PREKEY_FLOOR) return emptyList()
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        return (1..ONE_TIME_PREKEY_BATCH).mapNotNull { offset ->
            val id = maxId + offset
            try {
                val keyPair = ECKeyPair.generate()
                // Stored through the store so it lands as a libsignal record, not a bespoke blob.
                protocolStore.storePreKey(id, PreKeyRecord(id, keyPair))
                PreKeyUpload.KeyEntity(id, keyPair.publicKey.serialize())
            } catch (t: Throwable) {
                Log.w(TAG, "could not generate one-time pre-key $id", t)
                null
            }
        }
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

    fun ensureSignedPreKeyStored() {
        ensureLocalPreKeys()
    }

    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    companion object {
        private const val TAG = "SignalE2E"

        /** Regenerate one-time pre-keys once the store dips below this. */
        private const val ONE_TIME_PREKEY_FLOOR = 10
        private const val ONE_TIME_PREKEY_BATCH = 100

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
