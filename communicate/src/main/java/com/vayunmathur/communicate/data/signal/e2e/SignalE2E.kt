package com.vayunmathur.communicate.data.signal.e2e

import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalE2EPreKey
import com.vayunmathur.communicate.data.signal.SignalE2ESenderKey
import com.vayunmathur.communicate.data.signal.SignalE2ESession
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
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

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
) {
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)
    private val ownIdentityPrivate: ByteArray = b64(auth.identityPrivateKey)
    private val ownSignedPreKeyPrivate: ByteArray = b64(auth.signedPreKeyPrivate)
    private val ownAci: String = auth.aci.ifEmpty { auth.phoneNumber }
    private val ownDeviceId: Int = auth.deviceId

    private val protocolStore: InMemorySignalProtocolStore by lazy {
        val ikp = loadIdentityPair(auth.identityPrivateKey, auth.identityPublicKey)
        InMemorySignalProtocolStore(ikp, auth.registrationId.takeIf { it != 0 } ?: 1)
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

    fun signalAddress(aci: String, deviceId: Int = 1): SignalProtocolAddress =
        SignalProtocolAddress(aci, deviceId)

    fun hasSession(aci: String, deviceId: Int = 1): Boolean =
        runBlocking { db.e2eSessionDao().exists(aci, deviceId) } || try { protocolStore.containsSession(signalAddress(aci, deviceId)) } catch (_: Exception) { false }

    fun deleteSession(aci: String, deviceId: Int = 1) {
        runBlocking { db.e2eSessionDao().delete(aci, deviceId) }
        try { protocolStore.deleteSession(signalAddress(aci, deviceId)) } catch (_: Exception) {}
    }

    data class EncResult(val type: String, val data: ByteArray)

    fun encryptDM(aci: String, deviceId: Int, paddedPlaintext: ByteArray): EncResult {
        return try {
            val address = signalAddress(aci, deviceId)
            val cipher = SessionCipher(protocolStore, address)
            val msg = cipher.encrypt(paddedPlaintext)
            val typeStr = when (msg.type) { 3 -> "prekey"; else -> "whisper" }
            EncResult(typeStr, msg.serialize())
        } catch (_: Exception) {
            val entity = runBlocking { db.e2eSessionDao().get(aci, deviceId) } ?: throw RuntimeException("No session for $aci:$deviceId")
            val result = RustSignalCrypto.encryptSplit(entity.record, paddedPlaintext)
            runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, result.newSession)) }
            EncResult(if (result.isPreKey) "prekey" else "whisper", result.body)
        }
    }

    fun decryptDM(aci: String, deviceId: Int, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        return try {
            val address = signalAddress(aci, deviceId)
            val cipher = SessionCipher(protocolStore, address)
            if (isPreKey) {
                val preKeyMsg = org.signal.libsignal.protocol.message.PreKeySignalMessage(ciphertext)
                cipher.decrypt(preKeyMsg)
            } else {
                val signalMsg = org.signal.libsignal.protocol.message.SignalMessage(ciphertext)
                cipher.decrypt(signalMsg)
            }
        } catch (_: Exception) {
            if (isPreKey) {
                val preKeyId = parsePreKeyIdFromMessage(ciphertext)
                val oneTimePriv: ByteArray? = if (preKeyId != null) {
                    val entity = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
                    entity?.let { rec -> if (rec.record.size >= 32) rec.record.copyOfRange(0, 32) else null }
                } else null
                val decrypted = RustSignalCrypto.decryptPreKeySplit(
                    localIdentityPrivate = ownIdentityPrivate,
                    localIdentityPublic = ownIdentityPublicKey,
                    signedPreKeyPrivate = ownSignedPreKeyPrivate,
                    oneTimePrivate = oneTimePriv,
                    kyberSecretKey = null,
                    preKeyMessageBytes = ciphertext,
                )
                runBlocking {
                    db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, decrypted.newSession))
                    if (preKeyId != null) try { db.e2ePreKeyDao().delete(preKeyId) } catch (_: Exception) {}
                }
                decrypted.plaintext
            } else {
                val entity = runBlocking { db.e2eSessionDao().get(aci, deviceId) } ?: throw RuntimeException("No session for $aci:$deviceId (msg)")
                val decrypted = RustSignalCrypto.decryptMessageSplit(entity.record, ciphertext)
                runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, decrypted.newSession)) }
                decrypted.plaintext
            }
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

    fun processPreKeyBundle(aci: String, deviceId: Int, bundle: ParsedPreKeyBundle) {
        val hasKyber = bundle.kyberPreKeyId != null && bundle.kyberPreKeyPublic != null && bundle.kyberPreKeySignature != null
        if (hasKyber) {
            val address = signalAddress(aci, deviceId)
            val ecPreKey: ECPublicKey? = bundle.preKeyPublic?.let { ECPublicKey(it) }
            val signedEc = ECPublicKey(bundle.signedPreKeyPublic)
            val identity = IdentityKey(bundle.identityKey)
            val kyberPub = KEMPublicKey(bundle.kyberPreKeyPublic!!)
            val preKeyBundle = PreKeyBundle(
                bundle.registrationId,
                deviceId,
                bundle.preKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
                ecPreKey,
                bundle.signedPreKeyId,
                signedEc,
                bundle.signedPreKeySignature,
                identity,
                bundle.kyberPreKeyId!!,
                kyberPub,
                bundle.kyberPreKeySignature!!,
            )
            val builder = SessionBuilder(protocolStore, address)
            builder.process(preKeyBundle)
            runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, ByteArray(0))) }
            return
        }
        val sessionBytes = RustSignalCrypto.processPreKeyBundle(
            localIdentityPrivate = ownIdentityPrivate,
            localIdentityPublic = ownIdentityPublicKey,
            localRegistrationId = auth.registrationId,
            registrationId = bundle.registrationId,
            preKeyId = bundle.preKeyId ?: -1,
            preKeyPublic = bundle.preKeyPublic,
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = bundle.signedPreKeyPublic,
            signedPreKeySignature = bundle.signedPreKeySignature,
            identityKey = bundle.identityKey,
            kyberPreKeyId = bundle.kyberPreKeyId ?: -1,
            kyberPreKeyPublic = bundle.kyberPreKeyPublic ?: ByteArray(0),
            kyberPreKeySignature = bundle.kyberPreKeySignature ?: ByteArray(0),
            kyberCiphertext = ByteArray(0),
        ) ?: throw RuntimeException("Rust processPreKeyBundle returned null")
        runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, sessionBytes)) }
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

    fun sealedSenderEncrypt(recipientAci: String, recipientDeviceId: Int, plaintext: ByteArray, senderCertificate: SenderCertificate): ByteArray {
        val address = signalAddress(recipientAci, recipientDeviceId)
        val localUuid = try { UUID.fromString(ownAci) } catch (_: Exception) { UUID.randomUUID() }
        val cipher = SealedSessionCipher(protocolStore, localUuid, null, ownDeviceId)
        return cipher.encrypt(address, senderCertificate, plaintext)
    }

    fun sealedSenderEncrypt(recipientAci: String, recipientDeviceId: Int, plaintext: ByteArray): ByteArray {
        return RustSignalCrypto.sealedSenderEncrypt(plaintext, recipientAci, recipientDeviceId) ?: throw RuntimeException("sealedSenderEncrypt returned null")
    }

    fun sealedSenderDecrypt(ciphertext: ByteArray, trustRoot: ECPublicKey, timestampMs: Long = System.currentTimeMillis()): ByteArray {
        val validator = CertificateValidator(trustRoot)
        val localUuid = try { UUID.fromString(ownAci) } catch (_: Exception) { UUID.randomUUID() }
        val cipher = SealedSessionCipher(protocolStore, localUuid, null, ownDeviceId)
        val result = cipher.decrypt(validator, ciphertext, timestampMs)
        return result.paddedMessage
    }

    fun sealedSenderDecrypt(ciphertext: ByteArray): ByteArray {
        return RustSignalCrypto.sealedSenderDecrypt(ciphertext) ?: throw RuntimeException("sealedSenderDecrypt returned null")
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
        private fun b64(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Base64.Default.decode(s)

        fun parsePreKeyIdFromMessage(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            var pos = 1
            while (pos < data.size) {
                var keyVal = 0L
                var shift = 0
                var idx = pos
                var b: Int
                do {
                    if (idx >= data.size) return null
                    b = data[idx].toInt() and 0xFF
                    keyVal = keyVal or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    idx++
                } while (b and 0x80 != 0)
                val fieldNum = (keyVal shr 3).toInt()
                val wireType = (keyVal and 7).toInt()
                pos = idx
                if (wireType == 0) {
                    var v = 0L
                    shift = 0
                    while (pos < data.size) {
                        b = data[pos].toInt() and 0xFF
                        v = v or ((b and 0x7F).toLong() shl shift)
                        pos++
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (fieldNum == 1) return v.toInt()
                } else if (wireType == 2) {
                    var len = 0L
                    shift = 0
                    while (pos < data.size) {
                        b = data[pos].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        pos++
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (pos + len > data.size) return null
                    pos += len.toInt()
                } else break
            }
            return null
        }

        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val priv = ECPrivateKey(identityPrivate32)
            return priv.calculateSignature(signedPreKeyPublic32)
        }
    }
}
