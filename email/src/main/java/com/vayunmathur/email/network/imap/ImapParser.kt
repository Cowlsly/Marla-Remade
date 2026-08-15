package com.vayunmathur.email.imap

import android.util.Log

/**
 * Parser helpers for IMAP LIST and FETCH.
 */
object ImapParser {

    private const val TAG = "ImapParser"

    // Modified UTF-7 for IMAP mailbox names (RFC 3501 §5.1.3)
    // '&' starts a base64 section terminated by '-'. Base64 modified: ',' instead of '/'.
    private val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"
    private val base64Inv = IntArray(128) { -1 }.apply {
        for (i in base64Chars.indices) {
            this[base64Chars[i].code] = i
        }
    }

    fun decodeModifiedUtf7(s: String): String {
        return try {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    if (i + 1 < s.length && s[i + 1] == '-') {
                        sb.append('&')
                        i += 2
                        continue
                    }
                    val dash = s.indexOf('-', i + 1)
                    if (dash == -1) {
                        // Unterminated, treat literally
                        sb.append(s.substring(i))
                        break
                    }
                    val b64Section = s.substring(i + 1, dash)
                    if (b64Section.isEmpty()) {
                        i = dash + 1
                        continue
                    }
                    // Modified Base64 -> standard: replace ',' with '/'
                    val stdB64 = b64Section.replace(',', '/')
                    // Pad to multiple of 4
                    val padded = stdB64 + "===".substring(0, (4 - stdB64.length % 4) % 4)
                    try {
                        val bytes = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                        // UTF-16BE
                        val decoded = String(bytes, Charsets.UTF_16BE)
                        sb.append(decoded)
                    } catch (e: Exception) {
                        Log.w(TAG, "UTF7 decode failed for $b64Section: ${e.message}")
                        sb.append("&").append(b64Section).append("-")
                    }
                    i = dash + 1
                } else {
                    sb.append(c)
                    i++
                }
            }
            sb.toString()
        } catch (_: Exception) {
            s
        }
    }

    fun encodeModifiedUtf7(s: String): String {
        return try {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val code = c.code
                // Printable ASCII 0x20-0x7E except '&' are direct
                if (code in 0x20..0x7E && c != '&') {
                    sb.append(c)
                    i++
                } else {
                    // Collect sequence of non-ASCII or '&'
                    val start = i
                    while (i < s.length) {
                        val cc = s[i]
                        val coc = cc.code
                        if (coc in 0x20..0x7E && cc != '&') break
                        i++
                    }
                    val seq = s.substring(start, i)
                    if (seq == "&") {
                        sb.append("&-")
                    } else {
                        val utf16Bytes = seq.toByteArray(Charsets.UTF_16BE)
                        var b64 = android.util.Base64.encodeToString(utf16Bytes, android.util.Base64.NO_WRAP)
                        b64 = b64.trimEnd('=').replace('/', ',')
                        sb.append('&').append(b64).append('-')
                    }
                }
            }
            sb.toString()
        } catch (_: Exception) {
            s
        }
    }

    /**
     * Parse LIST line: * LIST (\Flags) "delimiter" "mailbox"
     * Example: * LIST (\HasNoChildren) "/" "INBOX"
     *          * LIST (\Noselect \HasChildren) "/" "[Gmail]"
     *          * LIST (\Seen) NIL "INBOX"
     */
    fun parseList(line: String): ImapListEntry? {
        return try {
            var rest = line.trim()
            if (!rest.startsWith("* LIST")) return null
            rest = rest.substringAfter("* LIST").trim()
            // Flags in ()
            if (!rest.startsWith("(")) return null
            val flagsEnd = rest.indexOf(')')
            if (flagsEnd == -1) return null
            val flagsStr = rest.substring(1, flagsEnd)
            val flags = flagsStr.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            rest = rest.substring(flagsEnd + 1).trim()

            // Delimiter: either quoted string or NIL or single char
            var delimiter: String? = null
            if (rest.startsWith("\"")) {
                val end = findClosingQuote(rest, 0)
                if (end != -1) {
                    delimiter = rest.substring(1, end)
                    rest = rest.substring(end + 1).trim()
                }
            } else if (rest.uppercase().startsWith("NIL")) {
                delimiter = null
                rest = rest.substring(3).trim()
            } else {
                // Unquoted token
                val space = rest.indexOf(' ')
                if (space != -1) {
                    delimiter = rest.substring(0, space).trim('"')
                    rest = rest.substring(space + 1).trim()
                } else {
                    delimiter = rest
                    rest = ""
                }
            }

            // Mailbox: remaining is mailbox name, quoted or literal or unquoted
            var mailboxRaw = rest.trim()
            var mailbox: String
            if (mailboxRaw.startsWith("\"")) {
                // May have escaped quotes? Simplified
                val end = findClosingQuote(mailboxRaw, 0)
                if (end != -1) {
                    mailbox = unescapeQuoted(mailboxRaw.substring(1, end))
                } else {
                    // Trim quotes
                    mailbox = mailboxRaw.trim('"')
                }
            } else if (mailboxRaw.startsWith("{")) {
                // Literal case already handled by RawImapConnection as separate literal bytes
                // For LIST literal, the mailbox would be in literal bytes not this line.
                // We return raw as-is for now.
                mailbox = mailboxRaw
            } else {
                mailbox = mailboxRaw.trim().trim('"')
            }

            // Decode modified UTF-7
            mailbox = decodeModifiedUtf7(mailbox)

            ImapListEntry(flags, delimiter, mailbox)
        } catch (e: Exception) {
            Log.w(TAG, "parseList failed for: $line: ${e.message}")
            null
        }
    }

    private fun findClosingQuote(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2 // escaped char
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun unescapeQuoted(s: String): String {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    fun parseUid(line: String): Long? {
        // UID may appear as "UID 12345"
        val m = Regex("""\bUID (\d+)""").find(line) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    fun parseFlags(line: String): List<String> {
        // * FLAGS (\Answered \Flagged ...)
        val m = Regex("""\(.*?\\.*?\)""").find(line) ?: return emptyList()
        // extract inside ()
        val inside = line.substringAfter('(').substringBefore(')').trim()
        return inside.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    fun parseFlagsFromFetch(line: String): List<String>? {
        // FETCH (FLAGS (\Seen \Answered) ...)
        val m = Regex("""FLAGS \(([^)]*)\)""", RegexOption.IGNORE_CASE).find(line) ?: return null
        val inside = m.groupValues[1]
        if (inside.isBlank()) return emptyList()
        return inside.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    fun parseInternalDate(line: String): String? {
        val m = Regex("""INTERNALDATE "([^"]+)"""", RegexOption.IGNORE_CASE).find(line) ?: return null
        return m.groupValues[1]
    }

    // Helper: parse sequence like "* 1 EXISTS" already done elsewhere

    // For thread detection, X-GM-THRID extraction happens in MimeParser header parse.

    fun parseGmailThreadIdFromHeaderBlock(headerBytes: ByteArray): String? {
        val text = String(headerBytes, Charsets.UTF_8)
        val lines = text.split("\r\n", "\n")
        for (ln in lines) {
            if (ln.lowercase().startsWith("x-gm-thrid:")) {
                return ln.substringAfter(":").trim()
            }
        }
        return null
    }

    fun extractListFlags(listLine: String): List<String> {
        val start = listLine.indexOf('(')
        val end = listLine.indexOf(')', start)
        if (start == -1 || end == -1) return emptyList()
        val inside = listLine.substring(start + 1, end).trim()
        if (inside.isBlank()) return emptyList()
        return inside.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    fun extractListDelimiter(listLine: String): String? {
        // After flags, next token is delimiter
        val flagsEnd = listLine.indexOf(')') + 1
        if (flagsEnd <= 0 || flagsEnd >= listLine.length) return null
        var rest = listLine.substring(flagsEnd).trim()
        if (rest.startsWith("\"")) {
            val close = findClosingQuote(rest, 0)
            if (close != -1) return rest.substring(1, close)
            return rest.trim('"').substringBefore(' ')
        }
        if (rest.uppercase().startsWith("NIL")) return null
        // unquoted
        return rest.substringBefore(' ').trim('"').ifBlank { null }
    }
}
