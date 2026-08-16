package com.vayunmathur.email.data

import androidx.room.*
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.BlockedSender
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {
    @Query("SELECT * FROM EmailAccount")
    fun getAccountsFlow(): Flow<List<EmailAccount>>

    @Query("SELECT * FROM EmailAccount")
    suspend fun getAccounts(): List<EmailAccount>

    @Query("SELECT * FROM EmailAccount WHERE email = :email")
    suspend fun getAccountByEmail(email: String): EmailAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: EmailAccount)

    @Query("UPDATE EmailAccount SET signature = :signature WHERE email = :email")
    suspend fun setSignature(email: String, signature: String)

    // ---- Blocked senders ----

    @Query("SELECT * FROM BlockedSender ORDER BY address ASC")
    fun getBlockedSendersFlow(): Flow<List<BlockedSender>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedSender(sender: BlockedSender)

    @Query("DELETE FROM BlockedSender WHERE address = :address")
    suspend fun deleteBlockedSender(address: String)

    @Delete
    suspend fun deleteAccount(account: EmailAccount)

    @Query("SELECT * FROM EmailFolder WHERE accountEmail = :accountEmail")
    fun getFoldersFlow(accountEmail: String): Flow<List<EmailFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<EmailFolder>)

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC")
    fun getMessagesFlow(accountEmail: String, folderName: String, now: Long): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND threadId = :threadId ORDER BY dateMillis ASC, id ASC")
    fun getThreadFlow(accountEmail: String, threadId: String): Flow<List<EmailMessage>>

    /** Raw insert — do not call directly. Use [insertMessages], which populates
     *  [EmailMessage.peekContent] before delegating here. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertMessages(messages: List<EmailMessage>)

    /**
     * Insert messages, always (re)computing [EmailMessage.peekContent] from the
     * incoming body via [previewText] so the stored snippet stays in sync with the
     * body regardless of caller. Runs in one transaction.
     */
    @Transaction
    suspend fun insertMessages(messages: List<EmailMessage>) =
        _insertMessages(messages.map { it.copy(peekContent = it.previewText(PEEK_LEN)) })

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND id = :uid AND folderName = :folderName")
    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage?

    @Query("DELETE FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND id = :uid")
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long)

    // ---- Deleted-UID tombstones ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedUid(tombstone: com.vayunmathur.email.data.DeletedUid)

    /** UIDs the user deleted locally — consulted by sync to avoid re-inserting them. */
    @Query("SELECT uid FROM DeletedUid WHERE accountEmail = :accountEmail AND folderName = :folderName")
    suspend fun getDeletedUids(accountEmail: String, folderName: String): List<Long>

    /** Record a tombstone and remove the local row in one step. */
    @Transaction
    suspend fun deleteMessageRow(accountEmail: String, folderName: String, uid: Long, tombstone: Boolean) {
        if (tombstone) {
            insertDeletedUid(com.vayunmathur.email.data.DeletedUid(accountEmail, folderName, uid))
        }
        deleteMessageRow(accountEmail, folderName, uid)
    }

    /** UIDs already stored for a given folder — used to skip body re-fetch in sync. */
    @Query("SELECT id FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName")
    suspend fun getKnownUids(accountEmail: String, folderName: String): List<Long>

    /**
     * Messages we have headers for but no body yet. Used by the sync worker to
     * back-fill bodies in the background once the per-folder header sync is done.
     * Newest first because the user is most likely to open recent mail.
     */
    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND body IS NULL ORDER BY id DESC LIMIT :limit")
    suspend fun getMessagesWithoutBody(accountEmail: String, limit: Int): List<EmailMessage>

    @Query("DELETE FROM EmailFolder WHERE accountEmail = :accountEmail")
    suspend fun clearFolders(accountEmail: String)

    @Query("DELETE FROM EmailMessage WHERE accountEmail = :accountEmail")
    suspend fun clearMessages(accountEmail: String)

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchMessagesFlow(accountEmail: String, folderName: String, query: String, now: Long): Flow<List<EmailMessage>>

    // Unified Inbox
    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC")
    fun getUnifiedMessagesFlow(folderName: String, now: Long): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchUnifiedMessagesFlow(folderName: String, query: String, now: Long): Flow<List<EmailMessage>>

    @Query("UPDATE EmailMessage SET snoozedUntil = :until WHERE accountEmail = :accountEmail AND folderName = :folderName AND id = :uid")
    suspend fun setSnooze(accountEmail: String, folderName: String, uid: Long, until: Long)

    @Query("UPDATE EmailMessage SET snoozedUntil = 0, isRead = 0 WHERE snoozedUntil != 0 AND snoozedUntil <= :now")
    suspend fun wakeDueSnoozed(now: Long): Int

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 100")
    suspend fun getRecentUnifiedMessages(): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 30")
    suspend fun getRecentInboxMessages(): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' ORDER BY dateMillis DESC, id DESC LIMIT 30")
    suspend fun searchMessages(query: String): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 10")
    fun getRecentUnifiedMessagesFlow(): Flow<List<EmailMessage>>

    // ---- Light preview projections (no body) for list/widget screens ----
    // These SELECT only the columns [EmailPreview] needs and read the stored
    // peekContent snippet, so list rows and the widget never load the full body.

    @Query("SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC")
    fun getMessagesPreviewFlow(accountEmail: String, folderName: String, now: Long): Flow<List<EmailPreview>>

    @Query("SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchMessagesPreviewFlow(accountEmail: String, folderName: String, query: String, now: Long): Flow<List<EmailPreview>>

    @Query("SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC")
    fun getUnifiedMessagesPreviewFlow(folderName: String, now: Long): Flow<List<EmailPreview>>

    @Query("SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchUnifiedMessagesPreviewFlow(folderName: String, query: String, now: Long): Flow<List<EmailPreview>>

    @Query("SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 100")
    suspend fun getRecentUnifiedPreview(): List<EmailPreview>

    // ---- One-time backfill for the dateMillis column ----

    /** Rows persisted before `dateMillis` existed; their `date` string needs parsing. */
    @Query("SELECT * FROM EmailMessage WHERE dateMillis = 0 LIMIT 1000")
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage>

    @Query("UPDATE EmailMessage SET dateMillis = :millis WHERE accountEmail = :accountEmail AND folderName = :folderName AND id = :uid")
    suspend fun updateDateMillis(accountEmail: String, folderName: String, uid: Long, millis: Long)

    // ---- One-time backfill for the peekContent column ----

    /** Rows with a body but no computed preview yet — either persisted before the
     *  peekContent column existed (default '') or inserted with an empty body since
     *  filled in. previewText's HTML strip can't be done in SQL, so recompute in
     *  Kotlin (see [PeekContentBackfill]). */
    @Query("SELECT * FROM EmailMessage WHERE peekContent = '' AND body IS NOT NULL AND body != '' LIMIT 1000")
    suspend fun getRowsWithEmptyPeek(): List<EmailMessage>

    @Query("UPDATE EmailMessage SET peekContent = :peek WHERE accountEmail = :accountEmail AND folderName = :folderName AND id = :uid")
    suspend fun updatePeekContent(accountEmail: String, folderName: String, uid: Long, peek: String)

    // Attachments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM Attachment WHERE accountEmail = :accountEmail AND messageId = :uid")
    suspend fun getAttachments(accountEmail: String, uid: Long): List<Attachment>

    @Query("UPDATE Attachment SET localUri = :uri WHERE accountEmail = :accountEmail AND messageId = :uid AND partId = :partId")
    suspend fun updateAttachmentLocalUri(accountEmail: String, uid: Long, partId: String, uri: String)

    @Query("UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail AND id = :uid AND folderName = :folderName")
    suspend fun updateReadStatus(accountEmail: String, folderName: String, uid: Long, isRead: Boolean)

    @Query("UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail AND id IN (:uids)")
    suspend fun updateBulkReadStatus(accountEmail: String, uids: List<Long>, isRead: Boolean)

    // ---- Outbox ----

    @Query("SELECT * FROM OutboxEntry ORDER BY createdAt ASC")
    fun getOutboxFlow(): Flow<List<OutboxEntry>>

    @Query("SELECT * FROM OutboxEntry ORDER BY createdAt ASC")
    suspend fun getOutbox(): List<OutboxEntry>

    @Query("SELECT COUNT(*) FROM OutboxEntry")
    suspend fun getOutboxCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxEntry(entry: OutboxEntry): Long

    @Delete
    suspend fun deleteOutboxEntry(entry: OutboxEntry)

    @Query("UPDATE OutboxEntry SET lastError = :error, attemptCount = :attempts, lastAttemptAt = :at WHERE id = :id")
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long)

    // ---- Drafts ----

    @Query("SELECT * FROM DraftEntry ORDER BY updatedAt DESC")
    fun getDraftsFlow(): Flow<List<DraftEntry>>

    @Query("SELECT * FROM DraftEntry WHERE id = :id")
    suspend fun getDraft(id: Long): DraftEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftEntry): Long

    @Query("DELETE FROM DraftEntry WHERE id = :id")
    suspend fun deleteDraftById(id: Long)
}
