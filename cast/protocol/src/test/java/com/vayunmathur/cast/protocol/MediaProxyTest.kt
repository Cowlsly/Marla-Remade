package com.vayunmathur.cast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the media proxy's HTTP behaviour.
 *
 * The proxy replaces a re-encode with a byte serve, so the thing that has to be right is the
 * arithmetic of a byte range. A range served off by one is not a visible error at either end: the
 * TV's demuxer gets a frame it cannot parse and reports a corrupt stream, which is indistinguishable
 * from a hundred other faults. Every offset is therefore checked against the source bytes rather
 * than against a recomputed expectation.
 *
 * The refusals matter for a different reason. This is a socket open on the LAN, so a request with
 * the wrong token or no token has to be turned away without saying why, and without ever reaching
 * a resolver that would touch the user's files.
 */
class MediaProxyTest {

    private val token = "s3cr3t-token"
    private val media = ByteArray(5_000) { (it * 31 % 251).toByte() }

    // ------------------------------------------------------------------
    // Range arithmetic
    // ------------------------------------------------------------------

    @Test
    fun `serves the whole resource when nothing asks for a range`() {
        val response = exchange().get("/$token/track")
        assertEquals(200, response.status)
        assertEquals("5000", response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"], "without this ExoPlayer will not scrub at all")
        assertNull(response.headers["content-range"])
        assertContentEquals(media, response.body)
    }

    @Test
    fun `serves a closed range inclusive of both ends`() {
        val response = exchange().get("/$token/track", "Range: bytes=100-199")
        assertEquals(206, response.status)
        assertEquals("bytes 100-199/5000", response.headers["content-range"])
        assertEquals("100", response.headers["content-length"], "HTTP ranges include the last byte")
        assertContentEquals(media.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `serves an open range to the end of the resource`() {
        val response = exchange().get("/$token/track", "Range: bytes=4900-")
        assertEquals(206, response.status)
        assertEquals("bytes 4900-4999/5000", response.headers["content-range"])
        assertContentEquals(media.copyOfRange(4900, 5000), response.body)
    }

    @Test
    fun `serves a suffix range counted back from the end`() {
        val response = exchange().get("/$token/track", "Range: bytes=-250")
        assertEquals(206, response.status)
        assertEquals("bytes 4750-4999/5000", response.headers["content-range"])
        assertContentEquals(media.copyOfRange(4750, 5000), response.body)
    }

    @Test
    fun `clamps a range that runs past the end rather than refusing it`() {
        // Asking for more than exists is normal: a player that knows the length only
        // approximately will overshoot, and a 416 there would end playback.
        val response = exchange().get("/$token/track", "Range: bytes=4990-99999")
        assertEquals(206, response.status)
        assertEquals("bytes 4990-4999/5000", response.headers["content-range"])
        assertEquals(10, response.body.size)
    }

    @Test
    fun `serves a single byte`() {
        val response = exchange().get("/$token/track", "Range: bytes=1234-1234")
        assertEquals(206, response.status)
        assertEquals("1", response.headers["content-length"])
        assertContentEquals(byteArrayOf(media[1234]), response.body)
    }

    @Test
    fun `refuses a range that starts past the end`() {
        val response = exchange().get("/$token/track", "Range: bytes=5000-5100")
        assertEquals(416, response.status)
        assertEquals(
            "bytes */5000",
            response.headers["content-range"],
            "a 416 has to state the real length or the player cannot correct itself",
        )
    }

    @Test
    fun `ignores a multi-range request instead of answering it wrongly`() {
        // A multipart/byteranges body is code with no caller: ExoPlayer never asks for two
        // ranges. Serving the whole resource is a permitted answer; a malformed multipart
        // body would not be.
        val response = exchange().get("/$token/track", "Range: bytes=0-99, 200-299")
        assertEquals(200, response.status)
        assertEquals(5000, response.body.size)
    }

    @Test
    fun `parses range headers without needing a resource`() {
        assertEquals(RangeSpec.Satisfiable(0, 9), MediaProxyExchange.parseRange("bytes=0-9", 100))
        assertEquals(RangeSpec.Satisfiable(90, 99), MediaProxyExchange.parseRange("bytes=90-", 100))
        assertEquals(RangeSpec.Satisfiable(95, 99), MediaProxyExchange.parseRange("bytes=-5", 100))
        assertEquals(RangeSpec.Whole, MediaProxyExchange.parseRange(null, 100))
        // A unit we do not speak is not an error; the header is simply not honoured.
        assertEquals(RangeSpec.Whole, MediaProxyExchange.parseRange("items=0-9", 100))
        assertEquals(RangeSpec.Unsatisfiable, MediaProxyExchange.parseRange("bytes=100-", 100))
        assertEquals(RangeSpec.Unsatisfiable, MediaProxyExchange.parseRange("bytes=9-0", 100))
        assertEquals(RangeSpec.Unsatisfiable, MediaProxyExchange.parseRange("bytes=-0", 100))
        assertEquals(RangeSpec.Unsatisfiable, MediaProxyExchange.parseRange("bytes=abc-", 100))
        // A resource with no bytes can satisfy no range, and reporting one would have the
        // player read a body that is not there.
        assertEquals(RangeSpec.Unsatisfiable, MediaProxyExchange.parseRange("bytes=0-", 0))
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    fun `refuses a request carrying the wrong token`() {
        var asked = false
        val exchange = MediaProxyExchange(token) { asked = true; FakeResource(media) }
        val response = exchange.get("/not-the-token/track")
        assertEquals(403, response.status)
        assertTrue(!asked, "a bad token must be turned away before anything opens a file")
    }

    @Test
    fun `refuses a request with no token at all`() {
        assertEquals(403, exchange().get("/track").status)
        assertEquals(403, exchange().get("/").status)
    }

    @Test
    fun `answers an unknown resource and a bad token differently only in the log`() {
        // 403 for a bad token and 404 for a missing resource is deliberate: the token gate is
        // reached first, so a peer without the token learns nothing about what exists.
        assertEquals(403, exchange().get("/wrong/track").status)
        assertEquals(404, exchange().get("/$token/no-such-track").status)
    }

    @Test
    fun `refuses a method it does not implement`() {
        val response = exchange().request("PUT /$token/track HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(405, response.status)
    }

    @Test
    fun `ends the connection on a malformed request line rather than guessing`() {
        val result = exchange().serve("GARBAGE\r\n\r\n")
        assertEquals(400, assertIs<ExchangeOutcome.Rejected>(result.first.outcome).status)
        assertTrue(
            !result.first.reusable,
            "after an unparseable request the stream position is unknown, so the next read would " +
                "be nonsense",
        )
    }

    @Test
    fun `reports a peer that closed before asking for anything`() {
        val result = exchange().serve("")
        assertEquals(ExchangeOutcome.Closed, result.first.outcome)
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    @Test
    fun `keeps slashes inside a resource id`() {
        // A SABR segment is naturally addressed as itag and sequence number, so everything
        // after the token is the id.
        val path = MediaProxyExchange.parsePath("/tok/251/17")
        assertEquals("tok", path?.token)
        assertEquals("251/17", path?.resourceId)
    }

    @Test
    fun `drops a query string and decodes escapes`() {
        assertEquals("track 1", MediaProxyExchange.parsePath("/tok/track%201?cachebust=9")?.resourceId)
        assertNull(MediaProxyExchange.parsePath("relative/path"))
        assertNull(MediaProxyExchange.parsePath("/tok/"))
        assertNull(MediaProxyExchange.parsePath("/tok"))
    }

    // ------------------------------------------------------------------
    // Partly available resources
    // ------------------------------------------------------------------

    @Test
    fun `closes the connection when a resource yields less than its stated length`() {
        // The case a SABR segment hits: the length is known from the index while the last bytes
        // are still arriving. The response has a Content-Length by the time this is discovered,
        // so the only honest signal left is to close - a player that keeps the connection open
        // would wait for bytes that are not coming.
        val withheld = FakeResource(media, availableBytes = media.size - 1)
        val exchange = MediaProxyExchange(token) { withheld }
        val result = exchange.serve("GET /$token/track HTTP/1.1\r\nRange: bytes=4990-4999\r\n\r\n")

        val outcome = assertIs<ExchangeOutcome.Truncated>(result.first.outcome)
        assertEquals(10, outcome.expected)
        assertEquals(9, outcome.actual)
        assertTrue(!result.first.reusable, "a short body leaves the connection unusable")
    }

    @Test
    fun `serves a range that stops short of a withheld final byte in full`() {
        val withheld = FakeResource(media, availableBytes = media.size - 1)
        val exchange = MediaProxyExchange(token) { withheld }
        val response = exchange.get("/$token/track", "Range: bytes=4000-4500")
        assertEquals(206, response.status)
        assertContentEquals(media.copyOfRange(4000, 4501), response.body)
    }

    // ------------------------------------------------------------------
    // Connection reuse
    // ------------------------------------------------------------------

    @Test
    fun `serves several ranges down one connection`() {
        // Scrubbing is a burst of range requests. If each one needed a new connection the cost
        // of a seek would be a TLS handshake, which is what this path exists to avoid.
        val exchange = MediaProxyExchange(token) { FakeResource(media) }
        val input = ByteArrayInputStream(
            (
                "GET /$token/track HTTP/1.1\r\nRange: bytes=0-9\r\n\r\n" +
                    "GET /$token/track HTTP/1.1\r\nRange: bytes=10-19\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )

        val first = ByteArrayOutputStream()
        assertTrue(exchange.serve(input, first).reusable)
        assertContentEquals(media.copyOfRange(0, 10), parse(first.toByteArray()).body)

        val second = ByteArrayOutputStream()
        exchange.serve(input, second)
        assertContentEquals(
            media.copyOfRange(10, 20),
            parse(second.toByteArray()).body,
            "the second request was misread, which means the first over-read its headers",
        )
    }

    @Test
    fun `answers a HEAD with the headers and no body`() {
        val response = exchange().request("HEAD /$token/track HTTP/1.1\r\n\r\n")
        assertEquals(200, response.status)
        assertEquals("5000", response.headers["content-length"])
        assertEquals(0, response.body.size, "a HEAD body would desynchronise the connection")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun exchange() = MediaProxyExchange(token) { id ->
        if (id == "track" || id.startsWith("251/")) FakeResource(media) else null
    }

    private class FakeResource(
        private val bytes: ByteArray,
        override val contentType: String = "audio/ogg",
        /** Fewer than [bytes] to stand in for a file that is still being written. */
        private val availableBytes: Int = bytes.size,
    ) : MediaResource {
        override val length: Long get() = bytes.size.toLong()

        override fun open(offset: Long): InputStream {
            val from = offset.toInt()
            val count = (availableBytes - from).coerceAtLeast(0)
            return ByteArrayInputStream(bytes, from, count)
        }
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun MediaProxyExchange.get(target: String, vararg headers: String): Response =
        request("GET $target HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n")

    private fun MediaProxyExchange.request(raw: String): Response = parse(serve(raw).second)

    private fun MediaProxyExchange.serve(raw: String): Pair<MediaProxyExchange.Result, ByteArray> {
        val output = ByteArrayOutputStream()
        val result = serve(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), output)
        return result to output.toByteArray()
    }

    private fun parse(raw: ByteArray): Response {
        val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val end = (0..raw.size - separator.size).firstOrNull { at ->
            separator.indices.all { raw[at + it] == separator[it] }
        } ?: error("response has no header terminator")

        val lines = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        return Response(
            status = lines[0].split(' ')[1].toInt(),
            headers = lines.drop(1).filter { it.contains(':') }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            },
            body = raw.copyOfRange(end + separator.size, raw.size),
        )
    }
}
