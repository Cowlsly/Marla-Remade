package com.vayunmathur.cast.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteRangeTest {

    @Test
    fun `no range header means the whole entity`() {
        assertEquals(ByteRangeResult.Whole, parseByteRange(null, 100))
    }

    @Test
    fun `a closed range is taken literally and is inclusive`() {
        val result = parseByteRange("bytes=0-99", 1000)
        assertEquals(ByteRangeResult.Partial(ByteRange(0, 99)), result)
        assertEquals(100, (result as ByteRangeResult.Partial).range.length)
    }

    @Test
    fun `an open ended range runs to the last byte`() {
        // What a player sends when the user seeks: everything from here on.
        assertEquals(
            ByteRangeResult.Partial(ByteRange(500, 999)),
            parseByteRange("bytes=500-", 1000),
        )
    }

    @Test
    fun `a suffix range counts back from the end`() {
        assertEquals(
            ByteRangeResult.Partial(ByteRange(900, 999)),
            parseByteRange("bytes=-100", 1000),
        )
    }

    @Test
    fun `a suffix longer than the entity is the whole entity`() {
        assertEquals(
            ByteRangeResult.Partial(ByteRange(0, 99)),
            parseByteRange("bytes=-500", 100),
        )
    }

    @Test
    fun `a last byte position past the end is clamped`() {
        assertEquals(
            ByteRangeResult.Partial(ByteRange(90, 99)),
            parseByteRange("bytes=90-100000", 100),
        )
    }

    @Test
    fun `a first byte position past the end is unsatisfiable`() {
        assertEquals(ByteRangeResult.Unsatisfiable, parseByteRange("bytes=100-200", 100))
        assertEquals(ByteRangeResult.Unsatisfiable, parseByteRange("bytes=100-", 100))
    }

    @Test
    fun `a reversed range is unsatisfiable`() {
        assertEquals(ByteRangeResult.Unsatisfiable, parseByteRange("bytes=50-10", 100))
    }

    @Test
    fun `a zero length suffix is unsatisfiable`() {
        assertEquals(ByteRangeResult.Unsatisfiable, parseByteRange("bytes=-0", 100))
    }

    @Test
    fun `an unparseable or unsupported header is ignored rather than rejected`() {
        // RFC 9110 6.1: a Range the server cannot parse must be ignored, which means
        // answering 200 with the whole body - never 416.
        assertEquals(ByteRangeResult.Whole, parseByteRange("bytes=abc-def", 100))
        assertEquals(ByteRangeResult.Whole, parseByteRange("items=0-10", 100))
        assertEquals(ByteRangeResult.Whole, parseByteRange("bytes=0-9,20-29", 100))
        assertEquals(ByteRangeResult.Whole, parseByteRange("bytes=", 100))
    }

    @Test
    fun `whitespace and case in the header are tolerated`() {
        assertEquals(
            ByteRangeResult.Partial(ByteRange(0, 9)),
            parseByteRange("  Bytes= 0 - 9 ", 100),
        )
    }
}
