package com.vayunmathur.communicate.data.signal.e2e

import android.util.Base64
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalE2EIdentity
import com.vayunmathur.communicate.data.signal.SignalE2EKyberPreKey
import com.vayunmathur.communicate.data.signal.SignalE2EKyberUsedBaseKey
import com.vayunmathur.communicate.data.signal.SignalE2EPreKey
import com.vayunmathur.communicate.data.signal.SignalE2ESenderKey
import com.vayunmathur.communicate.data.signal.SignalE2ESession
import com.vayunmathur.communicate.data.signal.SignalE2ESignedPreKey
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * [SignalProtocolStore] backed by the app's (SQLCipher-encrypted) Room database, so sessions,
 * identities and pre-keys survive process death. An in-memory store loses the ratchet state on every
 * restart, which then presents as decrypt failures against peers that have moved on.
 *
 * libsignal's store interfaces are synchronous, so the DAO calls are wrapped in [runBlocking]. The
 * callers are already off the main thread (send/receive both run on Dispatchers.IO).
 */
class PersistentSignalProtocolStore(
    private val db: SignalDatabase,
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int,
) : SignalProtocolStore {

    // -- IdentityKeyStore --

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val key = address.name
        val existing = runBlocking { db.e2eIdentityDao().get(key) }
        val replaced = existing != null && !existing.identityKey.contentEquals(identityKey.serialize())
        runBlocking {
            db.e2eIdentityDao().insert(SignalE2EIdentity(address = key, identityKey = identityKey.serialize()))
        }
        return if (replaced) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    /**
     * Trust-on-first-use: an unknown peer is accepted, a known peer must present the same key. A
     * changed key is refused rather than silently accepted, which is what makes a server-substituted
     * identity visible instead of transparent.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val stored = runBlocking { db.e2eIdentityDao().get(address.name) } ?: return true
        return stored.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val stored = runBlocking { db.e2eIdentityDao().get(address.name) } ?: return null
        return try { IdentityKey(stored.identityKey) } catch (_: Exception) { null }
    }

    // -- SessionStore --

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val stored = runBlocking { db.e2eSessionDao().get(address.name, address.deviceId) }
        val record = stored?.record
        if (record == null || record.isEmpty()) return SessionRecord()
        return try { SessionRecord(record) } catch (_: Exception) { SessionRecord() }
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            val stored = runBlocking { db.e2eSessionDao().get(address.name, address.deviceId) }
                ?: throw NoSessionException("no session for $address")
            if (stored.record.isEmpty()) throw NoSessionException("empty session record for $address")
            try {
                SessionRecord(stored.record)
            } catch (e: Exception) {
                throw NoSessionException("unreadable session record for $address: ${e.message}")
            }
        }

    /**
     * Devices of [aci] with a usable session. Uses the same sender-chain test as [containsSession] so
     * archived or empty records are not reported — otherwise a device we can no longer encrypt for
     * would be retried on every send.
     */
    override fun getSubDeviceSessions(name: String): List<Int> =
        runBlocking { db.e2eSessionDao().getSubDeviceIds(name) }
            .filter { it != 1 && containsSession(SignalProtocolAddress(name, it)) }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runBlocking {
            db.e2eSessionDao().insert(
                SignalE2ESession(address = address.name, deviceId = address.deviceId, record = record.serialize()),
            )
        }
    }

    /**
     * A record only counts as a session once it has a sender chain. A freshly created but unused
     * [SessionRecord] would otherwise report true and make encryption fail later.
     */
    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val stored = runBlocking { db.e2eSessionDao().get(address.name, address.deviceId) } ?: return false
        if (stored.record.isEmpty()) return false
        return try { SessionRecord(stored.record).hasSenderChain() } catch (_: Exception) { false }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        runBlocking { db.e2eSessionDao().delete(address.name, address.deviceId) }
    }

    override fun deleteAllSessions(name: String) {
        runBlocking { db.e2eSessionDao().deleteAll(name) }
    }

    // -- PreKeyStore --

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val stored = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
            ?: throw InvalidKeyIdException("no pre-key $preKeyId")
        return try {
            PreKeyRecord(stored.record)
        } catch (e: Exception) {
            throw InvalidKeyIdException("unreadable pre-key $preKeyId: ${e.message}")
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking { db.e2ePreKeyDao().insert(SignalE2EPreKey(id = preKeyId, record = record.serialize())) }
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        runBlocking { db.e2ePreKeyDao().exists(preKeyId) }

    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.e2ePreKeyDao().delete(preKeyId) }
    }

    // -- SignedPreKeyStore --

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val stored = runBlocking { db.e2eSignedPreKeyDao().get(signedPreKeyId) }
            ?: throw InvalidKeyIdException("no signed pre-key $signedPreKeyId")
        return try {
            SignedPreKeyRecord(stored.record)
        } catch (e: Exception) {
            throw InvalidKeyIdException("unreadable signed pre-key $signedPreKeyId: ${e.message}")
        }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        runBlocking { db.e2eSignedPreKeyDao().getAll() }
            .mapNotNull { runCatching { SignedPreKeyRecord(it.record) }.getOrNull() }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runBlocking {
            db.e2eSignedPreKeyDao().insert(SignalE2ESignedPreKey(id = signedPreKeyId, record = record.serialize()))
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        runBlocking { db.e2eSignedPreKeyDao().exists(signedPreKeyId) }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runBlocking { db.e2eSignedPreKeyDao().delete(signedPreKeyId) }
    }

    // -- KyberPreKeyStore --

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val stored = runBlocking { db.e2eKyberPreKeyDao().get(kyberPreKeyId) }
            ?: throw InvalidKeyIdException("no kyber pre-key $kyberPreKeyId")
        return try {
            KyberPreKeyRecord(stored.record)
        } catch (e: Exception) {
            throw InvalidKeyIdException("unreadable kyber pre-key $kyberPreKeyId: ${e.message}")
        }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        runBlocking { db.e2eKyberPreKeyDao().getAll() }
            .mapNotNull { runCatching { KyberPreKeyRecord(it.record) }.getOrNull() }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        storeKyberPreKey(kyberPreKeyId, record, lastResort = false)
    }

    fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord, lastResort: Boolean) {
        runBlocking {
            db.e2eKyberPreKeyDao().insert(
                SignalE2EKyberPreKey(id = kyberPreKeyId, record = record.serialize(), lastResort = lastResort),
            )
        }
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        runBlocking { db.e2eKyberPreKeyDao().exists(kyberPreKeyId) }

    /**
     * One-time keys are consumed. Last-resort keys stay, but the
     * (kyberPreKeyId, signedPreKeyId, baseKey) tuple is recorded so a replayed pre-key message is
     * rejected instead of establishing a second session off the same key material.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val stored = runBlocking { db.e2eKyberPreKeyDao().get(kyberPreKeyId) } ?: return
        if (!stored.lastResort) {
            runBlocking {
                db.e2eKyberPreKeyDao().delete(kyberPreKeyId)
                db.e2eKyberUsedBaseKeyDao().deleteForKyberPreKey(kyberPreKeyId)
            }
            return
        }
        val baseKeyB64 = Base64.encodeToString(baseKey.serialize(), Base64.NO_WRAP)
        val seen = runBlocking { db.e2eKyberUsedBaseKeyDao().exists(kyberPreKeyId, signedPreKeyId, baseKeyB64) }
        if (seen) {
            throw ReusedBaseKeyException(
                "kyber pre-key $kyberPreKeyId already used with signed pre-key $signedPreKeyId and this base key",
            )
        }
        runBlocking {
            db.e2eKyberUsedBaseKeyDao().insert(
                SignalE2EKyberUsedBaseKey(
                    kyberPreKeyId = kyberPreKeyId,
                    signedPreKeyId = signedPreKeyId,
                    baseKeyB64 = baseKeyB64,
                ),
            )
        }
    }

    // -- SenderKeyStore --

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        runBlocking {
            db.e2eSenderKeyDao().insert(
                SignalE2ESenderKey(
                    address = sender.name,
                    deviceId = sender.deviceId,
                    distributionId = distributionId.toString(),
                    record = record.serialize(),
                ),
            )
        }
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val stored = runBlocking {
            db.e2eSenderKeyDao().get(sender.name, sender.deviceId, distributionId.toString())
        } ?: return null
        return try { SenderKeyRecord(stored.record) } catch (_: Exception) { null }
    }
}
