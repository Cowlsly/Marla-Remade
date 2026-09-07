package com.vayunmathur.logviewer.domain

import java.util.UUID

/**
 * One log or error report, ready to display, copy, save or share.
 *
 * Pure data: everything that needed a `Context` - reading logcat, resolving an app label, reading a
 * tombstone - has already happened by the time one of these exists. That is what lets the same
 * formatting be reused by the screen, the clipboard and the file writer without any of them
 * agreeing on it separately.
 *
 * [description] is the one part the user edits, and it is not held here: it is screen state, and
 * every function that needs it takes it as a parameter.
 */
data class LogDocument(
    val kind: LogKind,
    /** The app the log is about - the one that crashed, or the one logcat is filtered on. */
    val sourcePackage: String?,
    val title: String,
    val header: String,
    val body: String,
) {
    /**
     * Whether the bottom-left button offers Copy rather than Save.
     *
     * Both the character and the byte length are checked because the limit being approximated is a
     * binder transaction, which is bytes, while the cheap check is characters.
     */
    val canCopy: Boolean =
        body.length < MAX_SIZE_FOR_COPY && body.toByteArray(Charsets.UTF_8).size < MAX_SIZE_FOR_COPY

    /** Empty when there is no header at all, so the screen does not render a blank separator. */
    fun headerLines(): List<String> {
        val lines = splitLines(header)
        return if (lines.size == 1 && lines[0].isBlank()) emptyList() else lines
    }

    fun bodyLines(): List<String> = splitLines(body)

    /** The rows the list shows, in order: header, blank, body, blank, description. */
    fun displayLines(description: String): List<String> = buildList {
        val headerRows = headerLines()
        addAll(headerRows)
        if (headerRows.isNotEmpty()) add("")
        addAll(bodyLines())
        if (description.isNotBlank()) {
            add("")
            addAll(splitLines("description: $description"))
        }
    }

    /**
     * The clipboard text, and whether it had to be shortened.
     *
     * Fenced in triple backticks because the destination is an issue tracker. The tail is kept
     * rather than the head: the end of a log is what explains the crash. The 200 kB budget is the
     * binder transaction limit that `ClipboardManager.setPrimaryClip` has to fit inside - going
     * over it is a `TransactionTooLargeException`, not a truncated paste.
     */
    fun clipText(description: String): ClipText {
        var sumSize = header.toByteArray(Charsets.UTF_8).size +
            description.toByteArray(Charsets.UTF_8).size
        var sumChars = 0
        var bodyStartIndex = 0

        val bodyLines = bodyLines()
        for (i in bodyLines.indices.reversed()) {
            val line = bodyLines[i]
            sumChars += line.length + 1
            sumSize += line.toByteArray(Charsets.UTF_8).size + 1
            if (sumSize > MAX_CLIP_BYTES) {
                bodyStartIndex = i + 1
                break
            }
        }

        val sb = StringBuilder(sumChars + 1000)
        sb.append("```\n")

        val headerRows = headerLines()
        for (line in headerRows) {
            sb.append(line)
            sb.append('\n')
        }
        if (headerRows.size > 1) sb.append('\n')

        if (bodyStartIndex != 0) sb.append("[[TRUNCATED]]\n")

        for (i in bodyStartIndex until bodyLines.size) {
            sb.append(kind.copyLine(bodyLines[i]))
            sb.append('\n')
        }

        appendDescription(sb, description)
        sb.append("```\n")

        return ClipText(sb.toString(), truncated = bodyStartIndex != 0)
    }

    /** The whole thing, unfenced and untruncated: what Save writes and what Share hands over. */
    fun snapshotText(description: String): String {
        val sb = StringBuilder()

        val headerRows = headerLines()
        for (line in headerRows) {
            sb.append(line)
            sb.append('\n')
        }
        if (headerRows.size > 1) sb.append('\n')

        for (line in bodyLines()) {
            sb.append(line)
            sb.append('\n')
        }

        appendDescription(sb, description)
        return sb.toString()
    }

    private fun appendDescription(sb: StringBuilder, description: String) {
        if (description.isBlank()) return
        sb.append("\ndescription: ")
        sb.append(description)
        sb.append('\n')
    }

    data class ClipText(val text: String, val truncated: Boolean)

    companion object {
        private const val MAX_SIZE_FOR_COPY = 50_000
        private const val MAX_CLIP_BYTES = 200_000

        /**
         * Logs are plain text, but "text/plain" makes share targets do unwanted things to them -
         * link detection, and a preview in the share sheet that is no help for a stack trace.
         */
        const val MIME_TYPE = "application/octet-stream"

        /**
         * A name for the saved or shared file: the title, then twelve hex characters so two saves
         * of the same log do not collide.
         */
        fun snapshotFileName(title: String): String =
            trimToSize(title, 200) + ' ' + UUID.randomUUID().toString().substring(24) + ".txt"

        /**
         * `String.split` the way `java.lang.String.split` does it, which is what the header/body
         * line counts were written against: trailing empty strings are dropped, so a body ending in
         * a newline does not gain a blank last row (and so "scroll to the last line" lands on the
         * last line).
         */
        internal fun splitLines(s: String): List<String> {
            if (s.isEmpty()) return listOf("")
            val parts = s.split("\n")
            var end = parts.size
            while (end > 0 && parts[end - 1].isEmpty()) end--
            return parts.subList(0, end)
        }

        /** [android.text.TextUtils.trimToSize], reimplemented so this file stays Android-free. */
        private fun trimToSize(s: String, maxLength: Int): String {
            if (s.length <= maxLength) return s
            val end = if (Character.isHighSurrogate(s[maxLength - 1])) maxLength - 1 else maxLength
            return s.substring(0, end)
        }
    }
}
