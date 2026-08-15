package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.*
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.data.imapServer
import com.vayunmathur.email.data.loginUser
import com.vayunmathur.email.data.resolveAuth
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

/**
 * Sync worker — now uses raw IMAP client only (no Jakarta dependency).
 * - Non-INBOX hourly via WorkManager
 * - Full one-off for folder discovery + INBOX + body backfill
 * - Notification baseline per account/folder
 */
class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val nonInboxOnly = inputData.getBoolean(KEY_NON_INBOX_ONLY, false)
        val repository = EmailRepository.get(applicationContext)
        val dao = repository.getDatabase().emailDao()
        val accounts = dao.getAccounts()

        if (accounts.isEmpty()) {
            Log.d("EmailSync", "No accounts to sync")
            return Result.success()
        }

        EmailSyncState.start()
        var hasErrors = false
        var accountsProcessed = 0

        for (account in accounts) {
            try {
                Log.d("EmailSync", ">>> RAW Starting sync for ${account.email} (nonInboxOnly=$nonInboxOnly)")
                val auth = account.resolveAuth(applicationContext)

                val folders = ImapClient.fetchFolders(account.imapServer(), account.loginUser(), auth)
                dao.insertFolders(folders)
                Log.d("EmailSync", "Synced ${folders.size} folders.")

                val skipSet = if (account.provider == PROVIDER_GMAIL) ImapClient.GMAIL_VIRTUAL_FOLDERS else emptySet()
                val messageFolders = if (nonInboxOnly) {
                    folders.filter { it.holdsMessages && it.fullName !in skipSet && it.fullName != ImapClient.INBOX }
                } else {
                    folders.filter { it.holdsMessages && it.fullName !in skipSet }
                }
                val totalUnits = (accounts.size * messageFolders.size).coerceAtLeast(1)

                for ((index, folder) in messageFolders.withIndex()) {
                    try {
                        val knownUids = dao.getKnownUids(account.email, folder.fullName).toSet()
                        val deletedUids = dao.getDeletedUids(account.email, folder.fullName).toSet()
                        val (messages, attachments) = ImapClient.fetchMessages(
                            server = account.imapServer(),
                            user = account.loginUser(),
                            auth = auth,
                            folderName = folder.fullName,
                            limit = 50,
                            fetchBodies = false,
                            skipUids = knownUids + deletedUids,
                            context = applicationContext,
                        )
                        if (messages.isNotEmpty()) dao.insertMessages(messages)
                        if (attachments.isNotEmpty()) dao.insertAttachments(attachments)

                        if (!nonInboxOnly) {
                            if (knownUids.isNotEmpty() && folder.fullName == ImapClient.INBOX) {
                                syncReadStatusRaw(applicationContext, account, folder.fullName, knownUids)
                            }

                            if (folder.fullName == ImapClient.INBOX && messages.isNotEmpty()) {
                                val lastSeen = lastSeenPrefs(applicationContext)
                                    .getLong(lastSeenKey(account.email, folder.fullName), -1L)
                                if (lastSeen >= 0L && !com.vayunmathur.email.platform.AppLifecycleTracker.isAppInForeground) {
                                    val notifiable = messages.filter { it.id > lastSeen }
                                    com.vayunmathur.email.platform.EmailNotifications.postForNewMessages(
                                        applicationContext, account.email, notifiable,
                                    )
                                }
                                val maxUid = messages.maxOf { it.id }
                                if (maxUid > lastSeen) {
                                    lastSeenPrefs(applicationContext).edit {
                                        putLong(lastSeenKey(account.email, folder.fullName), maxUid)
                                    }
                                }
                            }
                        }

                        Log.d("EmailSync", "[${index + 1}/${messageFolders.size}] ${folder.fullName}: ${messages.size} new (skipped ${knownUids.size}).")
                    } catch (e: Exception) {
                        Log.e("EmailSync", "   x Failed folder ${folder.fullName}", e)
                    }
                    val unitsDone = accountsProcessed * messageFolders.size + (index + 1)
                    EmailSyncState.setProgress(unitsDone.toFloat() / totalUnits)
                }

                if (!nonInboxOnly) {
                    val missing = dao.getMessagesWithoutBody(account.email, BACKFILL_LIMIT)
                    if (missing.isNotEmpty()) {
                        Log.d("EmailSync", "Body backfill: ${missing.size} message(s)")
                        EmailSyncState.setProgress(0f)
                        for ((idx, msg) in missing.withIndex()) {
                            if (isStopped) {
                                Log.d("EmailSync", "Backfill stopped at ${idx}/${missing.size}")
                                break
                            }
                            try {
                                val current = dao.getMessage(msg.accountEmail, msg.folderName, msg.id) ?: continue
                                if (current.body != null) continue
                                val (body, isHtml, attachments) = ImapClient.fetchMessageBody(
                                    server = account.imapServer(),
                                    user = account.loginUser(),
                                    auth = auth,
                                    folderName = msg.folderName,
                                    uid = msg.id,
                                    context = applicationContext,
                                )
                                if (body != null || attachments.isNotEmpty()) {
                                    dao.insertMessages(listOf(current.copy(
                                        body = body,
                                        isHtml = isHtml,
                                        hasAttachments = attachments.isNotEmpty(),
                                    )))
                                    if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                                }
                            } catch (e: Exception) {
                                Log.w("EmailSync", "   x Backfill failed for UID ${msg.id}: ${e.message}")
                            }
                            EmailSyncState.setProgress((idx + 1f) / missing.size)
                        }
                        Log.d("EmailSync", "Backfill done for ${account.email}")
                    }
                }

                Log.d("EmailSync", "<<< Completed RAW sync for ${account.email}")
            } catch (e: Exception) {
                Log.e("EmailSync", "Failed to sync account ${account.email}", e)
                hasErrors = true
            }
            accountsProcessed++
        }

        if (!nonInboxOnly) EmailWidget().updateAll(applicationContext)
        EmailSyncState.finish()
        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"
        private const val HOURLY_NON_INBOX_WORK_NAME = "EmailNonInboxHourlySync"
        private const val KEY_NON_INBOX_ONLY = "non_inbox_only"

        /** Sync read status via raw FETCH FLAGS */
        private suspend fun syncReadStatusRaw(
            context: Context,
            account: com.vayunmathur.email.data.EmailAccount,
            folderName: String,
            knownUids: Set<Long>,
        ) {
            try {
                val db = EmailRepository.get(context).getDatabase()
                val dao = db.emailDao()
                val uidsToCheck = knownUids.sortedDescending().take(50)
                if (uidsToCheck.isEmpty()) return
                val auth = account.resolveAuth(context)
                val server = account.imapServer()
                val user = account.loginUser()

                ImapClient.withConnection(server, user, auth) { conn ->
                    conn.select(folderName)
                    val uidSet = uidsToCheck.joinToString(",")
                    val results = conn.uidFetchHeaders(uidSet)
                    for (r in results) {
                        val isRead = r.flags.any { it.equals("\\Seen", ignoreCase = true) }
                        try { dao.updateReadStatus(account.email, folderName, r.uid, isRead) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        private val GMAIL_VIRTUAL_FOLDERS = setOf(
            "[Gmail]/All Mail",
            "[Gmail]/Important",
            "[Gmail]/Starred",
            "[Gmail]/Chats",
        )

        private const val BACKFILL_LIMIT = 200

        private fun lastSeenPrefs(context: Context) =
            context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)

        private fun lastSeenKey(accountEmail: String, folderName: String) =
            "$accountEmail::$folderName"

        @Deprecated("IDLE-only push: periodic polling removed.")
        fun schedulePeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(HOURLY_NON_INBOX_WORK_NAME)
        }

        fun scheduleHourlyNonInboxSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val data = Data.Builder().putBoolean(KEY_NON_INBOX_ONLY, true).build()
            val req = PeriodicWorkRequestBuilder<EmailSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HOURLY_NON_INBOX_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun runOneOffSync(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_NON_INBOX_ONLY, false).build())
                .build()
            WorkManager.getInstance(context).enqueue(syncRequest)
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(HOURLY_NON_INBOX_WORK_NAME)
        }
    }
}
