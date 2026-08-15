package com.vayunmathur.email.imap

import android.util.Base64
import android.util.Log
import com.vayunmathur.email.ServerConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Raw IMAP connection using Socket / SSLSocket.
 * Implements RFC 3501 + STARTTLS + AUTH (PLAIN/LOGIN/XOAUTH2) + IDLE (RFC 2177).
 *
 * Literal handling: {N} at end of line indicates N raw bytes follow.
 */

data class ImapCapabilities(val caps: Set<String>) {
    fun has(cap: String): Boolean = caps.contains(cap.uppercase())
}

data class ImapListEntry(val flags: List<String>, val delimiter: String?, val mailbox: String)
data class ImapSelectResult(val exists: Int, val recent: Int = 0, val uidValidity: Long = 0, val uidNext: Long = 0, val flags: List<String> = emptyList())

data class ImapFetchResult(
    val uid: Long,
    val flags: List<String>,
    val internalDate: String?,
    val headerBytes: ByteArray?,
    val bodyBytes: ByteArray?,
    val size: Int = 0,
)

class RawImapConnection(
    val server: ServerConfig,
    val trustAll: Boolean = false,
) : AutoCloseable {

    companion object {
        private const val TAG = "RawImap"
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_LINE = 8192 * 16
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    internal var tagCounter = 1
    private var inIdle = false

    private fun inputStream(): InputStream = input ?: throw IOException("Not connected")
    private fun outputStream(): OutputStream = output ?: throw IOException("Not connected")

    internal fun nextTag(): String {
        val t = "A%04d".format(tagCounter)
        tagCounter++
        return t
    }

    fun connect() {
        Log.d(TAG, "Connecting to ${server.host}:${server.port} ssl=${server.useSsl} trustAll=$trustAll")
        val s: Socket = if (server.useSsl) {
            TrustAll.createSocket(server.host, server.port, trustAll)
        } else {
            TrustAll.createPlainSocket(server.host, server.port)
        }
        s.soTimeout = SOCKET_TIMEOUT_MS
        socket = s
        if (s is SSLSocket) {
            sslSocket = s
        }
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())

        val greeting = readLineWithLiteral()?.first ?: ""
        Log.d(TAG, "Greeting: $greeting")
        if (greeting.startsWith("* BYE") || greeting.startsWith("* BAD")) {
            throw IOException("IMAP server rejected: $greeting")
        }
    }

    fun startTls() {
        val plain = socket ?: throw IOException("Not connected for STARTTLS")
        val tag = nextTag()
        sendLine("$tag STARTTLS")
        val (resp, _) = readResponse(tag)
        if (!resp.uppercase().contains("OK")) throw IOException("STARTTLS failed: $resp")
        val upgraded = TrustAll.upgradeToTls(plain, server.host, server.port, trustAll, false)
        sslSocket = upgraded
        socket = upgraded
        upgraded.soTimeout = SOCKET_TIMEOUT_MS
        input = BufferedInputStream(upgraded.inputStream)
        output = BufferedOutputStream(upgraded.outputStream)
        Log.d(TAG, "STARTTLS upgraded")
    }

    fun capability(): ImapCapabilities {
        val tag = nextTag()
        sendLine("$tag CAPABILITY")
        val lines = mutableListOf<String>()
        var last = ""
        while (true) {
            val pair = readLineWithLiteral() ?: break
            val (line, _) = pair
            if (line.startsWith("* CAPABILITY")) lines.add(line)
            if (line.startsWith(tag)) { last = line; break }
        }
        val allCaps = lines.joinToString(" ").uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        Log.d(TAG, "CAPABILITY $allCaps final=$last")
        return ImapCapabilities(allCaps)
    }

    fun login(user: String, pass: String): String {
        val tag = nextTag()
        sendLine("$tag LOGIN ${escapeString(user)} ${escapeString(pass)}")
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("LOGIN failed: $final")
        return final
    }

    fun authenticatePlain(user: String, pass: String): String {
        val authStr = "\u0000$user\u0000$pass"
        val b64 = Base64.encodeToString(authStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tag = nextTag()
        sendLine("$tag AUTHENTICATE PLAIN $b64")
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("AUTH PLAIN failed: $final")
        return final
    }

    fun authenticateLogin(user: String, pass: String): String {
        val tag = nextTag()
        sendLine("$tag AUTHENTICATE LOGIN")
        var cont = readLineWithLiteral()?.first ?: ""
        if (!cont.startsWith("+")) throw IOException("AUTH LOGIN continuation expected, got: $cont")
        sendLine(Base64.encodeToString(user.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        cont = readLineWithLiteral()?.first ?: ""
        if (!cont.startsWith("+")) throw IOException("AUTH LOGIN 2nd continuation expected, got: $cont")
        sendLine(Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("AUTH LOGIN failed: $final")
        return final
    }

    fun authenticateXoauth2(email: String, token: String): String {
        val sasl = "user=$email\u0001auth=Bearer $token\u0001\u0001"
        val b64 = Base64.encodeToString(sasl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // Try inline first
        var tag = nextTag()
        sendLine("$tag AUTHENTICATE XOAUTH2 $b64")
        var accum = readUntilTag(tag)
        if (accum.finalLine.contains(" OK ", ignoreCase = true)) return accum.finalLine

        // If BAD/NO, retry challenge/response variant
        Log.d(TAG, "XOAUTH2 inline failed: ${accum.finalLine}, trying CR")
        tag = nextTag()
        sendLine("$tag AUTHENTICATE XOAUTH2")
        val continuation = readLineWithLiteral()?.first ?: ""
        if (!continuation.startsWith("+")) {
            val (finalAfterCont, _) = readResponse(tag)
            throw ImapAuthException("XOAUTH2 CR failed: $continuation / $finalAfterCont")
        }
        sendLine(b64)
        accum = readUntilTag(tag)
        if (!accum.finalLine.contains(" OK ", ignoreCase = true)) throw ImapAuthException("XOAUTH2 failed: ${accum.finalLine}")
        return accum.finalLine
    }

    fun list(ref: String, pattern: String): List<ImapListEntry> {
        val tag = nextTag()
        sendLine("$tag LIST ${escapeString(ref)} ${escapeString(pattern)}")
        val entries = mutableListOf<ImapListEntry>()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            val (line, literal) = pair
            if (line.startsWith("* LIST")) {
                ImapParser.parseList(line)?.let { entries.add(it) }
            }
            if (literal != null && line.startsWith("* LIST")) {
                // Literal contains mailbox name (non-ASCII folder)
                val mbox = String(literal, Charsets.UTF_8)
                // The line before had placeholder; replace last entry's mailbox if needed
                // If parsing failed due to literal, we reconstruct
                if (entries.isEmpty() || !entries.last().mailbox.contains(mbox)) {
                    // Try to parse flags/delim from line, use literal as mailbox
                    val flags = ImapParser.extractListFlags(line)
                    val delim = ImapParser.extractListDelimiter(line)
                    val decodedMbox = ImapParser.decodeModifiedUtf7(mbox)
                    entries.add(ImapListEntry(flags, delim, decodedMbox))
                }
            }
            if (line.startsWith(tag)) break
        }
        return entries
    }

    fun select(mailbox: String): ImapSelectResult {
        val tag = nextTag()
        sendLine("$tag SELECT ${escapeString(mailbox)}")
        var exists = 0
        var recent = 0
        var uidValidity = 0L
        var uidNext = 0L
        val flags = mutableListOf<String>()
        var finalLine = ""
        while (true) {
            val pair = readLineWithLiteral() ?: break
            val (line, _) = pair
            Regex("""^\* (\d+) EXISTS""").find(line)?.let { exists = it.groupValues[1].toIntOrNull() ?: exists }
            Regex("""^\* (\d+) RECENT""").find(line)?.let { recent = it.groupValues[1].toIntOrNull() ?: recent }
            Regex("""\[UIDVALIDITY (\d+)\]""").find(line)?.let { uidValidity = it.groupValues[1].toLongOrNull() ?: uidValidity }
            Regex("""\[UIDNEXT (\d+)\]""").find(line)?.let { uidNext = it.groupValues[1].toLongOrNull() ?: uidNext }
            if (line.startsWith("* FLAGS")) flags.addAll(ImapParser.parseFlags(line))
            if (line.startsWith(tag)) { finalLine = line; break }
        }
        if (!finalLine.uppercase().contains(" OK ")) throw IOException("SELECT $mailbox failed: $finalLine")
        return ImapSelectResult(exists, recent, uidValidity, uidNext, flags)
    }

    fun examine(mailbox: String): ImapSelectResult {
        val tag = nextTag()
        sendLine("$tag EXAMINE ${escapeString(mailbox)}")
        var exists = 0
        var finalLine = ""
        while (true) {
            val pair = readLineWithLiteral() ?: break
            val (line, _) = pair
            Regex("""^\* (\d+) EXISTS""").find(line)?.let { exists = it.groupValues[1].toIntOrNull() ?: exists }
            if (line.startsWith(tag)) { finalLine = line; break }
        }
        if (!finalLine.uppercase().contains(" OK ")) throw IOException("EXAMINE $mailbox failed: $finalLine")
        return ImapSelectResult(exists)
    }

    fun uidFetchHeaders(uidSet: String): List<ImapFetchResult> {
        if (uidSet.isBlank()) return emptyList()
        val tag = nextTag()
        val headerFields = "From To Cc Subject Date Message-ID References In-Reply-To List-Unsubscribe List-Unsubscribe-Post X-GM-THRID"
        sendLine("$tag UID FETCH $uidSet (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS ($headerFields)])")
        return collectFetch(tag)
    }

    fun fetchHeadersForSeq(seqSet: String): List<ImapFetchResult> {
        if (seqSet.isBlank()) return emptyList()
        val tag = nextTag()
        val headerFields = "From To Cc Subject Date Message-ID References In-Reply-To List-Unsubscribe List-Unsubscribe-Post X-GM-THRID"
        sendLine("$tag FETCH $seqSet (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS ($headerFields)])")
        return collectFetch(tag)
    }

    fun uidFetchFullSet(uidSet: String): List<ImapFetchResult> {
        if (uidSet.isBlank()) return emptyList()
        val tag = nextTag()
        sendLine("$tag UID FETCH $uidSet (UID FLAGS INTERNALDATE BODY.PEEK[])")
        return collectFetch(tag)
    }

    fun uidFetchFull(uid: Long): ImapFetchResult? = uidFetchFullSet(uid.toString()).firstOrNull()

    fun uidFetchPartBytes(uid: Long, section: String): ByteArray? {
        val tag = nextTag()
        sendLine("$tag UID FETCH $uid (BODY.PEEK[$section])")
        val results = collectFetch(tag)
        return results.firstOrNull()?.let { it.bodyBytes ?: it.headerBytes }
    }

    fun uidStoreFlags(uid: Long, flag: String, add: Boolean = true) {
        val tag = nextTag()
        val op = if (add) "+FLAGS" else "-FLAGS"
        sendLine("$tag UID STORE $uid $op ($flag)")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("STORE failed: $final")
    }

    fun uidStoreFlagsSet(uidSet: String, flag: String, add: Boolean = true) {
        if (uidSet.isBlank()) return
        val tag = nextTag()
        val op = if (add) "+FLAGS" else "-FLAGS"
        sendLine("$tag UID STORE $uidSet $op ($flag)")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("STORE $uidSet failed: $final")
    }

    fun expunge() {
        val tag = nextTag()
        sendLine("$tag EXPUNGE")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("EXPUNGE failed: $final")
    }

    fun uidExpunge(uid: Long): String {
        var tag = nextTag()
        sendLine("$tag UID EXPUNGE $uid")
        var (final, _) = readResponse(tag)
        if (final.uppercase().contains(" OK ")) return final
        // Fallback STORE \Deleted + EXPUNGE
        tag = nextTag()
        sendLine("$tag UID STORE $uid +FLAGS (\\Deleted)")
        readResponse(tag)
        tag = nextTag()
        sendLine("$tag EXPUNGE")
        val (final2, _) = readResponse(tag)
        return final2
    }

    // ---- IDLE ----

    fun sendIdle(): String {
        val tag = nextTag()
        sendLine("$tag IDLE")
        val line = readLineWithLiteral()?.first ?: throw IOException("No response to IDLE")
        if (!line.startsWith("+")) throw IOException("IDLE continuation expected, got: $line")
        inIdle = true
        return tag
    }

    fun sendIdleDone() {
        if (!inIdle) return
        sendLine("DONE")
        inIdle = false
    }

    fun readIdleLine(): String? {
        return try { readLineWithLiteral()?.first } catch (_: Exception) { null }
    }

    fun readIdleResponseForTag(idleTag: String): String {
        val sb = StringBuilder()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            sb.appendLine(pair.first)
            if (pair.first.startsWith(idleTag)) return sb.toString()
        }
        return sb.toString()
    }

    // ---- Low level ----

    private data class ResponseAccum(val lines: List<String>, val finalLine: String)

    private fun readUntilTag(tag: String): ResponseAccum {
        val lines = mutableListOf<String>()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            lines.add(pair.first)
            if (pair.first.startsWith(tag)) return ResponseAccum(lines, pair.first)
        }
        return ResponseAccum(lines, lines.lastOrNull() ?: "")
    }

    private fun readResponse(tag: String): Pair<String, List<String>> {
        val lines = mutableListOf<String>()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            lines.add(pair.first)
            if (pair.first.startsWith(tag)) return pair.first to lines
        }
        return (lines.lastOrNull() ?: "") to lines
    }

    internal fun collectFetch(tag: String): List<ImapFetchResult> {
        val results = mutableListOf<ImapFetchResult>()
        var currentUid: Long = -1
        var currentFlags: List<String> = emptyList()
        var currentDate: String? = null
        var currentHeaderBytes: ByteArray? = null
        var currentBodyBytes: ByteArray? = null
        var accumulating = false

        while (true) {
            val pair = readLineWithLiteral() ?: break
            val (line, literal) = pair

            if (line.startsWith(tag)) {
                if (accumulating && currentUid != -1L) {
                    results.add(ImapFetchResult(currentUid, currentFlags, currentDate, currentHeaderBytes, currentBodyBytes))
                }
                break
            }

            if (line.startsWith("*") && line.contains("FETCH")) {
                if (accumulating && currentUid != -1L) {
                    results.add(ImapFetchResult(currentUid, currentFlags, currentDate, currentHeaderBytes, currentBodyBytes))
                }
                currentUid = -1
                currentFlags = emptyList()
                currentDate = null
                currentHeaderBytes = null
                currentBodyBytes = null
                accumulating = true

                ImapParser.parseUid(line)?.let { currentUid = it }
                ImapParser.parseFlagsFromFetch(line)?.let { currentFlags = it }
                ImapParser.parseInternalDate(line)?.let { currentDate = it }

                if (literal != null) {
                    // Distinguish BODY[] vs HEADER
                    if (line.contains("BODY[]") || line.contains("BODY.PEEK[]") || Regex("""BODY\[.*\]""").containsMatchIn(line) && !line.contains("HEADER")) {
                        currentBodyBytes = literal
                    } else if (line.contains("HEADER")) {
                        currentHeaderBytes = literal
                    } else {
                        if (looksLikeHeaderBlock(literal)) currentHeaderBytes = literal else currentBodyBytes = literal
                    }
                }
            } else if (literal != null) {
                if (looksLikeHeaderBlock(literal)) {
                    if (currentHeaderBytes == null) currentHeaderBytes = literal else currentBodyBytes = literal
                } else {
                    currentBodyBytes = literal
                }
                if (currentUid == -1L) ImapParser.parseUid(line)?.let { currentUid = it }
            } else {
                if (line.contains("UID") && currentUid == -1L) ImapParser.parseUid(line)?.let { currentUid = it }
                if (line.contains("FLAGS") && currentFlags.isEmpty()) ImapParser.parseFlagsFromFetch(line)?.let { currentFlags = it }
            }
        }
        return results
    }

    private fun looksLikeHeaderBlock(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8)
        return sample.contains("From:") || sample.contains("Subject:") || (sample.contains(":") && sample.contains("\r\n"))
    }

    internal fun sendLine(line: String) {
        val out = outputStream()
        out.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
        out.flush()
        val preview = if (line.contains("LOGIN") || line.contains("AUTHENTICATE") && line.contains("PLAIN")) line.substringBefore(" ") + " [auth redacted]" else line
        Log.d(TAG, "C> $preview")
    }

    private fun readLineWithLiteral(): Pair<String, ByteArray?>? {
        val inp = inputStream()
        val lineBytes = readLineBytes(inp) ?: return null
        val lineStr = String(lineBytes, Charsets.US_ASCII).trimEnd('\r', '\n')
        val litMatch = Regex("""\{(\d+)(\+)?\}$""").find(lineStr)
        if (litMatch != null) {
            val size = litMatch.groupValues[1].toIntOrNull() ?: 0
            if (size > 0) {
                val litBytes = ByteArray(size)
                var read = 0
                while (read < size) {
                    val r = inp.read(litBytes, read, size - read)
                    if (r == -1) throw IOException("Unexpected EOF reading $size byte literal, got $read")
                    read += r
                }
                Log.d(TAG, "S> [literal $size] line=${lineStr.take(120)}")
                return lineStr to litBytes
            } else {
                return lineStr to ByteArray(0)
            }
        }
        if (lineStr.isNotBlank()) {
            val preview = if (lineStr.length > 600) lineStr.take(600) + " ... (${lineStr.length})" else lineStr
            Log.d(TAG, "S> $preview")
        }
        return lineStr to null
    }

    private fun readLineBytes(inp: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var count = 0
        while (true) {
            val b = try { inp.read() } catch (e: java.net.SocketTimeoutException) { throw e }
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            baos.write(b)
            count++
            if (count > MAX_LINE) {
                Log.w(TAG, "Line exceeded MAX_LINE, draining to newline")
                while (true) {
                    val nb = inp.read()
                    if (nb == -1) break
                    baos.write(nb)
                    if (nb == '\n'.code) break
                }
                break
            }
            if (b == '\n'.code) break
        }
        return baos.toByteArray()
    }

    private fun escapeString(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { sslSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
