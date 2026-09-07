package com.vayunmathur.logviewer.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the two pieces of [LogDocument] whose behaviour is easy to get subtly wrong and impossible
 * to notice on screen: line splitting and clipboard truncation.
 */
class LogDocumentTest {

    private fun document(header: String, body: String) = LogDocument(
        kind = LogKind.Logcat,
        sourcePackage = null,
        title = "System log",
        header = header,
        body = body,
    )

    @Test
    fun `trailing newline does not become a blank last row`() {
        // Every logcat dump ends in a newline. A blank final row would also mean "scroll to the
        // last line" lands on an empty one.
        val document = document("", "first\nsecond\n")
        assertEquals(listOf("first", "second"), document.bodyLines())
    }

    @Test
    fun `blank lines inside the body are kept`() {
        val document = document("", "first\n\nthird\n")
        assertEquals(listOf("first", "", "third"), document.bodyLines())
    }

    @Test
    fun `an empty header produces no header rows`() {
        assertTrue(document("", "body\n").headerLines().isEmpty())
    }

    @Test
    fun `display rows are header, blank, body, blank, description`() {
        val document = document("type: logcat", "line\n")
        assertEquals(
            listOf("type: logcat", "", "line", "", "description: why"),
            document.displayLines("why"),
        )
    }

    @Test
    fun `a small log is copyable and fenced`() {
        val document = document("type: logcat", "line\n")
        assertTrue(document.canCopy)
        val clip = document.clipText("")
        assertFalse(clip.truncated)
        assertEquals("```\ntype: logcat\nline\n```\n", clip.text)
    }

    @Test
    fun `an oversized log is truncated from the front and says so`() {
        // Well past the 200 kB clipboard budget, so the head has to go.
        val body = (1..20_000).joinToString("\n") { "line $it padded out to make it long enough" }
        val clip = document("type: logcat", body + "\n").clipText("")
        assertTrue(clip.truncated)
        assertTrue(clip.text.contains("[[TRUNCATED]]"))
        // The tail is what explains a crash, so that is the end that survives.
        assertTrue(clip.text.contains("line 20000"))
        assertFalse(clip.text.contains("line 1 padded"))
    }

    @Test
    fun `a log too large to copy still reports so`() {
        val document = document("", "x".repeat(60_000))
        assertFalse(document.canCopy)
    }

    @Test
    fun `snapshot text is unfenced and untruncated`() {
        val text = document("type: logcat", "line\n").snapshotText("why")
        assertEquals("type: logcat\nline\n\ndescription: why\n", text)
    }
}
