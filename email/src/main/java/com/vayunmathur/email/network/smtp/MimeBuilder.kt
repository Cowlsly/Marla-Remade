package com.vayunmathur.email.smtp

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vayunmathur.email.composer.InlineAttachment
import java.io.File
import java.security.SecureRandom

/**
 * Minimal MIME builder producing a raw RFC5322 message string with CRLF.
 * Replaces Jakarta MimeMessage + MimeMultipart building.
 *
 * Produces same structure as old EmailManager.sendMessage:
 * - If no inline images: multipart/mixed containing text + attachments
 * - If inline images: multipart/mixed containing multipart/related (text + inline image parts) + attachments
 *
 * Attachments read via ContentResolver.openInputStream or File fallback.
 * Base64 encoded chunked at 76 chars per RFC 2045.
 */

object MimeBuilder {

    private const val TAG = "MimeBuilder"

    data class BuildResult(val rawMessage: String)

    fun buildMessage(
        context: Context,
        from: String,
        to: String,
        subject: String,
        body: String,
        asHtml: Boolean,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
    ): String {
        val mixedBoundary = randomBoundary()
        val relatedBoundary = randomBoundary()

        val sb = StringBuilder()

        // Standard headers
        sb.append("From: $from\r\n")
        val toList = splitAddresses(to)
        sb.append("To: ${toList.joinToString(", ")}\r\n")
        cc?.takeIf { it.isNotBlank() }?.let {
            sb.append("Cc: ${splitAddresses(it).joinToString(", ")}\r\n")
        }
        bcc?.takeIf { it.isNotBlank() }?.let {
            sb.append("Bcc: ${splitAddresses(it).joinToString(", ")}\r\n")
        }
        sb.append("Subject: ${encodeHeaderIfNeeded(subject)}\r\n")
        sb.append("Date: ${java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        if (!inReplyTo.isNullOrBlank()) sb.append("In-Reply-To: $inReplyTo\r\n")
        if (!references.isNullOrBlank()) sb.append("References: $references\r\n")
        // Message-ID
        sb.append("Message-ID: <${System.currentTimeMillis()}.${SecureRandom().nextInt(1_000_000)}@${from.substringAfter('@').ifBlank { "email.local" }}>\r\n")

        if (inlineImages.isEmpty() && attachments.isEmpty()) {
            // Single part text
            if (asHtml) {
                sb.append("Content-Type: text/html; charset=utf-8\r\n")
                sb.append("Content-Transfer-Encoding: quoted-printable\r\n")
                sb.append("\r\n")
                sb.append(encodeQuotedPrintableForText(body))
                sb.append("\r\n")
            } else {
                sb.append("Content-Type: text/plain; charset=utf-8\r\n")
                sb.append("Content-Transfer-Encoding: quoted-printable\r\n")
                sb.append("\r\n")
                sb.append(encodeQuotedPrintableForText(body))
                sb.append("\r\n")
            }
            return sb.toString()
        }

        sb.append("Content-Type: multipart/mixed; boundary=\"$mixedBoundary\"\r\n")
        sb.append("\r\n")
        sb.append("This is a multi-part message in MIME format.\r\n")

        if (inlineImages.isEmpty()) {
            // Text part inside mixed
            sb.append("--$mixedBoundary\r\n")
            appendTextPart(sb, body, asHtml)
            // Attachments
            for (uri in attachments) {
                sb.append("--$mixedBoundary\r\n")
                appendAttachmentPart(sb, context, uri)
            }
            sb.append("--$mixedBoundary--\r\n")
        } else {
            // Related part wrapper inside mixed
            sb.append("--$mixedBoundary\r\n")
            sb.append("Content-Type: multipart/related; boundary=\"$relatedBoundary\"\r\n")
            sb.append("\r\n")

            sb.append("--$relatedBoundary\r\n")
            appendTextPart(sb, body, asHtml)

            for (inline in inlineImages) {
                sb.append("--$relatedBoundary\r\n")
                appendInlinePart(sb, context, inline)
            }
            sb.append("--$relatedBoundary--\r\n")

            // Regular attachments after related wrapper
            for (uri in attachments) {
                sb.append("--$mixedBoundary\r\n")
                appendAttachmentPart(sb, context, uri)
            }
            sb.append("--$mixedBoundary--\r\n")
        }

        return sb.toString()
    }

    private fun appendTextPart(sb: StringBuilder, body: String, asHtml: Boolean) {
        if (asHtml) {
            sb.append("Content-Type: text/html; charset=utf-8\r\n")
        } else {
            sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        }
        sb.append("Content-Transfer-Encoding: quoted-printable\r\n")
        sb.append("\r\n")
        sb.append(encodeQuotedPrintableForText(body))
        sb.append("\r\n")
    }

    private fun appendAttachmentPart(sb: StringBuilder, context: Context, uri: Uri) {
        val filename = queryFilename(context, uri) ?: uri.lastPathSegment ?: "attachment"
        val safeName = filename.replace("\"", "_").replace("\r", "").replace("\n", "")
        val mime = context.contentResolver.getType(uri) ?: guessMimeFromName(filename)
        Log.d(TAG, "Attachment $filename mime=$mime uri=$uri")
        sb.append("Content-Type: $mime; name=\"$safeName\"\r\n")
        sb.append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("\r\n")
        val bytes = readBytes(context, uri)
        sb.append(encodeBase64Chunked(bytes))
        sb.append("\r\n")
    }

    private fun appendInlinePart(sb: StringBuilder, context: Context, inline: InlineAttachment) {
        val mime = inline.mimeType.ifBlank { "image/jpeg" }
        val safeName = inline.fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        sb.append("Content-Type: $mime; name=\"$safeName\"\r\n")
        sb.append("Content-Disposition: inline; filename=\"$safeName\"\r\n")
        sb.append("Content-ID: <${inline.cid}>\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("\r\n")
        val bytes = readBytes(context, inline.uri)
        sb.append(encodeBase64Chunked(bytes))
        sb.append("\r\n")
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: File(uri.path ?: "").readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed for $uri: ${e.message}")
            try { File(uri.path ?: "").readBytes() } catch (_: Exception) { ByteArray(0) }
        }
    }

    private fun queryFilename(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            return try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) c.getString(idx) else c.getString(0)
                    } else null
                }
            } catch (_: Exception) { null }
        }
        return uri.path?.let { File(it).name }
    }

    private fun guessMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".html") -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun randomBoundary(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return "----=_Part_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).take(24).replace("-", "").replace("_", "") + "_" + System.currentTimeMillis().toString().takeLast(6)
    }

    fun encodeBase64Chunked(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val sb = StringBuilder()
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 76, b64.length)
            sb.append(b64, i, end).append("\r\n")
            i = end
        }
        return sb.toString()
    }

    fun encodeQuotedPrintableForText(text: String): String {
        // Simple QP encoder for text (utf-8 bytes)
        // Encode bytes that need encoding, wrap at 76.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var lineLen = 0
        for (b in bytes) {
            val ub = b.toInt() and 0xFF
            // Safe chars: 33-60,62-126 except = (61) ; also tab/space special at line end. Encode everything outside 33-126 except for some
            val needsEncode = ub < 33 || ub > 126 || ub == 61 || ub == 61 // always encode '='
            // Space and tab need encoding if at end of line, but we simplify: encode if needed or space? We'll leave space as is but encode at line wrap
            val encoded: String
            if (needsEncode) {
                if (ub == 10 || ub == 13) {
                    // preserve newline? Actually body line breaks should remain.
                    // We'll encode \r\n separately: we already split on \n earlier? For simplicity keep \r\n as literal line break in QP: text lines are terminated by \r\n outside encoding. So we must handle \r\n sequences.
                    // Our loop is over utf-8 bytes including newlines from body string which may be \n. We'll handle line breaks manually: if \n, emit \r\n and reset.
                    // But to keep structure simple, we detect \n here and emit \r\n newline.
                    if (ub == 10) { // \n -> soft? Actually in QP body, \n should be represented as \r\n line break (preserve)
                        sb.append("\r\n")
                        lineLen = 0
                        continue
                    }
                    if (ub == 13) continue // \r skip, \n will emit
                    encoded = "=" + "%02X".format(ub)
                } else {
                    encoded = "=" + "%02X".format(ub)
                }
            } else {
                // printable ascii
                encoded = ub.toChar().toString()
            }
            if (lineLen + encoded.length > 73) {
                sb.append("=\r\n")
                lineLen = 0
            }
            sb.append(encoded)
            lineLen += encoded.length
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun splitAddresses(input: String): List<String> {
        return input.split(',', ';').map { it.trim() }.filter { it.isNotBlank() && it.contains('@') }
    }

    private fun encodeHeaderIfNeeded(value: String): String {
        // If ascii-only, return as-is; else encode as RFC2047
        if (value.all { it.code in 32..126 }) return value
        // Encode subject via =?UTF-8?B?...
        val b64 = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Split into multiple encoded-words if too long (max 75 - "=?UTF-8?B??=" ~ 59 char b64 payload ~ 44 bytes)
        // Simplify: single word for now (subjects are usually < 100 chars)
        return "=?UTF-8?B?$b64?="
    }
}
