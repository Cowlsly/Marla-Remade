package com.vayunmathur.messages.signal.store

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [SignalDatabase] (signal_protocol.db).
 *
 * The single `buildDatabase` call site now lives here (via [RoomRepository.db]);
 * [SignalDatabase.getInstance] delegates to this repository so existing consumers
 * keep compiling unchanged. libsignal `runBlocking` store bridges keep working —
 * they now obtain DAOs via this repository's database.
 *
 * Exposes suspend wrappers for every DAO method currently consumed (see
 * [SignalSessionStore], [SignalIdentityKeyStore], [SignalPreKeyStore],
 * [SignalSenderKeyStore], [SignalRecipientStore], [SignalGroupStore],
 * [com.vayunmathur.messages.signal.media.BackupManager],
 * [com.vayunmathur.messages.signal.media.BackupRestore], and [com.vayunmathur.messages.signal.SignalClient]).
 */
class SignalProtocolRepository private constructor(context: Context) :
    RoomRepository<SignalDatabase>(context, SignalDatabase::class, "signal_protocol.db") {

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------
    suspend fun getSession(address: String, deviceId: Int): SignalSessionEntity? =
        db.sessionDao().get(address, deviceId)
    suspend fun getSubDeviceIds(address: String): List<Int> = db.sessionDao().getSubDeviceIds(address)
    suspend fun getAllSessionsForAddress(address: String): List<SignalSessionEntity> =
        db.sessionDao().getAllForAddress(address)
    suspend fun insertSession(entity: SignalSessionEntity) = db.sessionDao().insert(entity)
    suspend fun sessionExists(address: String, deviceId: Int): Boolean = db.sessionDao().exists(address, deviceId)
    suspend fun deleteSession(address: String, deviceId: Int) = db.sessionDao().delete(address, deviceId)
    suspend fun deleteAllSessionsForAddress(address: String) = db.sessionDao().deleteAll(address)
    suspend fun deleteAllSessions() = db.sessionDao().deleteAllSessions()

    // ------------------------------------------------------------------
    // Identity key
    // ------------------------------------------------------------------
    suspend fun getIdentityKey(serviceId: String): SignalIdentityKeyEntity? = db.identityKeyDao().get(serviceId)
    suspend fun insertIdentityKey(entity: SignalIdentityKeyEntity) = db.identityKeyDao().insert(entity)
    suspend fun deleteIdentityKey(serviceId: String) = db.identityKeyDao().delete(serviceId)

    // ------------------------------------------------------------------
    // Pre keys
    // ------------------------------------------------------------------
    suspend fun getPreKey(service: String, id: Int): SignalPreKeyEntity? = db.preKeyDao().get(service, id)
    suspend fun getAllPreKeys(service: String): List<SignalPreKeyEntity> = db.preKeyDao().getAll(service)
    suspend fun insertPreKey(entity: SignalPreKeyEntity) = db.preKeyDao().insert(entity)
    suspend fun preKeyExists(service: String, id: Int): Boolean = db.preKeyDao().exists(service, id)
    suspend fun deletePreKey(service: String, id: Int) = db.preKeyDao().delete(service, id)
    suspend fun deleteAllPreKeys(service: String) = db.preKeyDao().deleteAll(service)
    suspend fun deleteAllPreKeysAllServices() = db.preKeyDao().deleteAllServices()
    suspend fun getPreKeyCount(service: String): Int = db.preKeyDao().getCount(service)
    suspend fun getMaxPreKeyId(service: String): Int = db.preKeyDao().getMaxId(service)

    // ------------------------------------------------------------------
    // Signed pre keys
    // ------------------------------------------------------------------
    suspend fun getSignedPreKey(service: String, id: Int): SignalSignedPreKeyEntity? =
        db.signedPreKeyDao().get(service, id)
    suspend fun getAllSignedPreKeys(service: String): List<SignalSignedPreKeyEntity> =
        db.signedPreKeyDao().getAll(service)
    suspend fun insertSignedPreKey(entity: SignalSignedPreKeyEntity) = db.signedPreKeyDao().insert(entity)
    suspend fun signedPreKeyExists(service: String, id: Int): Boolean = db.signedPreKeyDao().exists(service, id)
    suspend fun deleteSignedPreKey(service: String, id: Int) = db.signedPreKeyDao().delete(service, id)
    suspend fun deleteAllSignedPreKeys(service: String) = db.signedPreKeyDao().deleteAll(service)

    // ------------------------------------------------------------------
    // Kyber pre keys
    // ------------------------------------------------------------------
    suspend fun getKyberPreKey(service: String, id: Int): SignalKyberPreKeyEntity? =
        db.kyberPreKeyDao().get(service, id)
    suspend fun getAllKyberPreKeys(service: String): List<SignalKyberPreKeyEntity> =
        db.kyberPreKeyDao().getAll(service)
    suspend fun getAllNonLastResortKyberPreKeys(service: String): List<SignalKyberPreKeyEntity> =
        db.kyberPreKeyDao().getAllNonLastResort(service)
    suspend fun insertKyberPreKey(entity: SignalKyberPreKeyEntity) = db.kyberPreKeyDao().insert(entity)
    suspend fun kyberPreKeyExists(service: String, id: Int): Boolean = db.kyberPreKeyDao().exists(service, id)
    suspend fun isLastResortKyberPreKey(service: String, id: Int): Boolean? =
        db.kyberPreKeyDao().isLastResort(service, id)
    suspend fun deleteKyberPreKey(service: String, id: Int) = db.kyberPreKeyDao().delete(service, id)
    suspend fun deleteKyberPreKeysNonLastResort(service: String) =
        db.kyberPreKeyDao().deleteAllNonLastResort(service)
    suspend fun deleteAllKyberPreKeysForService(service: String) = db.kyberPreKeyDao().deleteAll(service)
    suspend fun getKyberPreKeyCount(service: String): Int = db.kyberPreKeyDao().getCount(service)
    suspend fun getMaxKyberPreKeyId(service: String): Int = db.kyberPreKeyDao().getMaxId(service)

    // ------------------------------------------------------------------
    // Sender keys
    // ------------------------------------------------------------------
    suspend fun getSenderKey(address: String, deviceId: Int, distributionId: String): SignalSenderKeyEntity? =
        db.senderKeyDao().get(address, deviceId, distributionId)
    suspend fun insertSenderKey(entity: SignalSenderKeyEntity) = db.senderKeyDao().insert(entity)
    suspend fun deleteSenderKey(address: String, deviceId: Int, distributionId: String) =
        db.senderKeyDao().delete(address, deviceId, distributionId)

    suspend fun getSenderKeyInfo(groupId: String): SignalSenderKeyInfoEntity? =
        db.senderKeyInfoDao().get(groupId)
    suspend fun insertSenderKeyInfo(entity: SignalSenderKeyInfoEntity) = db.senderKeyInfoDao().insert(entity)
    suspend fun deleteSenderKeyInfo(groupId: String) = db.senderKeyInfoDao().delete(groupId)

    // ------------------------------------------------------------------
    // Recipients
    // ------------------------------------------------------------------
    suspend fun getRecipient(aci: String): SignalRecipientEntity? = db.recipientDao().get(aci)
    suspend fun insertRecipient(entity: SignalRecipientEntity) = db.recipientDao().insert(entity)
    suspend fun getRecipientByPni(pni: String): SignalRecipientEntity? = db.recipientDao().getByPni(pni)
    suspend fun getRecipientByE164(e164: String): SignalRecipientEntity? = db.recipientDao().getByE164(e164)
    suspend fun searchRecipients(query: String): List<SignalRecipientEntity> = db.recipientDao().search(query)
    suspend fun getAllRecipients(): List<SignalRecipientEntity> = db.recipientDao().getAll()
    suspend fun getAllContacts(): List<SignalRecipientEntity> = db.recipientDao().getAllContacts()
    suspend fun getProfileKey(aci: String): ByteArray? = db.recipientDao().getProfileKey(aci)
    suspend fun deleteRecipientByPni(pni: String) = db.recipientDao().deleteByPni(pni)

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------
    suspend fun getGroup(groupId: String): SignalGroupEntity? = db.groupDao().get(groupId)
    suspend fun getGroupMasterKey(groupId: String): ByteArray? = db.groupDao().getMasterKey(groupId)
    suspend fun insertGroup(entity: SignalGroupEntity) = db.groupDao().insert(entity)
    suspend fun getAllGroups(): List<SignalGroupEntity> = db.groupDao().getAll()

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------
    suspend fun insertBackupRecipient(entity: SignalBackupRecipientEntity) =
        db.backupRecipientDao().insert(entity)
    suspend fun getBackupRecipient(id: Long): SignalBackupRecipientEntity? = db.backupRecipientDao().get(id)
    suspend fun deleteAllBackupRecipients() = db.backupRecipientDao().deleteAll()

    suspend fun insertBackupChat(entity: SignalBackupChatEntity) = db.backupChatDao().insert(entity)
    suspend fun getBackupChat(id: Long): SignalBackupChatEntity? = db.backupChatDao().get(id)
    suspend fun getAllBackupChats(): List<SignalBackupChatEntity> = db.backupChatDao().getAll()
    suspend fun deleteAllBackupChats() = db.backupChatDao().deleteAll()

    suspend fun insertBackupChatItem(entity: SignalBackupChatItemEntity) = db.backupChatItemDao().insert(entity)
    suspend fun getBackupChatItems(chatId: Long, limit: Int = 100): List<SignalBackupChatItemEntity> =
        db.backupChatItemDao().getByChatId(chatId, limit)
    suspend fun deleteAllBackupChatItems() = db.backupChatItemDao().deleteAll()

    // ------------------------------------------------------------------
    // Event buffer (coverage, currently unused)
    // ------------------------------------------------------------------
    suspend fun getEventBuffer(hash: ByteArray): SignalEventBufferEntity? = db.eventBufferDao().get(hash)
    suspend fun insertEventBuffer(entity: SignalEventBufferEntity) = db.eventBufferDao().insert(entity)
    suspend fun clearEventBufferPlaintext(hash: ByteArray) = db.eventBufferDao().clearPlaintext(hash)
    suspend fun deleteEventBuffersOlderThan(maxTimestamp: Long) = db.eventBufferDao().deleteOlderThan(maxTimestamp)

    /** Direct database access for legacy `runBlocking` libsignal bridges; prefer suspend wrappers for new code. */
    fun database(): SignalDatabase = db

    companion object {
        @Volatile
        private var instance: SignalProtocolRepository? = null

        fun get(context: Context): SignalProtocolRepository =
            instance ?: synchronized(this) {
                instance ?: SignalProtocolRepository(context).also { instance = it }
            }
    }
}
