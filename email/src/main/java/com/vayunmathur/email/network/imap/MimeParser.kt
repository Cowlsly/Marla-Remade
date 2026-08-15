package com.vayunmathur.email.imap

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.email.Attachment
import com.vayunmathur.email.util.EmlAttachment
import com.vayunmathur.email.util.ParsedEml
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Minimal MIME parser replacing Jakarta Mail's MimeMessage walk.
 * Handles header unfolding, Content-Type params, CTE (base64/qp), RFC2047, charset, multipart split, CID cache.
 */
object MimeParser {

    private const val TAG = "MimeParser"

    data class ContentTypeInfo(val mainType: String, val subType: String, val params: Map<String, String>) {
        val fullType: String get() = "$mainType/$subType"
        val isMultipart: Boolean get() = mainType.equals("multipart", ignoreCase = true)
        val isText: Boolean get() = mainType.equals("text", ignoreCase = true)
        val isImage: Boolean get() = mainType.equals("image", ignoreCase = true)
    }

    data class DispositionInfo(val type: String, val params: Map<String, String>) {
        val isAttachment: Boolean get() = type.equals("attachment", ignoreCase = true)
        val isInline: Boolean get() = type.equals("inline", ignoreCase = true)
        val filename: String? get() = params["filename"] ?: params["name"]
    }

    data class ParsedPart(
        val headers: Map<String, String>,
        val rawHeaderBlock: String,
        val contentType: ContentTypeInfo,
        val disposition: DispositionInfo?,
        val contentId: String?,
        val cte: String,
        val rawBodyBytes: ByteArray,
        val decodedBytes: ByteArray,
        val children: List<ParsedPart> = emptyList(),
        val partId: String = "",
        var bodyText: String? = null,
    )

    // ---- Public API ----

    fun parseMessage(
        rfc822Bytes: ByteArray,
        uid: Long,
        accountEmail: String,
        folderName: String,
        context: Context? = null,
    ): Triple<String?, Boolean, List<Attachment>> {
        val root = parsePart(rfc822Bytes, "")
        val triple = collectBodyAndAttachments(root, uid, accountEmail, folderName)
        if (context != null) {
            try { extractCidMapInternal(context, root, uid) } catch (_: Exception) {}
        }
        return triple
    }

    fun parseHeaderBlockBytes(headerBytes: ByteArray): Map<String, String> {
        val str = try { String(headerBytes, Charsets.UTF_8) } catch (_: Exception) { String(headerBytes, Charsets.ISO_8859_1) }
        return parseHeadersString(str)
    }

    fun parsePart(rawPartBytes: ByteArray, partId: String): ParsedPart {
        val sep = findHeaderBodySeparator(rawPartBytes)
        val headerBytes: ByteArray
        val bodyBytes: ByteArray
        if (sep != null) {
            val (sepStart, sepLen) = sep
            headerBytes = rawPartBytes.copyOfRange(0, sepStart)
            bodyBytes = if (sepStart + sepLen < rawPartBytes.size) rawPartBytes.copyOfRange(sepStart + sepLen, rawPartBytes.size) else ByteArray(0)
        } else {
            headerBytes = rawPartBytes
            bodyBytes = ByteArray(0)
        }
        val headerStr = String(headerBytes, Charsets.ISO_8859_1)
        val headers = parseHeadersString(headerStr)
        val ct = parseContentType(headers["content-type"])
        val disp = parseDisposition(headers["content-disposition"])
        val cte = (headers["content-transfer-encoding"] ?: "7bit").lowercase().trim()
        val cidRaw = headers["content-id"]?.let { extractCid(it) }

        if (ct.isMultipart) {
            val boundary = ct.params["boundary"]
            if (boundary.isNullOrBlank()) {
                return ParsedPart(headers, headerStr, ct, disp, cidRaw, cte, bodyBytes, ByteArray(0), emptyList(), partId)
            }
            val subPartsBytes = splitMultipart(bodyBytes, boundary)
            val children = subPartsBytes.mapIndexed { idx, b ->
                val childId = if (partId.isEmpty()) idx.toString() else "$partId.$idx"
                parsePart(b, childId)
            }
            return ParsedPart(headers, headerStr, ct, disp, cidRaw, cte, bodyBytes, ByteArray(0), children, partId)
        }

        val decoded = decodeCte(bodyBytes, cte)
        var bodyText: String? = null
        if (ct.isText) {
            val charset = ct.params["charset"] ?: "utf-8"
            bodyText = decodeCharset(decoded, charset)
        }

        return ParsedPart(headers, headerStr, ct, disp, cidRaw, cte, bodyBytes, decoded, emptyList(), partId, bodyText)
    }

    fun collectBodyAndAttachments(root: ParsedPart, uid: Long, accountEmail: String, folderName: String): Triple<String?, Boolean, List<Attachment>> {
        var finalBody: String? = null
        var finalIsHtml = false
        val attachments = mutableListOf<Attachment>()

        fun extractSingle(part: ParsedPart): Triple<String?, Boolean, List<Attachment>> {
            val ct = part.contentType
            val cid = part.contentId
            val isInlineImage = cid != null && (ct.isImage || part.disposition?.isInline == true || ct.fullType.startsWith("image/", true))
            if (isInlineImage) return Triple(null, false, emptyList())
            val filename = part.disposition?.filename ?: ct.params["name"]
            if (!filename.isNullOrBlank() || part.disposition?.isAttachment == true) {
                val fn = sanitizeFilename(filename ?: "unnamed")
                val mime = ct.fullType
                return Triple(null, false, listOf(Attachment(accountEmail, folderName, uid, part.partId, fn, mime, part.decodedBytes.size.toLong())))
            }
            if (ct.isText) {
                return when (ct.subType.lowercase()) {
                    "html" -> Triple(part.bodyText, true, emptyList())
                    "plain" -> Triple(part.bodyText, false, emptyList())
                    else -> Triple(part.bodyText, ct.subType.equals("html", true), emptyList())
                }
            }
            return Triple(null, false, emptyList())
        }

        fun walk(part: ParsedPart) {
            if (part.contentType.isMultipart) {
                for (child in part.children) {
                    if (child.contentType.isMultipart) {
                        walk(child)
                    } else {
                        val (b, h, a) = extractSingle(child)
                        attachments.addAll(a)
                        if (b != null) {
                            if (finalBody == null || (h && !finalIsHtml)) {
                                finalBody = b
                                finalIsHtml = h
                            }
                        }
                    }
                }
            } else {
                val (b, h, a) = extractSingle(part)
                if (b != null) {
                    finalBody = b
                    finalIsHtml = h
                }
                attachments.addAll(a)
            }
        }

        walk(root)
        return Triple(finalBody, finalIsHtml, attachments)
    }

    fun parseEmlToParsedMessage(emlBytes: ByteArray, syntheticId: Long, context: Context): ParsedEml {
        val root = parsePart(emlBytes, "")
        var body: String? = null
        var isHtml = false
        val emlAttachments = mutableListOf<EmlAttachment>()
        val cidMap = mutableMapOf<String, File>()

        fun walkForEml(part: ParsedPart) {
            if (part.contentType.isMultipart) {
                part.children.forEach { walkForEml(it) }
                return
            }
            val cid = part.contentId
            if (cid != null && (part.contentType.isImage || part.disposition?.isInline == true)) {
                val dir = File(context.cacheDir, "eml_cid/$syntheticId").also { it.mkdirs() }
                val rawName = part.disposition?.filename ?: "${cid.hashCode()}.bin"
                val safeName = rawName.replace(Regex("[/\\\\]"), "_").take(80).ifBlank { "${cid.hashCode()}.bin" }
                val out = File(dir, safeName)
                try {
                    if (!out.exists()) out.writeBytes(part.decodedBytes)
                    cidMap[cid] = out
                } catch (_: Exception) {}
                return
            }
            val filename = part.disposition?.filename ?: part.contentType.params["name"]
            if (!filename.isNullOrBlank() || part.disposition?.isAttachment == true) {
                val mime = part.contentType.fullType
                emlAttachments.add(EmlAttachment(fileName = filename ?: "unnamed", mimeType = mime, bytes = part.decodedBytes))
            } else if (part.contentType.isText) {
                val txt = part.bodyText
                if (txt != null) {
                    if (body == null || (part.contentType.subType.equals("html", true) && !isHtml)) {
                        body = txt
                        isHtml = part.contentType.subType.equals("html", true)
                    }
                }
            }
        }
        walkForEml(root)

        val headers = root.headers
        val from = headers["from"]?.let { decodeHeader(it) } ?: ""
        val subject = headers["subject"]?.let { decodeHeader(it) } ?: "(no subject)"
        val dateStr = headers["date"] ?: ""
        val dateMillis = parseDateToMillis(dateStr)
        val to = headers["to"]?.let { decodeHeader(it) }
        val cc = headers["cc"]?.let { decodeHeader(it) }
        val serverId = headers["message-id"]
        val refs = headers["references"] ?: headers["in-reply-to"]
        val listUnsub = headers["list-unsubscribe"]
        val listUnsubPost = headers["list-unsubscribe-post"]

        val emailMessage = com.vayunmathur.email.EmailMessage(
            accountEmail = "eml-viewer",
            folderName = "EML",
            id = syntheticId,
            serverId = serverId,
            threadId = syntheticId.toString(),
            subject = subject,
            from = from,
            to = to,
            cc = cc,
            date = dateStr,
            dateMillis = dateMillis,
            body = body,
            isHtml = isHtml,
            isRead = true,
            references = refs,
            hasAttachments = emlAttachments.isNotEmpty(),
            listUnsubscribe = listUnsub,
            listUnsubscribePost = listUnsubPost
        )
        return ParsedEml(emailMessage, emlAttachments, cidMap)
    }

    fun extractCidMap(context: Context, rfc822Bytes: ByteArray, uid: Long): Map<String, File> {
        val root = parsePart(rfc822Bytes, "")
        return extractCidMapInternal(context, root, uid)
    }

    private fun extractCidMapInternal(context: Context, root: ParsedPart, uid: Long): Map<String, File> {
        val map = mutableMapOf<String, File>()
        val dir = File(context.cacheDir, "cid/$uid").also { it.mkdirs() }

        fun walk(part: ParsedPart) {
            if (part.contentType.isMultipart) {
                part.children.forEach { walk(it) }
                return
            }
            val cid = part.contentId ?: return
            if (cid.isBlank()) return
            val isInline = part.contentType.isImage || part.disposition?.isInline == true || part.contentType.fullType.startsWith("image/", true)
            if (!isInline) return
            val safeName = (part.disposition?.filename ?: "${cid.hashCode()}.bin").replace(Regex("[/\\\\]"), "_").take(80).ifBlank { "${cid.hashCode()}.bin" }
            val outFile = File(dir, safeName)
            if (!outFile.exists()) {
                try { outFile.writeBytes(part.decodedBytes) } catch (_: Exception) {}
            }
            if (outFile.exists()) {
                map[cid] = outFile
                try { File(dir, "$cid.meta").writeText(outFile.name) } catch (_: Exception) {}
            }
        }
        walk(root)
        try {
            dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { meta ->
                val cid = meta.name.removeSuffix(".meta")
                if (cid !in map) {
                    val targetName = try { meta.readText().trim() } catch (_: Exception) { null }
                    if (targetName != null) {
                        val file = File(dir, targetName)
                        if (file.exists()) map[cid] = file
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    // ---- Header / CT handling ----

    private fun findHeaderBodySeparator(bytes: ByteArray): Pair<Int, Int>? {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() && bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()) {
                return Pair(i, 4)
            }
        }
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                return Pair(i, 2)
            }
        }
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) {
                return Pair(i, 3)
            }
        }
        return null
    }

    fun parseHeadersString(headerStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var currentKey: String? = null
        var currentVal = StringBuilder()
        val lines = headerStr.split("\r\n", "\n")
        for (rawLine in lines) {
            if (rawLine.isEmpty()) continue
            val isContinuation = rawLine.firstOrNull()?.let { it == ' ' || it == '\t' } == true
            if (isContinuation && currentKey != null) {
                currentVal.append(' ').append(rawLine.trim())
            } else {
                if (currentKey != null) {
                    map[currentKey.lowercase()] = currentVal.toString().trim()
                }
                val colonIdx = rawLine.indexOf(':')
                if (colonIdx == -1) {
                    currentKey = null
                    currentVal = StringBuilder()
                    continue
                }
                currentKey = rawLine.substring(0, colonIdx).trim()
                currentVal = StringBuilder(rawLine.substring(colonIdx + 1).trim())
            }
        }
        if (currentKey != null) {
            map[currentKey.lowercase()] = currentVal.toString().trim()
        }
        return map
    }

    fun parseContentType(raw: String?): ContentTypeInfo {
        if (raw.isNullOrBlank()) return ContentTypeInfo("text", "plain", emptyMap())
        val semi = raw.indexOf(';')
        val typePart = if (semi == -1) raw.trim() else raw.substring(0, semi).trim()
        val slash = typePart.indexOf('/')
        val main = if (slash == -1) typePart else typePart.substring(0, slash).trim()
        val sub = if (slash == -1) "plain" else typePart.substring(slash + 1).trim()
        val params = mutableMapOf<String, String>()
        if (semi != -1) params.putAll(parseHeaderParams(raw.substring(semi + 1)))
        return ContentTypeInfo(main.lowercase(), sub.lowercase(), params.mapKeys { it.key.lowercase() })
    }

    fun parseDisposition(raw: String?): DispositionInfo? {
        if (raw.isNullOrBlank()) return null
        val semi = raw.indexOf(';')
        val type = if (semi == -1) raw.trim() else raw.substring(0, semi).trim()
        val params = mutableMapOf<String, String>()
        if (semi != -1) params.putAll(parseHeaderParams(raw.substring(semi + 1)))
        return DispositionInfo(type.lowercase(), params.mapKeys { it.key.lowercase() })
    }

    private fun parseHeaderParams(paramStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        var keySb = StringBuilder()
        var valSb = StringBuilder()
        var inKey = true
        var inQuotes = false
        var currentKey = ""
        while (i < paramStr.length) {
            val c = paramStr[i]
            if (inKey) {
                if (c == '=') {
                    currentKey = keySb.toString().trim()
                    keySb = StringBuilder()
                    inKey = false
                    valSb = StringBuilder()
                } else if (c == ';' && !inQuotes) {
                    keySb = StringBuilder()
                } else {
                    keySb.append(c)
                }
            } else {
                if (!inQuotes && c == '"') {
                    inQuotes = true
                } else if (inQuotes && c == '"') {
                    inQuotes = false
                } else if (!inQuotes && c == ';') {
                    val v = valSb.toString().trim().removeSurrounding("\"")
                    if (currentKey.isNotBlank()) result[currentKey.trim()] = v
                    currentKey = ""
                    keySb = StringBuilder()
                    valSb = StringBuilder()
                    inKey = true
                } else {
                    valSb.append(c)
                }
            }
            i++
        }
        if (!inKey && currentKey.isNotBlank()) {
            result[currentKey.trim()] = valSb.toString().trim().removeSurrounding("\"")
        }
        return result
    }

    fun extractCid(rawCidHeader: String): String? {
        val t = rawCidHeader.trim()
        if (t.isEmpty()) return null
        return t.removePrefix("<").removeSuffix(">").trim().ifBlank { null }
    }

    private fun decodeCte(bytes: ByteArray, cte: String): ByteArray {
        return when (cte) {
            "base64" -> try {
                val str = String(bytes, Charsets.US_ASCII).replace(Regex("\\s"), "")
                if (str.isEmpty()) ByteArray(0) else Base64.decode(str, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "base64 decode failed: ${e.message}")
                bytes
            }
            "quoted-printable" -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
    }

    fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                if (i + 1 >= bytes.size) break
                val next = bytes[i + 1]
                if (next == '\r'.code.toByte()) {
                    if (i + 2 < bytes.size && bytes[i + 2] == '\n'.code.toByte()) {
                        i += 3
                        continue
                    }
                } else if (next == '\n'.code.toByte()) {
                    i += 2
                    continue
                }
                if (i + 2 < bytes.size) {
                    val h1 = bytes[i + 1].toInt().toChar()
                    val h2 = bytes[i + 2].toInt().toChar()
                    try {
                        out.write("$h1$h2".toInt(16))
                        i += 3
                        continue
                    } catch (_: Exception) {}
                }
                out.write(b.toInt())
                i++
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    fun decodeCharset(bytes: ByteArray, charsetName: String): String {
        val name = charsetName.trim().lowercase()
        val candidates = listOf(
            name,
            when (name) {
                "utf8" -> "utf-8"
                "latin1", "iso8859-1", "iso-8859-1" -> "iso-8859-1"
                "windows-1252", "cp1252" -> "windows-1252"
                "windows-1250", "cp1250" -> "windows-1250"
                "us-ascii", "ascii" -> "us-ascii"
                else -> name
            },
            "utf-8",
            "iso-8859-1"
        ).distinct()
        for (cs in candidates) {
            try {
                return String(bytes, Charset.forName(cs))
            } catch (_: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    private val rfc2047Pattern = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=", Pattern.CASE_INSENSITIVE)

    fun decodeHeader(raw: String): String {
        var result = raw
        try {
            val matcher = rfc2047Pattern.matcher(raw)
            val sb = StringBuffer()
            while (matcher.find()) {
                val charset = matcher.group(1) ?: "utf-8"
                val enc = matcher.group(2)?.uppercase() ?: "B"
                val encoded = matcher.group(3) ?: ""
                val decoded = try {
                    if (enc == "B") {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        decodeCharset(bytes, charset)
                    } else {
                        val qpStr = encoded.replace('_', ' ')
                        val decodedBytes = decodeQuotedPrintable(qpStr.toByteArray(Charsets.ISO_8859_1))
                        decodeCharset(decodedBytes, charset)
                    }
                } catch (_: Exception) { encoded }
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(decoded))
            }
            matcher.appendTail(sb)
            result = sb.toString()
        } catch (_: Exception) {}
        return result
    }

    fun parseDateToMillis(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss zzz",
            "EEE MMM dd HH:mm:ss Z yyyy",
            "EEE MMM dd HH:mm:ss zzz yyyy",
            "EEE MMM dd HH:mm:ss yyyy",
            "yyyy-MM-dd HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "dd-MMM-yyyy HH:mm:ss Z"
        )
        for (fmtStr in formats) {
            try {
                val fmt = java.text.SimpleDateFormat(fmtStr, java.util.Locale.US)
                fmt.isLenient = true
                val d = fmt.parse(dateStr.trim())
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        try {
            @Suppress("DEPRECATION")
            val d = java.util.Date(dateStr)
            if (d.time != 0L) return d.time
        } catch (_: Exception) {}
        return 0L
    }

    fun sanitizeFilename(name: String): String {
        var n = name.trim()
        if (n.contains("=?")) n = decodeHeader(n)
        n = n.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[\\r\\n\"]"), "_")
        if (n.length > 120) n = n.take(120)
        return n.ifBlank { "attachment" }
    }

    fun splitMultipart(bodyBytes: ByteArray, boundary: String): List<ByteArray> {
        if (boundary.isBlank()) return emptyList()
        val bodyStr = String(bodyBytes, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        data class DelimPos(val start: Int, val end: Int, val isClose: Boolean)

        val positions = mutableListOf<DelimPos>()
        var searchFrom = 0
        while (true) {
            val idx = bodyStr.indexOf(delim, searchFrom)
            if (idx == -1) break
            var lineEnd = bodyStr.indexOf('\n', idx)
            if (lineEnd == -1) lineEnd = bodyStr.length else lineEnd++
            val afterDelimPart = if (idx + delim.length < bodyStr.length) {
                val nl = bodyStr.indexOf('\n', idx)
                if (nl == -1) bodyStr.substring(idx + delim.length) else bodyStr.substring(idx + delim.length, nl)
            } else ""
            val isClose = afterDelimPart.trim().startsWith("--")
            positions.add(DelimPos(idx, lineEnd, isClose))
            if (isClose) break
            searchFrom = lineEnd
        }

        if (positions.size < 2) {
            if (positions.size == 1 && !positions[0].isClose) {
                val start = positions[0].end
                if (start < bodyStr.length) return listOf(bodyStr.substring(start).toByteArray(Charsets.ISO_8859_1))
            }
            return emptyList()
        }

        val result = mutableListOf<ByteArray>()
        for (i in 0 until positions.size - 1) {
            val cur = positions[i]
            if (cur.isClose) break
            val next = positions[i + 1]
            var partStart = cur.end
            var partEnd = next.start
            if (partEnd >= 2 && bodyStr[partEnd - 1] == '\n') {
                partEnd--
                if (partEnd > 0 && bodyStr[partEnd - 1] == '\r') partEnd--
            }
            if (partStart > partEnd) continue
            result.add(bodyStr.substring(partStart, partEnd).toByteArray(Charsets.ISO_8859_1))
        }
        return result
    }

    fun extractHeaderValue(headers: Map<String, String>, name: String): String? {
        return headers[name.lowercase()]?.let { decodeHeader(it) }
    }
}
