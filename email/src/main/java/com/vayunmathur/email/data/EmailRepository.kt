package com.vayunmathur.email.data

import android.content.Context
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.BlockedSender
import com.vayunmathur.email.data.DeletedUid
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

class EmailRepository private constructor(context: Context) :
    RoomRepository<EmailDatabase>(context, EmailDatabase::class, "email-db") {

    private val dao: EmailDao get() = db.emailDao()

    // Expose the underlying database for legacy getInstance delegation
    fun getDatabase(): EmailDatabase = db

    // ---- Accounts ----
    fun getAccountsFlow(): Flow<List<EmailAccount>> = dao.getAccountsFlow()
    suspend fun getAccounts(): List<EmailAccount> = dao.getAccounts()
    suspend fun getAccountByEmail(email: String): EmailAccount? = dao.getAccountByEmail(email)
    suspend fun insertAccount(account: EmailAccount) = dao.insertAccount(account)
    suspend fun setSignature(email: String, signature: String) = dao.setSignature(email, signature)
    suspend fun deleteAccount(account: EmailAccount) = dao.deleteAccount(account)

    // ---- Blocked senders ----
    fun getBlockedSendersFlow(): Flow<List<BlockedSender>> = dao.getBlockedSendersFlow()
    suspend fun insertBlockedSender(sender: BlockedSender) = dao.insertBlockedSender(sender)
    suspend fun deleteBlockedSender(address: String) = dao.deleteBlockedSender(address)

    // ---- Folders ----
    fun getFoldersFlow(accountEmail: String): Flow<List<EmailFolder>> = dao.getFoldersFlow(accountEmail)
    suspend fun insertFolders(folders: List<EmailFolder>) = dao.insertFolders(folders)
    suspend fun clearFolders(accountEmail: String) = dao.clearFolders(accountEmail)

    // ---- Messages ----
    fun getMessagesFlow(accountEmail: String, folderName: String, now: Long): Flow<List<EmailMessage>> =
        dao.getMessagesFlow(accountEmail, folderName, now)
    fun getThreadFlow(accountEmail: String, threadId: String): Flow<List<EmailMessage>> =
        dao.getThreadFlow(accountEmail, threadId)
    suspend fun insertMessages(messages: List<EmailMessage>) = dao.insertMessages(messages)
    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage? =
        dao.getMessage(accountEmail, folderName, uid)
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long) =
        dao.deleteMessageRow(accountEmail, folderName, uid)
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long, tombstone: Boolean) =
        dao.deleteMessageRow(accountEmail, folderName, uid, tombstone)
    suspend fun insertDeletedUid(tombstone: DeletedUid) = dao.insertDeletedUid(tombstone)
    suspend fun getDeletedUids(accountEmail: String, folderName: String): List<Long> =
        dao.getDeletedUids(accountEmail, folderName)
    suspend fun getKnownUids(accountEmail: String, folderName: String): List<Long> =
        dao.getKnownUids(accountEmail, folderName)
    suspend fun getMessagesWithoutBody(accountEmail: String, limit: Int): List<EmailMessage> =
        dao.getMessagesWithoutBody(accountEmail, limit)
    suspend fun clearMessages(accountEmail: String) = dao.clearMessages(accountEmail)
    fun searchMessagesFlow(accountEmail: String, folderName: String, query: String, now: Long): Flow<List<EmailMessage>> =
        dao.searchMessagesFlow(accountEmail, folderName, query, now)
    fun getUnifiedMessagesFlow(folderName: String, now: Long): Flow<List<EmailMessage>> =
        dao.getUnifiedMessagesFlow(folderName, now)
    fun searchUnifiedMessagesFlow(folderName: String, query: String, now: Long): Flow<List<EmailMessage>> =
        dao.searchUnifiedMessagesFlow(folderName, query, now)
    suspend fun setSnooze(accountEmail: String, folderName: String, uid: Long, until: Long) =
        dao.setSnooze(accountEmail, folderName, uid, until)
    suspend fun wakeDueSnoozed(now: Long): Int = dao.wakeDueSnoozed(now)
    suspend fun getRecentUnifiedMessages(): List<EmailMessage> = dao.getRecentUnifiedMessages()
    suspend fun getRecentInboxMessages(): List<EmailMessage> = dao.getRecentInboxMessages()
    suspend fun searchMessages(query: String): List<EmailMessage> = dao.searchMessages(query)
    fun getRecentUnifiedMessagesFlow(): Flow<List<EmailMessage>> = dao.getRecentUnifiedMessagesFlow()
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage> = dao.getRowsWithZeroDateMillis()
    suspend fun updateDateMillis(accountEmail: String, folderName: String, uid: Long, millis: Long) =
        dao.updateDateMillis(accountEmail, folderName, uid, millis)

    // ---- Attachments ----
    suspend fun insertAttachments(attachments: List<Attachment>) = dao.insertAttachments(attachments)
    suspend fun getAttachments(accountEmail: String, uid: Long): List<Attachment> =
        dao.getAttachments(accountEmail, uid)
    suspend fun updateAttachmentLocalUri(accountEmail: String, uid: Long, partId: String, uri: String) =
        dao.updateAttachmentLocalUri(accountEmail, uid, partId, uri)
    suspend fun updateReadStatus(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) =
        dao.updateReadStatus(accountEmail, folderName, uid, isRead)
    suspend fun updateBulkReadStatus(accountEmail: String, uids: List<Long>, isRead: Boolean) =
        dao.updateBulkReadStatus(accountEmail, uids, isRead)

    // ---- Outbox ----
    fun getOutboxFlow(): Flow<List<OutboxEntry>> = dao.getOutboxFlow()
    suspend fun getOutbox(): List<OutboxEntry> = dao.getOutbox()
    suspend fun getOutboxCount(): Int = dao.getOutboxCount()
    suspend fun insertOutboxEntry(entry: OutboxEntry): Long = dao.insertOutboxEntry(entry)
    suspend fun deleteOutboxEntry(entry: OutboxEntry) = dao.deleteOutboxEntry(entry)
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long) =
        dao.updateOutboxAttempt(id, error, attempts, at)

    // ---- Drafts ----
    fun getDraftsFlow(): Flow<List<DraftEntry>> = dao.getDraftsFlow()
    suspend fun getDraft(id: Long): DraftEntry? = dao.getDraft(id)
    suspend fun insertDraft(draft: DraftEntry): Long = dao.insertDraft(draft)
    suspend fun deleteDraftById(id: Long) = dao.deleteDraftById(id)

    companion object {
        @Volatile private var instance: EmailRepository? = null
        fun get(context: Context): EmailRepository =
            instance ?: synchronized(this) {
                instance ?: EmailRepository(context).also { instance = it }
            }
    }
}
