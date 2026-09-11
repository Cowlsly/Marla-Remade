package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Unit tests for the pure encoding / line-ending detection and round-tripping. */
class TextEncodingTest {

    @Test
    fun detectsLfWhenNoCarriageReturns() {
        assertEquals(LineEnding.LF, TextEncoding.detectLineEnding("a\nb\nc"))
    }

    @Test
    fun detectsCrlfWhenPresent() {
        assertEquals(LineEnding.CRLF, TextEncoding.detectLineEnding("a\r\nb\r\nc"))
    }

    @Test
    fun mixedEndingsPickTheMajority() {
        // Two CRLF vs one lone LF -> CRLF.
        assertEquals(LineEnding.CRLF, TextEncoding.detectLineEnding("a\r\nb\r\nc\nd"))
        // One CRLF vs two lone LF -> LF.
        assertEquals(LineEnding.LF, TextEncoding.detectLineEnding("a\r\nb\nc\nd"))
    }

    @Test
    fun normalizeCollapsesCrlfAndLoneCr() {
        assertEquals("a\nb\nc", TextEncoding.normalizeToLf("a\r\nb\rc"))
    }

    @Test
    fun normalizeReturnsTheSameStringWhenThereIsNothingToDo() {
        val text = "a\nb\nc"
        assertSame(text, TextEncoding.normalizeToLf(text))
    }

    @Test
    fun decodesPlainUtf8() {
        val decoded = TextEncoding.decode("hello".toByteArray(Charsets.UTF_8))
        assertEquals("hello", decoded.text)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertFalse(decoded.hadBom)
        assertEquals(LineEnding.LF, decoded.lineEnding)
    }

    @Test
    fun decodesUtf8WithBomAndStripsIt() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        val decoded = TextEncoding.decode(bytes)
        assertEquals("hi", decoded.text)
        assertTrue(decoded.hadBom)
    }

    @Test
    fun decodesUtf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        val decoded = TextEncoding.decode(bytes)
        assertEquals("hi", decoded.text)
        assertEquals(Charsets.UTF_16LE, decoded.charset)
        assertTrue(decoded.hadBom)
    }

    @Test
    fun crlfRoundTripsExactly() {
        val original = "line1\r\nline2\r\n".toByteArray(Charsets.UTF_8)
        val decoded = TextEncoding.decode(original)
        assertEquals("line1\nline2\n", decoded.text)
        val reencoded = TextEncoding.encode(decoded.text, decoded.charset, decoded.lineEnding, decoded.hadBom)
        assertContentEquals(original, reencoded)
    }

    @Test
    fun utf8BomRoundTripsExactly() {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "x\r\ny".toByteArray(Charsets.UTF_8)
        val decoded = TextEncoding.decode(original)
        val reencoded = TextEncoding.encode(decoded.text, decoded.charset, decoded.lineEnding, decoded.hadBom)
        assertContentEquals(original, reencoded)
    }

    @Test
    fun lfFileWithoutBomRoundTrips() {
        val original = "a\nb\n".toByteArray(Charsets.UTF_8)
        val decoded = TextEncoding.decode(original)
        val reencoded = TextEncoding.encode(decoded.text, decoded.charset, decoded.lineEnding, decoded.hadBom)
        assertContentEquals(original, reencoded)
    }
}
