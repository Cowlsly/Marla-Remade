package com.vayunmathur.email.util

import android.content.Context
import android.net.Uri
import com.vayunmathur.email.network.imap.MimeParser
import com.vayunmathur.email.data.EmailMessage
import java.io.File

data class EmlAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class ParsedEml(
    val message: EmailMessage,
    val emlAttachments: List<EmlAttachment>,
    val inlineCidMap: Map<String, File> = emptyMap(),
)

object EmlUtils {

    /**
     * Parse EML file via raw [MimeParser] (no Jakarta dependency).
     * Returns [ParsedEml] expected by EmlViewerScreen.
     */
    fun parseEml(context: Context, uri: Uri): ParsedEml {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open InputStream for $uri")
        val syntheticId = uri.toString().hashCode().toLong()
        return MimeParser.parseEmlToParsedMessage(bytes, syntheticId, context)
    }

    fun sanitizeFileName(input: String, fallback: String = "email"): String {
        var base = input.ifBlank { fallback }.trim()
        if (base.endsWith(".eml", ignoreCase = true)) base = base.dropLast(4)
        base = base.take(60).trim()
        base = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        base = base.replace(Regex("_+"), "_")
        base = base.trim('_', '.', ' ')
        if (base.isBlank()) base = fallback
        return "$base.eml"
    }
}
