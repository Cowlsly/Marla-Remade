package com.vayunmathur.library.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [HttpUrlEngine.drainFully], the replacement for `InputStream.readBytes()` on the paths
 * that still have to return a whole body. Content-Length is a hint here, never a promise.
 */
class HttpUrlEngineBodyTest {

    private val segment = 64 * 1024

    private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    /** Hands out at most [chunk] bytes per read, the way a socket does. */
    private fun trickle(data: ByteArray, chunk: Int): InputStream =
        object : InputStream() {
            private val src = ByteArrayInputStream(data)
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                src.read(b, off, minOf(len, chunk))
        }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test
    fun exact_content_length_is_allocated_once() {
        val data = bytes(5000)
        val result = HttpUrlEngine.drainFully(trickle(data, 137), 5000L, true)
        assertContentEquals(data, result)
        assertEquals(5000, result.size)
    }

    @Test
    fun absent_content_length_reads_to_eof() {
        val data = bytes(9001)
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), null, true))
    }

    @Test
    fun content_length_longer_than_stream_is_trimmed() {
        val data = bytes(1200)
        val result = HttpUrlEngine.drainFully(ByteArrayInputStream(data), 1_000_000L, true)
        assertContentEquals(data, result)
        assertEquals(1200, result.size)
    }

    @Test
    fun content_length_shorter_than_stream_keeps_the_excess() {
        val data = bytes(4096)
        val result = HttpUrlEngine.drainFully(trickle(data, 500), 100L, true)
        assertContentEquals(data, result)
    }

    @Test
    fun a_wildly_overstated_content_length_is_not_allocated() {
        // Content-Length is attacker-controlled, so a tiny body advertised as gigabytes must not
        // be pre-sized to; it falls back to segments and still comes back exact.
        val data = bytes(3)
        val result = HttpUrlEngine.drainFully(ByteArrayInputStream(data), 4L * 1024 * 1024 * 1024, true)
        assertContentEquals(data, result)
    }

    @Test
    fun empty_body_yields_empty_array() {
        assertEquals(0, HttpUrlEngine.drainFully(ByteArrayInputStream(ByteArray(0)), null, true).size)
        assertEquals(0, HttpUrlEngine.drainFully(ByteArrayInputStream(ByteArray(0)), 0L, true).size)
    }

    @Test
    fun body_spanning_several_segments_without_a_hint() {
        val data = bytes(segment * 3 + 17)
        assertContentEquals(data, HttpUrlEngine.drainFully(trickle(data, 7919), null, true))
    }

    @Test
    fun body_landing_exactly_on_a_segment_boundary() {
        val data = bytes(segment * 2)
        assertContentEquals(data, HttpUrlEngine.drainFully(trickle(data, 4096), null, true))
    }

    @Test
    fun content_length_is_ignored_when_the_body_is_encoded() {
        // The header describes the gzipped length; the stream handed over is already inflated,
        // so pre-sizing to it would truncate.
        val data = bytes(30_000)
        val encodedLength = gzip(data).size.toLong()
        assertTrue(encodedLength < data.size, "gzip should shrink this fixture")
        assertContentEquals(
            data,
            HttpUrlEngine.drainFully(ByteArrayInputStream(data), encodedLength, false),
        )
    }

    @Test
    fun read_failure_reports_how_far_it_got() {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 300) throw IOException("boom")
                val n = minOf(len, 300 - served)
                b.fill(1, off, off + n)
                served += n
                return n
            }
        }
        val e = assertFailsWith<HttpUrlEngine.BodyReadException> {
            HttpUrlEngine.drainFully(failing, null, true)
        }
        assertEquals(300L, e.bytesRead)
    }
}
