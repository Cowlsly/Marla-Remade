@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.email.data

import kotlin.concurrent.atomics.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.R
import com.vayunmathur.email.network.imap.ImapAuthException
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.network.imap.RawImapConnection
import com.vayunmathur.email.network.imap.TrustAll
import com.vayunmathur.email.data.resolveAuth
import com.vayunmathur.email.data.imapServer
import com.vayunmathur.email.data.loginUser
import com.vayunmathur.email.platform.AppLifecycleTracker
import com.vayunmathur.email.platform.EmailNotifications
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service — raw IMAP IDLE (no Jakarta), app-password + Outlook OAuth XOAUTH2.
 * Fix for BOOT_COMPLETED crash: start() returns Boolean, onStartCommand catches FGS exception.
 */
class ImapIdleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildOngoingNotification(), foregroundServiceType())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { startIdleLoops() }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun startIdleLoops() {
        val dao = EmailRepository.get(applicationContext).getDatabase().emailDao()
        val accounts = dao.getAccounts()
        if (accounts.isEmpty()) { stopSelf(); return }
        try { EmailSyncWorker.scheduleHourlyNonInboxSync(applicationContext) } catch (_: Throwable) {}
        val current = accounts.map { it.email }.toSet()
        accountJobs.keys.filter { it !in current }.forEach { accountJobs[it]?.cancel(); accountJobs.remove(it) }
        for (account in accounts) {
            accountJobs[account.email]?.cancel()
            accountJobs[account.email] = scope.launch { idleLoop(account) }
        }
    }

    private suspend fun idleLoop(account: EmailAccount) {
        var backoffMs = 2_000L
        val maxBackoffMs = 60_000L
        while (scope.coroutineContext.isActive) {
            try {
                runIdleSessionRaw(account)
                backoffMs = 2_000L
                delay(1_000L)
            } catch (e: ImapAuthException) {
                Log.w(TAG, "IDLE auth failed for ${account.email}; stop")
                return
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("auth") && msg.contains("fail")) { Log.w(TAG, "IDLE auth fail ${account.email}"); return }
                Log.w(TAG, "IDLE err ${account.email}: ${e.javaClass.simpleName}: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    private suspend fun runIdleSessionRaw(account: EmailAccount) = withContext(Dispatchers.IO) {
        val server = account.imapServer()
        val user = account.loginUser()
        val auth = account.resolveAuth(applicationContext)
        val useTrustAll = !TrustAll.isKnownHost(server.host)
        val rawConn = RawImapConnection(server, trustAll = useTrustAll)
        try {
            rawConn.connect()
            var caps = rawConn.capability()
            if (!server.useSsl && caps.has("STARTTLS")) {
                try { rawConn.startTls(); caps = rawConn.capability() } catch (e: Exception) { Log.w(TAG, "STARTTLS fail ${account.email}: ${e.message}") }
            }

            when (auth) {
                is EmailManager.AuthType.OAuth -> rawConn.authenticateXoauth2(user, auth.token)
                is EmailManager.AuthType.Password -> {
                    try { rawConn.login(user, auth.value) } catch (e: Exception) {
                        if (caps.has("AUTH=PLAIN")) rawConn.authenticatePlain(user, auth.value) else throw e
                    }
                }
            }

            val supportsIdle = caps.has("IDLE")
            Log.d(TAG, "Raw IDLE supports $supportsIdle for ${account.email}")

            if (!supportsIdle) {
                while (scope.coroutineContext.isActive) {
                    delay(FALLBACK_NO_IDLE_POLL_MS)
                    try {
                        val dao = EmailRepository.get(applicationContext).getDatabase().emailDao()
                        val known = dao.getKnownUids(account.email, "INBOX").toSet()
                        val deleted = dao.getDeletedUids(account.email, "INBOX").toSet()
                        val (msgs, atts) = ImapClient.fetchMessagesInConnection(rawConn, account.email, "INBOX", 50, 0, false, known + deleted, applicationContext)
                        if (msgs.isNotEmpty()) { dao.insertMessages(msgs); if (atts.isNotEmpty()) dao.insertAttachments(atts); postNewMailNotification(account.email, msgs) }
                        syncReadStatusPullRaw(applicationContext, account, known)
                    } catch (t: Throwable) { Log.w(TAG, "Raw poll fail: ${t.message}") }
                }
                return@withContext
            }

            val dao = EmailRepository.get(applicationContext).getDatabase().emailDao()
            while (scope.coroutineContext.isActive) {
                val sel = rawConn.select("INBOX")
                Log.d(TAG, "SELECT INBOX ${account.email} exists=${sel.exists}")
                try {
                    val folders = rawConn.list("", "*").map { entry ->
                        val fullName = entry.mailbox
                        val delim = entry.delimiter ?: "/"
                        val nm = if (fullName.contains(delim)) fullName.substringAfterLast(delim) else fullName.substringAfterLast('/')
                        val parent = fullName.lastIndexOf(delim).let { if (it > 0) fullName.substring(0, it) else null }
                        val holds = !entry.flags.any { f -> f.equals("\\Noselect", ignoreCase = true) }
                        EmailFolder(account.email, fullName, nm.ifBlank { fullName }, parent, holds, delim)
                    }
                    dao.insertFolders(folders)
                } catch (t: Throwable) { Log.w(TAG, "folder discovery fail: ${t.message}") }

                var sawNewMail = false
                var sawExpunge = false
                var sawFlags = false
                val expungedSeqs = mutableListOf<Int>()
                var needReopen = false
                var isProactiveRefresh = false

                while (scope.coroutineContext.isActive && !needReopen) {
                    val idleTag = rawConn.sendIdle()
                    Log.d(TAG, "IDLE start ${account.email} tag=$idleTag")

                    val watchdog = scope.launch {
                        delay(IDLE_REFRESH_MS)
                        if (isActive) { Log.d(TAG, "proactive refresh ${account.email}"); isProactiveRefresh = true; try { rawConn.sendIdleDone() } catch (_: Throwable) {} }
                    }

                    var idleEnded = false
                    var idleLoopActive = true
                    while (idleLoopActive && scope.coroutineContext.isActive) {
                        val line = withContext(Dispatchers.IO) { try { rawConn.readIdleLine() } catch (_: Exception) { null } } ?: break
                        Log.d(TAG, "IDLE line ${account.email}: $line")
                        if (line.startsWith(idleTag)) { idleEnded = true; idleLoopActive = false; break }
                        when {
                            Regex("""^\* (\d+) EXISTS""").containsMatchIn(line) -> sawNewMail = true
                            Regex("""^\* (\d+) EXPUNGE""").containsMatchIn(line) -> {
                                val seq = Regex("""^\* (\d+) EXPUNGE""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                if (seq != -1) expungedSeqs.add(seq); sawExpunge = true
                            }
                            line.contains("FETCH") && line.contains("FLAGS") -> sawFlags = true
                        }
                        if (sawNewMail || sawExpunge || sawFlags) { try { rawConn.sendIdleDone() } catch (_: Throwable) {} }
                    }

                    if (!idleEnded) { try { rawConn.sendIdleDone() } catch (_: Throwable) {}; try { rawConn.readIdleResponseForTag(idleTag) } catch (_: Throwable) {} }

                    watchdog.cancel()

                    if (isProactiveRefresh) { Log.d(TAG, "24-min refresh ${account.email}"); isProactiveRefresh = false; needReopen = true; delay(200L); break }

                    if (sawNewMail && !needReopen) {
                        sawNewMail = false
                        try {
                            val known = dao.getKnownUids(account.email, "INBOX").toSet()
                            val deleted = dao.getDeletedUids(account.email, "INBOX").toSet()
                            val (msgs, atts) = ImapClient.fetchMessagesInConnection(rawConn, account.email, "INBOX", 50, 0, false, known + deleted, applicationContext)
                            if (msgs.isNotEmpty()) { dao.insertMessages(msgs); if (atts.isNotEmpty()) dao.insertAttachments(atts); postNewMailNotification(account.email, msgs) }
                        } catch (t: Throwable) { Log.w(TAG, "quick fetch fail: ${t.message}") }
                    }

                    if (sawExpunge && expungedSeqs.isNotEmpty() && !needReopen) {
                        sawExpunge = false; expungedSeqs.clear(); try { EmailWidget().updateAll(applicationContext) } catch (_: Throwable) {}
                    }

                    if (sawFlags && !needReopen) {
                        sawFlags = false
                        try { syncReadStatusPullRaw(applicationContext, account, dao.getKnownUids(account.email, "INBOX").toSet()) } catch (t: Throwable) { Log.w(TAG, "flag sync fail: ${t.message}") }
                    }

                    if (needReopen) break
                    if (scope.coroutineContext.isActive) delay(500L)
                }
                if (!scope.coroutineContext.isActive) break
                if (needReopen) continue
                delay(500L)
            }
        } catch (e: Exception) { try { rawConn.close() } catch (_: Throwable) {}; throw e }
    }

    private suspend fun postNewMailNotification(accountEmail: String, messages: List<com.vayunmathur.email.data.EmailMessage>) {
        if (messages.isEmpty()) return
        val ctx = applicationContext ?: return
        val prefs = ctx.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("$accountEmail::INBOX", -1L)
        val notifiable = if (lastSeen == -1L) messages else messages.filter { it.id > lastSeen }
        if (notifiable.isNotEmpty() && !AppLifecycleTracker.isAppInForeground) {
            EmailNotifications.postForNewMessages(ctx, accountEmail, notifiable)
        }
        val maxUid = messages.maxOfOrNull { it.id } ?: lastSeen
        if (maxUid > lastSeen) prefs.edit { putLong("$accountEmail::INBOX", maxUid) }
        try { EmailWidget().updateAll(ctx) } catch (t: Throwable) { Log.w(TAG, "widget fail ${t.message}") }
    }

    private suspend fun syncReadStatusPullRaw(context: Context, account: EmailAccount, knownUids: Set<Long>) {
        if (knownUids.isEmpty()) return
        try {
            val dao = EmailRepository.get(context).getDatabase().emailDao()
            val uidsToCheck = knownUids.sortedDescending().take(50)
            val auth = account.resolveAuth(context)
            ImapClient.withConnection(account.imapServer(), account.loginUser(), auth) { conn ->
                conn.select("INBOX")
                val results = conn.uidFetchHeaders(uidsToCheck.joinToString(","))
                for (r in results) {
                    val isRead = r.flags.any { it.equals("\\Seen", ignoreCase = true) }
                    try { dao.updateReadStatus(account.email, "INBOX", r.uid, isRead) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildOngoingNotification(): Notification {
        ensureChannel(applicationContext)
        return androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listening_for_new_mail))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
    }

    companion object {
        private const val TAG = "ImapIdle"
        private const val CHANNEL_ID = "imap_idle"
        private const val NOTIFICATION_ID = 9001
        const val IDLE_REFRESH_MS = 24L * 60 * 1000
        const val FALLBACK_NO_IDLE_POLL_MS = 5L * 60 * 1000

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.email_sync_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.email_sync_channel_desc)
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context): Boolean {
            return try {
                val intent = Intent(context, ImapIdleService::class.java)
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}", e); false
            }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ImapIdleService::class.java)) }
    }
}
