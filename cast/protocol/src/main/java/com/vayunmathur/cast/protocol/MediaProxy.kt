package com.vayunmathur.cast.protocol

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Big enough that a range serve is a handful of writes, small enough to hold per connection. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * The HTTP half of the media proxy: how a request is read, checked and answered.
 *
 * The phone serves app media to the TV over HTTPS instead of re-encoding it into RTP, so this
 * is the wire format for that. It lives in `:cast:protocol` rather than beside the listener in
 * `:cast` for the same reason the handshake rules do: the rules have to be testable without a
 * device, and `android.util.Log` is not available to a JVM unit test. The listener in `:cast`
 * owns the sockets and the logging; everything that decides what bytes come back is here.
 *
 * Only what a player actually sends is implemented. `GET` and `HEAD`, one byte range at a time,
 * no chunked bodies and no redirects - ExoPlayer asks for nothing else, and a proxy that
 * answers requests nobody makes is a proxy with untested code paths in it.
 */
object MediaProxy {

    /**
     * The capability token is a path segment rather than a header because it has to survive
     * being handed to a player as a plain URL. `DefaultHttpDataSource` will carry default
     * request properties, but a manifest's `<BaseURL>` and a subtitle URL are just strings, and
     * a token in the path travels with them for free.
     */
    fun url(host: String, port: Int, token: String, resourceId: String): String =
        "https://$host:$port/$token/$resourceId"

    /** Longest request line or header line accepted, to bound what an unauthenticated peer can make us buffer. */
    const val MAX_LINE_BYTES = 8 * 1024

    /** Most header lines accepted, for the same reason. */
    const val MAX_HEADERS = 64

    /**
     * Compares tokens in constant time.
     *
     * A byte-at-a-time comparison leaks the length of the matching prefix, and the token is the
     * only thing standing between a LAN peer and the user's media. Length is compared first
     * because [MessageDigest.isEqual] short-circuits on mismatched lengths, which is fine: the
     * length is not the secret.
     */
    fun tokenMatches(expected: String, offered: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.US_ASCII),
        offered.toByteArray(Charsets.US_ASCII),
    )
}

/** A resource the proxy can serve: a known length and a stream that can start part-way in. */
interface MediaResource {

    /**
     * Total length in bytes.
     *
     * Known up front even when the bytes are not all present yet - a SABR segment still on its
     * way down has a length from its index. That is why [open] is allowed to end early and why
     * the caller has to notice when it does.
     */
    val length: Long

    val contentType: String

    /** A stream positioned at [offset]. The caller closes it. */
    fun open(offset: Long): InputStream
}

/** Maps the resource id in a request path to something servable, or null if there is no such resource. */
fun interface MediaResourceResolver {
    fun resolve(resourceId: String): MediaResource?
}

/** What a byte range in a request turned out to mean. */
sealed interface RangeSpec {

    /** No `Range` header, or one this proxy chooses not to honour. The whole resource, `200`. */
    object Whole : RangeSpec

    /** Both ends inclusive, as HTTP counts them. */
    data class Satisfiable(val first: Long, val last: Long) : RangeSpec {
        val length: Long get() = last - first + 1
    }

    /** Syntactically fine but outside the resource, which is a `416` rather than a clamp. */
    object Unsatisfiable : RangeSpec
}

/** How an exchange ended, for the listener to log. */
sealed interface ExchangeOutcome {

    data class Served(val resourceId: String, val range: RangeSpec, val bytes: Long) : ExchangeOutcome

    /** Answered with an error status. [detail] is for the log, never for the client. */
    data class Rejected(val status: Int, val detail: String) : ExchangeOutcome

    /**
     * Fewer bytes arrived from the resource than its length promised.
     *
     * The response has already been committed with a `Content-Length` by then, so there is no
     * way to turn this into an error status; the connection must be closed so the client sees a
     * truncated body instead of waiting for bytes that are not coming.
     */
    data class Truncated(val resourceId: String, val expected: Long, val actual: Long) : ExchangeOutcome

    /** The peer closed before sending a request. Ordinary at the end of a keep-alive connection. */
    object Closed : ExchangeOutcome
}

/**
 * Serves requests for one connection.
 *
 * Keep-alive is supported deliberately. Scrubbing turns into a burst of range requests, and a
 * fresh TLS handshake for each one would put the cost of a seek back into the same place the
 * RTP path had it.
 */
class MediaProxyExchange(
    private val token: String,
    private val resolver: MediaResourceResolver,
) {

    /**
     * Reads one request and writes one response.
     *
     * Returns whether the connection may be reused, alongside the outcome. Anything that leaves
     * the stream in an unknown state - a malformed request, a truncated body - ends the
     * connection, because the alternative is misreading the next request off a desynchronised
     * stream.
     */
    fun serve(input: InputStream, output: OutputStream): Result {
        val requestLine = readLine(input) ?: return Result(ExchangeOutcome.Closed, reusable = false)

        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            respondError(output, 400)
            return Result(ExchangeOutcome.Rejected(400, "malformed request line"), reusable = false)
        }
        val method = parts[0]
        val target = parts[1]

        val headers = readHeaders(input)
            ?: run {
                respondError(output, 431)
                return Result(ExchangeOutcome.Rejected(431, "too many or too long headers"), reusable = false)
            }

        if (method != "GET" && method != "HEAD") {
            respondError(output, 405)
            return Result(ExchangeOutcome.Rejected(405, "method $method"), reusable = true)
        }

        val path = parsePath(target)
        if (path == null || !MediaProxy.tokenMatches(token, path.token)) {
            // Deliberately the same answer for a bad token and an unparseable path: telling a
            // peer which of the two it got wrong tells it the shape of a valid URL.
            respondError(output, 403)
            return Result(ExchangeOutcome.Rejected(403, "bad token or path '$target'"), reusable = true)
        }

        val resource = resolver.resolve(path.resourceId)
        if (resource == null) {
            respondError(output, 404)
            return Result(ExchangeOutcome.Rejected(404, "no resource '${path.resourceId}'"), reusable = true)
        }

        val range = parseRange(headers["range"], resource.length)
        if (range is RangeSpec.Unsatisfiable) {
            respondError(
                output,
                416,
                extraHeaders = listOf("Content-Range: bytes */${resource.length}"),
            )
            return Result(ExchangeOutcome.Rejected(416, "range outside ${resource.length}"), reusable = true)
        }

        val first = if (range is RangeSpec.Satisfiable) range.first else 0L
        val bodyLength = if (range is RangeSpec.Satisfiable) range.length else resource.length

        val head = StringBuilder()
        head.append(if (range is RangeSpec.Satisfiable) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(resource.contentType).append("\r\n")
        head.append("Content-Length: ").append(bodyLength).append("\r\n")
        // Without this ExoPlayer assumes the server cannot seek and refuses to scrub at all.
        head.append("Accept-Ranges: bytes\r\n")
        if (range is RangeSpec.Satisfiable) {
            head.append("Content-Range: bytes ")
                .append(range.first).append('-').append(range.last)
                .append('/').append(resource.length).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))

        if (method == "HEAD") {
            output.flush()
            return Result(ExchangeOutcome.Served(path.resourceId, range, 0), reusable = true)
        }

        val written = resource.open(first).use { body -> copy(body, output, bodyLength) }
        output.flush()

        return if (written < bodyLength) {
            Result(
                ExchangeOutcome.Truncated(path.resourceId, bodyLength, written),
                reusable = false,
            )
        } else {
            Result(ExchangeOutcome.Served(path.resourceId, range, written), reusable = true)
        }
    }

    data class Result(val outcome: ExchangeOutcome, val reusable: Boolean)

    // ------------------------------------------------------------------
    // Request reading
    // ------------------------------------------------------------------

    /**
     * Reads a CRLF-terminated line a byte at a time.
     *
     * Byte at a time rather than a buffered reader because the body must be left untouched for
     * [copy] - and on a keep-alive connection, over-reading past the headers would swallow the
     * start of the next request.
     */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (out.isEmpty()) null else out.toString()
            if (byte == '\n'.code) {
                if (out.isNotEmpty() && out.last() == '\r') out.setLength(out.length - 1)
                return out.toString()
            }
            if (out.length >= MediaProxy.MAX_LINE_BYTES) return null
            out.append(byte.toChar())
        }
    }

    /** Header names lowercased, because HTTP does not promise a case and clients do not agree on one. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return headers
            if (headers.size >= MediaProxy.MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    private fun copy(from: InputStream, to: OutputStream, limit: Long): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = limit
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = from.read(buffer, 0, want)
            if (read == -1) break
            to.write(buffer, 0, read)
            remaining -= read
        }
        return limit - remaining
    }

    private fun respondError(output: OutputStream, status: Int, extraHeaders: List<String> = emptyList()) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        head.append("Content-Length: 0\r\n")
        extraHeaders.forEach { head.append(it).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    companion object {

        data class ProxyPath(val token: String, val resourceId: String)

        /**
         * Splits `/<token>/<resourceId>` and drops any query string.
         *
         * The resource id may itself contain slashes: a SABR segment is naturally addressed as
         * `<itag>/<sequence>`, so everything after the token is the id.
         */
        fun parsePath(target: String): ProxyPath? {
            val withoutQuery = target.substringBefore('?')
            if (!withoutQuery.startsWith('/')) return null
            val rest = withoutQuery.substring(1)
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return ProxyPath(
                token = percentDecode(rest.substring(0, slash)),
                resourceId = percentDecode(rest.substring(slash + 1)),
            )
        }

        /**
         * Interprets a `Range` header against a known [length].
         *
         * Multi-range requests are answered with the whole resource rather than a multipart
         * body. HTTP permits ignoring a `Range` you do not want to honour, ExoPlayer never asks
         * for more than one range, and a multipart encoder would be code with no caller.
         */
        fun parseRange(header: String?, length: Long): RangeSpec {
            if (header == null) return RangeSpec.Whole
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Whole
            val spec = value.substring("bytes=".length).trim()
            if (spec.contains(',')) return RangeSpec.Whole

            val dash = spec.indexOf('-')
            if (dash < 0) return RangeSpec.Unsatisfiable
            val fromText = spec.substring(0, dash).trim()
            val toText = spec.substring(dash + 1).trim()

            // A zero-length resource can satisfy no range at all, not even `bytes=0-`.
            if (length <= 0L) return RangeSpec.Unsatisfiable

            if (fromText.isEmpty()) {
                // A suffix range: the last N bytes.
                val suffix = toText.toLongOrNull() ?: return RangeSpec.Unsatisfiable
                if (suffix <= 0L) return RangeSpec.Unsatisfiable
                val first = maxOf(0L, length - suffix)
                return RangeSpec.Satisfiable(first, length - 1)
            }

            val first = fromText.toLongOrNull() ?: return RangeSpec.Unsatisfiable
            if (first < 0L || first >= length) return RangeSpec.Unsatisfiable
            val last = if (toText.isEmpty()) {
                length - 1
            } else {
                val stated = toText.toLongOrNull() ?: return RangeSpec.Unsatisfiable
                if (stated < first) return RangeSpec.Unsatisfiable
                minOf(stated, length - 1)
            }
            return RangeSpec.Satisfiable(first, last)
        }

        private fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val out = java.io.ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                out.write(c.code)
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
