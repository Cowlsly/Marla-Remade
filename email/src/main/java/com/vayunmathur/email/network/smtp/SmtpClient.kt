package com.vayunmathur.email.smtp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.ServerConfig
import com.vayunmathur.email.composer.InlineAttachment
import com.vayunmathur.email.imap.TrustAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level SMTP client — app-password primary + Outlook OAuth XOAUTH2 support.
 */
object SmtpClient {

    private const val TAG = "SmtpClient"

    suspend fun sendMessage(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        from: String? = null,
        asHtml: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val fromAddress = from ?: user
        val mimeString = MimeBuilder.buildMessage(
            context = context,
            from = fromAddress,
            to = to,
            subject = subject,
            body = body,
            asHtml = asHtml,
            cc = cc,
            bcc = bcc,
            attachments = attachments,
            inlineImages = inlineImages,
            inReplyTo = inReplyTo,
            references = references,
        )
        val useTrustAll = !TrustAll.isKnownHost(server.host)
        val conn = RawSmtpConnection(server, trustAll = useTrustAll)
        try {
            conn.connect()
            conn.ehlo("email.local")
            if (!server.useSsl && conn.hasCap("STARTTLS")) {
                conn.startTls(server.host)
                conn.ehlo("email.local")
            }

            when (auth) {
                is EmailManager.AuthType.OAuth -> {
                    try { conn.authXoauth2(user, auth.token) } catch (e: Exception) { Log.e(TAG, "XOAUTH2 SMTP failed", e); throw e }
                }
                is EmailManager.AuthType.Password -> {
                    if (conn.hasCap("AUTH=PLAIN") || conn.hasCap("PLAIN")) {
                        try { conn.authPlain(user, auth.value) } catch (e: Exception) {
                            if (conn.hasCap("LOGIN")) conn.authLogin(user, auth.value) else throw e
                        }
                    } else if (conn.hasCap("LOGIN")) {
                        conn.authLogin(user, auth.value)
                    } else {
                        try { conn.authPlain(user, auth.value) } catch (e: Exception) { conn.authLogin(user, auth.value) }
                    }
                }
            }

            conn.mailFrom(fromAddress)
            val allRecipients = mutableListOf<String>()
            allRecipients.addAll(splitAddresses(to))
            cc?.takeIf { it.isNotBlank() }?.let { allRecipients.addAll(splitAddresses(it)) }
            bcc?.takeIf { it.isNotBlank() }?.let { allRecipients.addAll(splitAddresses(it)) }
            for (rcpt in allRecipients.distinct()) { conn.rcptTo(rcpt) }
            conn.data(mimeString)
            conn.quit()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun splitAddresses(input: String): List<String> =
        input.split(',', ';').map { it.trim() }.filter { it.isNotBlank() && it.contains('@') }.map {
            Regex("<([^>]+)>").find(it)?.groupValues?.get(1)?.trim() ?: it.trim()
        }
}
