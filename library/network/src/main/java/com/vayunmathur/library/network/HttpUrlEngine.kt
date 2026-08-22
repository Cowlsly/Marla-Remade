package com.vayunmathur.library.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Android-only HTTP engine backed by HttpURLConnection.
 *
 * - withContext(IO) opens (URL(url).openConnection() as HttpURLConnection)
 * - connectTimeout 30000, readTimeout 60000 – both overridable per request
 * - custom verbs via reflection getDeclaredField("method")
 * - headers Map<String, *> where Iterable expands to multiple header lines
 * - body String/ByteArray with setFixedLengthStreamingMode, chunked fallback
 * - when body == null -> doOutput=false, no Content-Type forced (critical for SABR)
 * - manual redirect 301/302/303/307/308 up to 5 hops
 * - Content-Encoding br via org.brotli.dec.BrotliInputStream, gzip via GZIPInputStream,
 *   deflate via InflaterInputStream
 * - TLS: respects per-call sslSocketFactory, else falls back to NetworkClient.defaultSslSocketFactory.
 */
internal object HttpUrlEngine {

    const val CONNECT_TIMEOUT = 30_000
    const val READ_TIMEOUT = 60_000
    const val MAX_REDIRECTS = 5

    /** Segment size used when draining a body of unknown length. */
    private const val SEGMENT_SIZE = 64 * 1024

    /**
     * Content-Length is attacker-controlled, so it is only trusted as an allocation size up to this
     * much; larger bodies still read in full, just via segments instead of one up-front array.
     */
    private const val MAX_PRESIZE = 1L * 1024 * 1024

    private val EMPTY_BYTES = ByteArray(0)

    data class InternalResult(
        val status: Int,
        val statusMessage: String,
        val headers: Map<String, List<String>>,
        val bodyBytes: ByteArray,
        val finalUrl: String,
    )

    /**
     * A response whose body has *not* been read. [stream] is live and already decompressed;
     * closing it (or this) also disconnects the underlying connection.
     */
    class OpenResponse(
        val status: Int,
        val statusMessage: String,
        val headers: Map<String, List<String>>,
        val finalUrl: String,
        val stream: InputStream,
        /** False when the connection exposed no body stream at all. */
        val hasStream: Boolean,
        /** Raw Content-Length header, which describes the *encoded* length. */
        val contentLength: Long?,
        val isIdentityEncoding: Boolean,
    ) : Closeable {
        val isSuccess: Boolean get() = status in 200..299

        override fun close() {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    /** Read failure carrying how far the body got, so the caller can name the endpoint. */
    class BodyReadException(val bytesRead: Long, cause: Throwable) : IOException(cause)

    fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
    ): HttpURLConnection {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs?.toInt() ?: CONNECT_TIMEOUT
            readTimeout = readTimeoutMs?.toInt() ?: READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        // Certificate pinning / reduced trust: explicit factory wins, else app-wide default, else system.
        val effectiveFactory = sslSocketFactory ?: NetworkClient.defaultSslSocketFactory
        if (effectiveFactory != null && conn is HttpsURLConnection) {
            conn.sslSocketFactory = effectiveFactory
        }

        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            var clazz: Class<*>? = conn.javaClass
            var success = false
            while (clazz != null && !success) {
                try {
                    val f = clazz.getDeclaredField("method")
                    f.isAccessible = true
                    f.set(conn, method)
                    success = true
                } catch (_: Exception) {
                    clazz = clazz.superclass
                }
            }
            if (!success) {
                try {
                    val delegateField = conn.javaClass.getDeclaredField("delegate")
                    delegateField.isAccessible = true
                    val delegate = delegateField.get(conn)
                    val mf = delegate.javaClass.getDeclaredField("method")
                    mf.isAccessible = true
                    mf.set(delegate, method)
                } catch (e: Exception) {
                    throw java.net.ProtocolException("Cannot set custom method $method: ${e.message}")
                }
            }
        }

        headers.forEach { (k, v) ->
            when (v) {
                is Iterable<*> -> v.forEach { elem ->
                    if (elem != null) conn.addRequestProperty(k, elem.toString())
                }
                else -> if (v != null) conn.setRequestProperty(k, v.toString())
            }
        }

        if (bodyBytes != null) {
            try {
                conn.setFixedLengthStreamingMode(bodyBytes.size)
            } catch (_: Exception) {
                try { conn.setChunkedStreamingMode(0) } catch (_: Exception) {}
            }
            conn.outputStream.use { it.write(bodyBytes) }
        }

        return conn
    }

    fun extractHeaders(conn: HttpURLConnection): Map<String, List<String>> {
        return conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
    }

    fun maybeDecompress(conn: HttpURLConnection, raw: InputStream?): InputStream? {
        if (raw == null) return null
        val encoding = conn.getHeaderField("Content-Encoding")
            ?: conn.getHeaderField("content-encoding")
            ?: return raw
        val lower = encoding.lowercase()
        return when {
            lower.contains("br") -> try { org.brotli.dec.BrotliInputStream(raw) } catch (_: Throwable) { raw }
            lower.contains("gzip") -> try { GZIPInputStream(raw) } catch (_: Exception) { raw }
            lower.contains("deflate") -> try {
                InflaterInputStream(raw, Inflater(true))
            } catch (_: Exception) { raw }
            else -> raw
        }
    }

    fun toBodyBytes(body: Any?): ByteArray? = when (body) {
        null -> null
        is ByteArray -> body
        is String -> body.toByteArray(Charsets.UTF_8)
        else -> body.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The single connect -> follow-redirects -> decompress sequence every entry point is built on.
     * Hands back a live body instead of a buffered one so callers can consume incrementally.
     *
     * [wrapConnectErrors] reports a connect failure as `IOException("Failed to connect to ...")`;
     * with it off the original exception propagates unchanged (the streaming callers rely on that).
     */
    suspend fun openResponse(
        url: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        wrapConnectErrors: Boolean = true,
    ): OpenResponse = withContext(Dispatchers.IO) {
        var currentUrl = url
        var currentMethod = method
        var currentBody = bodyBytes
        var redirects = 0
        var result: OpenResponse? = null

        while (result == null) {
            val conn = openConnection(
                currentUrl, currentMethod, headers, currentBody,
                connectTimeoutMs, readTimeoutMs, sslSocketFactory,
            )
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                if (wrapConnectErrors) {
                    throw IOException("Failed to connect to $currentUrl: ${e.message}", e)
                }
                throw e
            }
            val msg = conn.responseMessage ?: ""
            val respHeaders = extractHeaders(conn)
            val finalUrl = conn.url.toString()

            if (followRedirects && status in 301..308 && status != 304 && redirects < MAX_REDIRECTS) {
                val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                if (loc != null) {
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    if (status == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    redirects++
                    try { conn.inputStream?.close() } catch (_: Exception) {}
                    conn.disconnect()
                    continue
                }
            }

            val raw: InputStream? = try {
                if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
            } catch (_: Exception) { null }

            val encoding = (conn.getHeaderField("Content-Encoding")
                ?: conn.getHeaderField("content-encoding"))?.trim()?.lowercase()
            val identity = encoding.isNullOrEmpty() || encoding == "identity"
            val declaredLength = conn.getHeaderField("Content-Length")?.toLongOrNull()

            val decompressed = maybeDecompress(conn, raw)
            val body: InputStream = if (decompressed == null) {
                conn.disconnect()
                ByteArrayInputStream(EMPTY_BYTES)
            } else {
                object : InputStream() {
                    override fun read(): Int = decompressed.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = decompressed.read(b, off, len)
                    override fun available(): Int = decompressed.available()
                    override fun close() {
                        try { decompressed.close() } catch (_: Exception) {}
                        conn.disconnect()
                    }
                }
            }

            result = OpenResponse(
                status, msg, respHeaders, finalUrl, body,
                hasStream = decompressed != null,
                contentLength = declaredLength,
                isIdentityEncoding = identity,
            )
        }

        result
    }

    /**
     * Read a whole body without ever growing an array by doubling.
     *
     * With a usable Content-Length on an unencoded body the result is allocated once at its exact
     * size; otherwise fixed-size segments accumulate and a single exact-size array is assembled at
     * the end. A header that over- or under-states the real length is tolerated, not trusted.
     *
     * Pure in its arguments so it can be unit-tested without a network or a Context.
     */
    fun drainFully(
        input: InputStream,
        contentLengthHint: Long?,
        isIdentityEncoding: Boolean,
    ): ByteArray {
        var read = 0L

        // Fills target from [from] until full or EOF; returns how much of it is populated.
        fun fill(target: ByteArray, from: Int): Int {
            var used = from
            while (used < target.size) {
                val n = try {
                    input.read(target, used, target.size - used)
                } catch (e: Exception) {
                    throw BodyReadException(read, e)
                }
                if (n < 0) break
                used += n
                read += n
            }
            return used
        }

        // Content-Length describes the encoded body, so it is only a size for identity encoding.
        val hint = if (isIdentityEncoding) contentLengthHint else null
        var presized: ByteArray? = null
        var presizedLen = 0
        if (hint != null && hint > 0 && hint <= MAX_PRESIZE) {
            val exact = ByteArray(hint.toInt())
            presizedLen = fill(exact, 0)
            // Header promised more than the stream delivered.
            if (presizedLen < exact.size) return exact.copyOf(presizedLen)
            presized = exact
        }

        // Whatever remains (the whole body when there was no usable hint, or the excess when the
        // header understated it) accumulates in segments so nothing is ever reallocated. Each
        // segment is allocated only once a byte for it is in hand, so a body that ended exactly
        // where the header said costs nothing extra.
        val segments = ArrayList<ByteArray>()
        var tail = 0L
        while (true) {
            val lead = try {
                input.read()
            } catch (e: Exception) {
                throw BodyReadException(read, e)
            }
            if (lead < 0) break
            read += 1
            val segment = ByteArray(SEGMENT_SIZE)
            segment[0] = lead.toByte()
            val used = fill(segment, 1)
            segments.add(if (used == SEGMENT_SIZE) segment else segment.copyOf(used))
            tail += used
            if (used < SEGMENT_SIZE) break
        }

        if (presized != null && tail == 0L) return presized
        if (presized == null) {
            if (segments.isEmpty()) return EMPTY_BYTES
            if (segments.size == 1) return segments[0]
        }

        val total = presizedLen + tail
        if (total > Int.MAX_VALUE) {
            throw IOException("Response body of $total bytes cannot be returned as a single array")
        }
        val out = ByteArray(total.toInt())
        var pos = 0
        if (presized != null) {
            System.arraycopy(presized, 0, out, 0, presizedLen)
            pos = presizedLen
        }
        for (segment in segments) {
            System.arraycopy(segment, 0, out, pos, segment.size)
            pos += segment.size
        }
        return out
    }

    /**
     * [drainFully] over an [OpenResponse], naming the endpoint if the read fails.
     *
     * A truncated body is an error here, where it used to be silently reported as an empty one:
     * hiding it left no way to tell which endpoint had failed, which is what #582 asked for.
     */
    fun readBody(response: OpenResponse): ByteArray = try {
        drainFully(response.stream, response.contentLength, response.isIdentityEncoding)
    } catch (e: BodyReadException) {
        throw IOException(
            "Failed to read response body from ${response.finalUrl} after ${e.bytesRead} bytes",
            e.cause,
        )
    }

    suspend fun internalExecute(
        url: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
    ): InternalResult = withContext(Dispatchers.IO) {
        val response = openResponse(
            url, method, headers, bodyBytes, followRedirects,
            connectTimeoutMs, readTimeoutMs, sslSocketFactory,
        )
        val bytes = response.use { readBody(it) }
        InternalResult(response.status, response.statusMessage, response.headers, bytes, response.finalUrl)
    }
}
