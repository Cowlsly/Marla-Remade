package com.vayunmathur.email.imap

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.vayunmathur.email.Attachment
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.ServerConfig
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level IMAP client using raw sockets — app-password primary, Outlook OAuth via XOAUTH2.
 */
object ImapClient {

    private const val TAG = "ImapClient"

    /**
     * Canonical inbox mailbox name. RFC 3501 §5.1 defines INBOX case-insensitively,
     * so servers are free to report it however they like — Gmail/Yahoo/iCloud return
     * `INBOX`, but Office 365 / Exchange returns `Inbox`. Every inbox query in the app
     * (per-account *and* the unified inbox) matches on this exact literal, and SQLite
     * TEXT comparison is case-sensitive, so all persisted folder names must be folded
     * to this constant via [canonicalizeMailbox] before they hit the DB.
     */
    const val INBOX = "INBOX"

    /**
     * Folds a server-reported mailbox path to its canonical form, so `Inbox` and
     * `Inbox/Receipts` (Office 365) are stored the same way as `INBOX` and
     * `INBOX/Receipts` (Gmail). Non-inbox mailboxes are returned untouched — only the
     * inbox has spec-mandated case-insensitivity.
     *
     * @param delimiter the server's hierarchy delimiter; when unknown, the two common
     *   ones (`/` and `.`) are both accepted.
     */
    fun canonicalizeMailbox(mailbox: String, delimiter: String? = null): String {
        if (mailbox.equals(INBOX, ignoreCase = true)) return INBOX
        if (mailbox.length <= INBOX.length) return mailbox
        if (!mailbox.regionMatches(0, INBOX, 0, INBOX.length, ignoreCase = true)) return mailbox
        val delims = if (delimiter.isNullOrEmpty()) listOf("/", ".") else listOf(delimiter)
        return if (delims.any { mailbox.startsWith(it, INBOX.length) }) {
            INBOX + mailbox.substring(INBOX.length)
        } else {
            mailbox
        }
    }

    val GMAIL_VIRTUAL_FOLDERS = setOf(
        "[Gmail]/All Mail",
        "[Gmail]/Important",
        "[Gmail]/Starred",
        "[Gmail]/Chats",
    )

    suspend fun <T> withConnection(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        trustAll: Boolean = false,
        block: suspend (RawImapConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        val useTrustAll = trustAll || !TrustAll.isKnownHost(server.host)
        val conn = RawImapConnection(server, trustAll = useTrustAll)
        try {
            conn.connect()
            var caps = conn.capability()

            if (!server.useSsl && caps.has("STARTTLS")) {
                try {
                    conn.startTls()
                    caps = conn.capability()
                } catch (e: Exception) {
                    Log.w(TAG, "STARTTLS failed ${server.host}: ${e.message}")
                }
            } else if (!server.useSsl && caps.has("LOGINDISABLED")) {
                try {
                    conn.startTls()
                    caps = conn.capability()
                } catch (e: Exception) {
                    Log.w(TAG, "STARTTLS required failed: ${e.message}")
                }
            }

            when (auth) {
                is EmailManager.AuthType.OAuth -> {
                    conn.authenticateXoauth2(user, auth.token)
                }
                is EmailManager.AuthType.Password -> {
                    try {
                        conn.login(user, auth.value)
                    } catch (e: Exception) {
                        if (caps.has("AUTH=PLAIN")) {
                            try { conn.authenticatePlain(user, auth.value) } catch (e2: Exception) { Log.w(TAG, "PLAIN fallback failed: ${e2.message}"); throw e }
                        } else throw e
                    }
                }
            }

            block(conn)
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun fetchFolders(server: ServerConfig, user: String, auth: EmailManager.AuthType): List<EmailFolder> =
        withConnection(server, user, auth) { conn ->
            val entries = conn.list("", "*")
            entries.map { entry ->
                val delim = entry.delimiter ?: "/"
                // Identify the folder by its canonical path, but keep the server's own
                // casing for the drawer label so Outlook still reads "Inbox", not "INBOX".
                val fullName = canonicalizeMailbox(entry.mailbox, delim)
                val displayPath = entry.mailbox
                val name = if (displayPath.contains(delim)) displayPath.substringAfterLast(delim) else displayPath.substringAfterLast('/')
                val parent = fullName.lastIndexOf(delim).let { if (it > 0) fullName.substring(0, it).takeIf { it.isNotEmpty() } else null }
                val holds = !entry.flags.any { it.equals("\\Noselect", ignoreCase = true) }
                EmailFolder(accountEmail = user, fullName = fullName, name = name.ifBlank { fullName }, parentFullName = parent, holdsMessages = holds, delimiter = delim)
            }
        }

    suspend fun fetchMessages(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        limit: Int,
        offset: Int = 0,
        fetchBodies: Boolean = false,
        skipUids: Set<Long> = emptySet(),
        context: Context? = null,
    ): Pair<List<EmailMessage>, List<Attachment>> =
        withConnection(server, user, auth) { conn ->
            fetchMessagesInConnection(conn, user, folderName, limit, offset, fetchBodies, skipUids, context)
        }

    suspend fun fetchMessagesInConnection(
        conn: RawImapConnection,
        user: String,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean,
        skipUids: Set<Long>,
        context: Context?,
    ): Pair<List<EmailMessage>, List<Attachment>> {
        // SELECT with the exact name the server gave us, but persist under the
        // canonical one so an Office 365 "Inbox" lands in the same bucket as a
        // Gmail "INBOX" (see [canonicalizeMailbox]).
        val sel = conn.select(folderName)
        val storedFolderName = canonicalizeMailbox(folderName)
        val total = sel.exists
        if (total == 0) return Pair(emptyList(), emptyList())
        val end = (total - offset).coerceAtLeast(1)
        val start = (end - limit + 1).coerceAtLeast(1)
        if (end < 1 || start > end) return Pair(emptyList(), emptyList())
        val seqSet = "$start:$end"
        val fetchResults = conn.fetchHeadersForSeq(seqSet)
        val filtered = fetchResults.filter { it.uid !in skipUids }.reversed()
        if (filtered.isEmpty()) return Pair(emptyList(), emptyList())

        val messages = mutableListOf<EmailMessage>()
        val allAttachments = mutableListOf<Attachment>()
        for (fr in filtered) {
            val headerMap = fr.headerBytes?.let { MimeParser.parseHeaderBlockBytes(it) } ?: emptyMap()
            val from = headerMap["from"]?.let { MimeParser.decodeHeader(it) } ?: ""
            val to = headerMap["to"]?.let { MimeParser.decodeHeader(it) }
            val cc = headerMap["cc"]?.let { MimeParser.decodeHeader(it) }
            val subject = headerMap["subject"]?.let { MimeParser.decodeHeader(it) } ?: "(no subject)"
            val dateHeader = headerMap["date"] ?: ""
            val dateMillis = if (dateHeader.isNotBlank()) MimeParser.parseDateToMillis(dateHeader) else MimeParser.parseDateToMillis(fr.internalDate ?: "")
            val isRead = fr.flags.any { it.equals("\\Seen", ignoreCase = true) }
            val gmThrid = headerMap["x-gm-thrid"]

            var body: String? = null
            var isHtmlFlag = false
            var hasAtt = false
            if (fetchBodies && fr.bodyBytes != null) {
                val triple = try { MimeParser.parseMessage(fr.bodyBytes, fr.uid, user, storedFolderName, context) } catch (_: Exception) { Triple<String?, Boolean, List<Attachment>>(null, false, emptyList()) }
                body = triple.first
                isHtmlFlag = triple.second
                allAttachments.addAll(triple.third)
                hasAtt = triple.third.isNotEmpty()
            }

            messages.add(
                EmailMessage(
                    accountEmail = user,
                    folderName = storedFolderName,
                    id = fr.uid,
                    serverId = headerMap["message-id"],
                    threadId = gmThrid ?: fr.uid.toString(),
                    subject = subject,
                    from = from,
                    to = to,
                    cc = cc,
                    date = dateHeader.ifBlank { fr.internalDate ?: "" },
                    dateMillis = dateMillis,
                    body = body,
                    isHtml = isHtmlFlag,
                    isRead = isRead,
                    references = headerMap["references"] ?: headerMap["in-reply-to"],
                    hasAttachments = hasAtt,
                    listUnsubscribe = headerMap["list-unsubscribe"],
                    listUnsubscribePost = headerMap["list-unsubscribe-post"]
                )
            )
        }
        return Pair(messages, allAttachments)
    }

    suspend fun fetchMessageBody(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        context: Context? = null,
    ): Triple<String?, Boolean, List<Attachment>> = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.uidFetchFullSet(uid.toString()).firstOrNull()
            ?: return@withConnection Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
        val bytes = res.bodyBytes ?: return@withConnection Triple(null, false, emptyList())
        try { MimeParser.parseMessage(bytes, uid, user, canonicalizeMailbox(folderName), context) } catch (e: Exception) { Log.w(TAG, "parse failed ${e.message}"); Triple(null, false, emptyList()) }
    }

    suspend fun fetchFullForBody(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
    ): EmailManager.FullFetchResult = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.uidFetchFullSet(uid.toString()).firstOrNull() ?: return@withConnection EmailManager.FullFetchResult(Triple(null, false, emptyList()))
        val bytes = res.bodyBytes ?: return@withConnection EmailManager.FullFetchResult(Triple(null, false, emptyList()))
        val triple = try { MimeParser.parseMessage(bytes, uid, user, canonicalizeMailbox(folderName), context.applicationContext) } catch (e: Exception) { Log.w(TAG, "full parse fail ${e.message}"); Triple<String?, Boolean, List<Attachment>>(null, false, emptyList()) }
        EmailManager.FullFetchResult(triple)
    }

    suspend fun fetchCidMap(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
    ): Map<String, File> = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.uidFetchFullSet(uid.toString()).firstOrNull() ?: return@withConnection emptyMap()
        val bytes = res.bodyBytes ?: return@withConnection emptyMap()
        try { MimeParser.extractCidMap(context, bytes, uid) } catch (_: Exception) { emptyMap() }
    }

    suspend fun setSeenFlag(server: ServerConfig, user: String, auth: EmailManager.AuthType, folderName: String, uid: Long, seen: Boolean) =
        withConnection(server, user, auth) { it.select(folderName); it.uidStoreFlags(uid, "\\Seen", add = seen) }

    suspend fun deleteMessage(server: ServerConfig, user: String, auth: EmailManager.AuthType, folderName: String, uid: Long) =
        withConnection(server, user, auth) { it.select(folderName); it.uidExpunge(uid) }

    suspend fun downloadAttachment(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        partId: String,
        fileName: String,
        mimeType: String,
    ): String = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val section = partIdToSection(partId)
        val bytes = conn.uidFetchPartBytes(uid, section) ?: throw IllegalStateException("Part $partId ($section) not found UID $uid")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Downloads entry failed")
        try {
            resolver.openOutputStream(itemUri)?.use { it.write(bytes) } ?: throw IllegalStateException("openOutput")
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } catch (e: Exception) { resolver.delete(itemUri, null, null); throw e }
        itemUri.toString()
    }

    suspend fun fetchRawMessageBytes(server: ServerConfig, user: String, auth: EmailManager.AuthType, folderName: String, uid: Long): ByteArray =
        withConnection(server, user, auth) { conn ->
            conn.select(folderName)
            val r = conn.uidFetchFullSet(uid.toString()).firstOrNull() ?: throw IllegalStateException("UID $uid not found $folderName")
            r.bodyBytes ?: throw IllegalStateException("Empty $uid")
        }

    suspend fun fetchRawMessageTo(server: ServerConfig, user: String, auth: EmailManager.AuthType, folderName: String, uid: Long, output: OutputStream) =
        withConnection(server, user, auth) { conn ->
            conn.select(folderName)
            val r = conn.uidFetchFullSet(uid.toString()).firstOrNull() ?: throw IllegalStateException("UID $uid not found $folderName")
            output.write(r.bodyBytes ?: throw IllegalStateException("Empty $uid"))
        }

    suspend fun quickInboxFetchRaw(
        conn: RawImapConnection,
        accountEmail: String,
        known: Set<Long>,
        deleted: Set<Long>,
    ): Pair<List<EmailMessage>, List<Attachment>> =
        fetchMessagesInConnection(conn, accountEmail, "INBOX", 50, 0, false, known + deleted, null)

    fun partIdToSection(partId: String): String {
        if (partId == "0" || partId.isBlank()) return "1"
        val parts = partId.split(".")
        val mapped = parts.mapNotNull { it.toIntOrNull()?.let { idx -> (idx + 1).toString() } }
        if (mapped.isEmpty()) return "1"
        return mapped.joinToString(".")
    }
}
