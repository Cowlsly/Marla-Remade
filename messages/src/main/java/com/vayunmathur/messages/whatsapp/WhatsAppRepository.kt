package com.vayunmathur.messages.whatsapp

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [WhatsAppDatabase] (whatsapp_database).
 *
 * The single `buildDatabase` call site now lives here (via [RoomRepository.db]);
 * [WhatsAppDatabase.getDatabase] delegates to this repository so existing consumers
 * keep compiling unchanged. The Rust-backed E2E `runBlocking` bridges obtain DAOs
 * via this repository's database.
 */
class WhatsAppRepository private constructor(context: Context) :
    RoomRepository<WhatsAppDatabase>(context, WhatsAppDatabase::class, "whatsapp_database") {

    // ------------------------------------------------------------------
    // Device / session (legacy, currently unused — preserved for completeness)
    // ------------------------------------------------------------------
    suspend fun getDevice(deviceId: String): WhatsAppDevice? = db.deviceDao().getDevice(deviceId)
    suspend fun insertDevice(device: WhatsAppDevice) = db.deviceDao().insertDevice(device)
    suspend fun deleteDevice(deviceId: String) = db.deviceDao().deleteDevice(deviceId)

    suspend fun getSession(jid: String): WhatsAppSession? = db.sessionDao().getSession(jid)
    suspend fun containsSession(jid: String): Boolean = db.sessionDao().containsSession(jid)
    suspend fun getAllSessions(): List<WhatsAppSession> = db.sessionDao().getAllSessions()
    suspend fun insertSession(session: WhatsAppSession) = db.sessionDao().insertSession(session)
    suspend fun deleteSession(jid: String) = db.sessionDao().deleteSession(jid)
    suspend fun clearAllSessions() = db.sessionDao().clearAllSessions()

    // ------------------------------------------------------------------
    // History-sync conversation / media / avatar / poll
    // ------------------------------------------------------------------
    suspend fun getConversation(chatJid: String): WhatsAppConversation? =
        db.conversationDao().getConversation(chatJid)
    suspend fun getRecentConversations(limit: Int): List<WhatsAppConversation> =
        db.conversationDao().getRecent(limit)
    suspend fun upsertConversation(conversation: WhatsAppConversation) =
        db.conversationDao().upsert(conversation)
    suspend fun deleteConversation(chatJid: String) = db.conversationDao().delete(chatJid)
    suspend fun deleteAllConversations() = db.conversationDao().deleteAll()
    suspend fun updateMuteEndTime(chatJid: String, muteEndTime: Long) =
        db.conversationDao().updateMuteEndTime(chatJid, muteEndTime)
    suspend fun updatePinned(chatJid: String, pinned: Boolean) = db.conversationDao().updatePinned(chatJid, pinned)
    suspend fun updateArchived(chatJid: String, archived: Boolean) =
        db.conversationDao().updateArchived(chatJid, archived)
    suspend fun updateMarkedAsUnread(chatJid: String, unread: Boolean) =
        db.conversationDao().updateMarkedAsUnread(chatJid, unread)

    suspend fun upsertMediaRequest(request: WhatsAppMediaRequest) = db.mediaRequestDao().upsert(request)
    suspend fun deleteMediaRequest(messageId: String) = db.mediaRequestDao().delete(messageId)
    suspend fun getUnrequestedMedia(): List<WhatsAppMediaRequest> = db.mediaRequestDao().getUnrequested()

    suspend fun getAvatar(entityJid: String, avatarId: String): WhatsAppAvatarCache? =
        db.avatarCacheDao().get(entityJid, avatarId)
    suspend fun putAvatar(entry: WhatsAppAvatarCache) = db.avatarCacheDao().put(entry)

    suspend fun upsertPollOption(option: WhatsAppPollOption) = db.pollOptionDao().upsert(option)
    suspend fun upsertPollOptions(options: List<WhatsAppPollOption>) = db.pollOptionDao().upsertAll(options)
    suspend fun getPollOptions(msgId: String): List<WhatsAppPollOption> = db.pollOptionDao().getByMessageId(msgId)
    suspend fun getPollOption(msgId: String, hash: String): WhatsAppPollOption? =
        db.pollOptionDao().getByHash(msgId, hash)
    suspend fun deletePollOptions(msgId: String) = db.pollOptionDao().deleteByMessageId(msgId)

    suspend fun upsertPollSecret(secret: WhatsAppPollSecret) = db.pollSecretDao().upsert(secret)
    suspend fun getPollSecret(msgId: String): String? = db.pollSecretDao().get(msgId)

    // ------------------------------------------------------------------
    // Rust-backed E2E stores
    // ------------------------------------------------------------------
    suspend fun getE2ESession(address: String, deviceId: Int): WhatsAppE2ESession? =
        db.e2eSessionDao().get(address, deviceId)
    suspend fun getE2ESubDeviceIds(address: String): List<Int> = db.e2eSessionDao().getSubDeviceIds(address)
    suspend fun insertE2ESession(entity: WhatsAppE2ESession) = db.e2eSessionDao().insert(entity)
    suspend fun e2eSessionExists(address: String, deviceId: Int): Boolean =
        db.e2eSessionDao().exists(address, deviceId)
    suspend fun deleteE2ESession(address: String, deviceId: Int) = db.e2eSessionDao().delete(address, deviceId)
    suspend fun deleteAllE2ESessionsForAddress(address: String) = db.e2eSessionDao().deleteAll(address)

    suspend fun getE2EIdentity(address: String): WhatsAppE2EIdentity? = db.e2eIdentityDao().get(address)
    suspend fun insertE2EIdentity(entity: WhatsAppE2EIdentity) = db.e2eIdentityDao().insert(entity)
    suspend fun deleteE2EIdentity(address: String) = db.e2eIdentityDao().delete(address)

    suspend fun getE2EPreKey(id: Int): WhatsAppE2EPreKey? = db.e2ePreKeyDao().get(id)
    suspend fun getAllE2EPreKeys(): List<WhatsAppE2EPreKey> = db.e2ePreKeyDao().getAll()
    suspend fun getUnuploadedE2EPreKeys(): List<WhatsAppE2EPreKey> = db.e2ePreKeyDao().getUnuploaded()
    suspend fun insertE2EPreKey(entity: WhatsAppE2EPreKey) = db.e2ePreKeyDao().insert(entity)
    suspend fun insertAllE2EPreKeys(entities: List<WhatsAppE2EPreKey>) = db.e2ePreKeyDao().insertAll(entities)
    suspend fun markE2EPreKeysUploadedUpTo(maxId: Int) = db.e2ePreKeyDao().markUploadedUpTo(maxId)
    suspend fun e2ePreKeyExists(id: Int): Boolean = db.e2ePreKeyDao().exists(id)
    suspend fun deleteE2EPreKey(id: Int) = db.e2ePreKeyDao().delete(id)
    suspend fun getE2EPreKeyCount(): Int = db.e2ePreKeyDao().getCount()
    suspend fun getMaxE2EPreKeyId(): Int = db.e2ePreKeyDao().getMaxId()

    suspend fun getE2ESignedPreKey(id: Int): WhatsAppE2ESignedPreKey? = db.e2eSignedPreKeyDao().get(id)
    suspend fun getAllE2ESignedPreKeys(): List<WhatsAppE2ESignedPreKey> = db.e2eSignedPreKeyDao().getAll()
    suspend fun insertE2ESignedPreKey(entity: WhatsAppE2ESignedPreKey) = db.e2eSignedPreKeyDao().insert(entity)
    suspend fun e2eSignedPreKeyExists(id: Int): Boolean = db.e2eSignedPreKeyDao().exists(id)
    suspend fun deleteE2ESignedPreKey(id: Int) = db.e2eSignedPreKeyDao().delete(id)

    suspend fun getE2ESenderKey(address: String, deviceId: Int, distributionId: String): WhatsAppE2ESenderKey? =
        db.e2eSenderKeyDao().get(address, deviceId, distributionId)
    suspend fun insertE2ESenderKey(entity: WhatsAppE2ESenderKey) = db.e2eSenderKeyDao().insert(entity)
    suspend fun deleteE2ESenderKey(address: String, deviceId: Int, distributionId: String) =
        db.e2eSenderKeyDao().delete(address, deviceId, distributionId)

    /** Direct database access for legacy `runBlocking` Rust E2E bridges; prefer suspend wrappers for new code. */
    fun database(): WhatsAppDatabase = db

    companion object {
        @Volatile
        private var instance: WhatsAppRepository? = null

        fun get(context: Context): WhatsAppRepository =
            instance ?: synchronized(this) {
                instance ?: WhatsAppRepository(context).also { instance = it }
            }
    }
}
