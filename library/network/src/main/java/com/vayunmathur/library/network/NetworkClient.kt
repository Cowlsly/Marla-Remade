package com.vayunmathur.library.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLSocketFactory

/**
 * Android-only HTTP client – HttpURLConnection only, no Ktor/OkHttp.
 *
 * Hardened with reduced CA bundles via [TrustBundle] + [BundledTrust].
 * Call [init] early (Application.onCreate or MainActivity.onCreate) to pin to a minimal root set.
 * Email/web/vpn use SYSTEM (platform default) because they contact arbitrary user hosts / browser URLs.
 *
 * Public API binary-compatible:
 *  SimpleResponse(status,statusMessage,body,headers,url){ isSuccess, contentLength }
 *  RawResponse(status,statusMessage,bytes,headers,url){ isSuccess, text, header(), headerValues() }
 *  NetworkDataStream { suspend read(buffer[,offset,length]), isClosedForRead }
 *  performRequestBytes / Full / performRequest / execute / stream / getContentLength /
 *  performRequestInputStream / callJson / getJson via kotlinx-serialization-json
 */
data class SimpleResponse(
    val status: Int,
    val statusMessage: String,
    val body: String,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299
    val contentLength: Long? get() = headers.entries.firstOrNull {
        it.key.equals("Content-Length", ignoreCase = true)
    }?.value?.firstOrNull()?.toLongOrNull()

    /** Case-insensitive single-value header lookup. */
    fun header(name: String): String? = headerValues(name).firstOrNull()

    /** Case-insensitive all-values header lookup (e.g. multiple Set-Cookie). */
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
}

/**
 * Byte-oriented response. Use instead of [SimpleResponse] when the body is
 * binary (protobuf, images, ciphertext) and a UTF-8 round-trip would corrupt it.
 */
class RawResponse(
    val status: Int,
    val statusMessage: String,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299

    /** The body decoded as UTF-8. Only meaningful for textual responses. */
    val text: String get() = bytes.toString(Charsets.UTF_8)

    /** Case-insensitive single-value header lookup. */
    fun header(name: String): String? = headerValues(name).firstOrNull()

    /** Case-insensitive all-values header lookup (e.g. multiple Set-Cookie). */
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
}

interface NetworkDataStream {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    val isClosedForRead: Boolean
}

/** Adapts already-buffered bytes to [NetworkDataStream] so the same framing/parsing
 *  code can run over a live stream or a buffered body. */
fun ByteArray.asNetworkDataStream(): NetworkDataStream {
    val src = this
    return object : NetworkDataStream {
        private var pos = 0
        override val isClosedForRead: Boolean get() = pos >= src.size
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= src.size) return -1
            val n = minOf(length, src.size - pos)
            System.arraycopy(src, pos, buffer, offset, n)
            pos += n
            return n
        }
    }
}

object NetworkClient {

    private const val TAG = "NetworkClient"

    // Published API – accessible from public inline functions.
    @PublishedApi
    internal val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    @Volatile
    var defaultSslSocketFactory: SSLSocketFactory? = null
        private set

    @Volatile
    var currentBundle: TrustBundle = TrustBundle.SYSTEM
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context, bundle: TrustBundle) {
        val appCtx = context.applicationContext
        // Allow re-init with different bundle (useful for tests) but avoid redundant work.
        if (initialized && bundle == currentBundle && (bundle == TrustBundle.SYSTEM || defaultSslSocketFactory != null)) {
            return
        }
        try {
            currentBundle = bundle
            if (bundle == TrustBundle.SYSTEM) {
                defaultSslSocketFactory = null
                Log.i(TAG, "Initialized with SYSTEM bundle (platform default trust)")
            } else {
                val result = BundledTrust.createFactory(appCtx, bundle)
                defaultSslSocketFactory = result?.first
                if (defaultSslSocketFactory == null) {
                    Log.w(TAG, "Bundle $bundle produced no factory (missing DERs?), falling back to system trust")
                } else {
                    Log.i(TAG, "Initialized with bundle $bundle")
                }
            }
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "init failed for bundle $bundle, falling back to system", e)
            if (bundle == TrustBundle.SYSTEM) {
                defaultSslSocketFactory = null
            }
            // Even on failure we mark initialized to avoid repeat crashes, but keep bundle.
            initialized = true
        }
    }

    @Synchronized
    fun initWithFactory(factory: SSLSocketFactory?, bundle: TrustBundle = TrustBundle.SYSTEM) {
        defaultSslSocketFactory = factory
        currentBundle = bundle
        initialized = true
    }

    private fun resolveFactory(
        explicit: SSLSocketFactory?,
        useSystemTrust: Boolean,
    ): SSLSocketFactory? {
        if (useSystemTrust) return null
        return explicit ?: defaultSslSocketFactory
    }

    // ------------------------------------------------------------------
    // Bytes / Full / SimpleResponse
    // ------------------------------------------------------------------

    suspend fun performRequestBytes(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Pair<Int, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return r.status to r.bodyBytes
    }

    suspend fun performRequestBytesFull(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return Triple(r.status, r.headers, r.bodyBytes)
    }

    suspend fun performRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): SimpleResponse {
        // internalExecute suspends on Dispatchers.IO but returns to the caller's dispatcher,
        // so decoding the body here would run the (potentially hundreds of MB) UTF-8
        // conversion on whatever thread called us — the main thread, for Compose callers.
        return withContext(Dispatchers.IO) {
            val r = HttpUrlEngine.internalExecute(
                url, method, headers, HttpUrlEngine.toBodyBytes(body),
                followRedirects = true, connectTimeoutMs = null,
                sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            )
            SimpleResponse(r.status, r.statusMessage, r.bodyBytes.toString(Charsets.UTF_8), r.headers, r.finalUrl)
        }
    }

    /**
     * Full-control buffered request: binary body, per-request timeouts and
     * optional redirect suppression. Does not throw on 4xx/5xx — inspect
     * [RawResponse.status].
     *
     * [sslSocketFactory] pins the TLS trust anchors for this call (Signal uses
     * a factory built over its bundled root); null keeps the platform default or
     * the bundle default set via [init]. Use [useSystemTrust]=true to force system
     * trust for dynamic hosts (email custom IMAP hostnames, vpn user endpoint).
     */
    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        followRedirects: Boolean = true,
        connectTimeoutMs: Long? = null,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): RawResponse {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects, connectTimeoutMs, readTimeoutMs,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return RawResponse(r.status, r.statusMessage, r.bodyBytes, r.headers, r.finalUrl)
    }

    // ------------------------------------------------------------------
    // Streaming variants
    // ------------------------------------------------------------------

    /**
     * Open a response and hand its body to [block] as a live stream, keeping the
     * connection open for the duration of the callback. On a non-streamable status
     * the block gets a null stream and the (buffered) error body in
     * [SimpleResponse.body].
     */
    suspend fun stream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        connectTimeoutMs: Long? = null,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
        block: suspend (stream: NetworkDataStream?, response: SimpleResponse) -> Unit,
    ): SimpleResponse {
        var currentUrl = url
        var currentMethod = method
        var currentBody = HttpUrlEngine.toBodyBytes(body)
        var redirects = 0
        var lastConn: HttpURLConnection? = null
        var finalStatus = 0
        var finalMessage = ""
        var finalHeaders: Map<String, List<String>> = emptyMap()
        var finalUrl = url

        val effectiveFactory = resolveFactory(sslSocketFactory, useSystemTrust)

        withContext(Dispatchers.IO) {
            while (true) {
                val conn = HttpUrlEngine.openConnection(
                    currentUrl, currentMethod, headers, currentBody, connectTimeoutMs, readTimeoutMs,
                    sslSocketFactory = effectiveFactory,
                )
                lastConn = conn
                finalStatus = conn.responseCode
                finalMessage = conn.responseMessage ?: ""
                finalHeaders = HttpUrlEngine.extractHeaders(conn)
                finalUrl = conn.url.toString().let { if (it == currentUrl) currentUrl else it }

                if (finalStatus in 301..308 && finalStatus != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                    if (loc != null) {
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        if (finalStatus == 303) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        redirects++
                        conn.disconnect()
                        continue
                    }
                }
                break
            }
        }

        var simple = SimpleResponse(finalStatus, finalMessage, "", finalHeaders, finalUrl)
        val conn = lastConn!!

        if (simple.isSuccess || simple.status == 206) {
            var raw: InputStream? = withContext(Dispatchers.IO) {
                var s: InputStream? = try {
                    if (finalStatus >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) { null }
                HttpUrlEngine.maybeDecompress(conn, s)
            }

            if (raw != null) {
                var closed = false
                val streamObj = object : NetworkDataStream {
                    override val isClosedForRead: Boolean get() = closed
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        withContext(Dispatchers.IO) {
                            try {
                                val n = raw.read(buffer, offset, length)
                                if (n == -1) closed = true
                                n
                            } catch (_: Exception) {
                                closed = true
                                -1
                            }
                        }
                }
                try {
                    block(streamObj, simple)
                } finally {
                    withContext(Dispatchers.IO) {
                        try { raw.close() } catch (_: Exception) {}
                        conn.disconnect()
                    }
                }
            } else {
                withContext(Dispatchers.IO) { conn.disconnect() }
                block(null, simple)
            }
        } else {
            val errorBody = withContext(Dispatchers.IO) {
                val s = try {
                    if (finalStatus >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) { null }
                val bytes = try {
                    HttpUrlEngine.maybeDecompress(conn, s)?.use { it.readBytes() } ?: ByteArray(0)
                } catch (_: Exception) { ByteArray(0) }
                conn.disconnect()
                bytes.toString(Charsets.UTF_8)
            }
            simple = simple.copy(body = errorBody)
            block(null, simple)
        }
        return simple
    }

    /**
     * Used by youpipe SABR – returns code, headers, InputStream that disconnects on close.
     * Manual redirect handling; no forced Content-Type when body == null.
     */
    suspend fun performRequestInputStream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        timeoutMs: Long? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Triple<Int, Map<String, List<String>>, InputStream> {
        return withContext(Dispatchers.IO) {
            var currentUrl = url
            var currentMethod = method
            var currentBody = HttpUrlEngine.toBodyBytes(body)
            var redirects = 0
            var out: Triple<Int, Map<String, List<String>>, InputStream>? = null
            val effectiveFactory = resolveFactory(sslSocketFactory, useSystemTrust)

            while (out == null) {
                val conn = HttpUrlEngine.openConnection(
                    currentUrl, currentMethod, headers, currentBody, timeoutMs,
                    sslSocketFactory = effectiveFactory,
                )
                val status = try {
                    conn.responseCode
                } catch (e: java.io.IOException) {
                    conn.disconnect()
                    throw e
                }
                val respHeaders = HttpUrlEngine.extractHeaders(conn)

                if (status in 301..308 && status != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
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

                var stream: InputStream? = try {
                    if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                } catch (_: Exception) { null }

                stream = HttpUrlEngine.maybeDecompress(conn, stream)

                val finalStream: InputStream = stream?.let { s ->
                    object : InputStream() {
                        override fun read(): Int = s.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
                        override fun close() {
                            try { s.close() } catch (_: Exception) {}
                            conn.disconnect()
                        }
                    }
                } ?: ByteArrayInputStream(ByteArray(0)).also { conn.disconnect() }

                out = Triple(status, respHeaders, finalStream)
            }

            out
        }
    }

    suspend fun getContentLength(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Long? {
        return withContext(Dispatchers.IO) {
            var currentUrl = url
            var redirects = 0
            var lenResult: Long? = null
            var done = false
            val effectiveFactory = resolveFactory(sslSocketFactory, useSystemTrust)

            while (!done) {
                val conn = HttpUrlEngine.openConnection(
                    currentUrl, "HEAD", headers, null, null,
                    sslSocketFactory = effectiveFactory,
                )
                try {
                    val status = conn.responseCode
                    val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
                        ?: conn.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    val respHeaders = HttpUrlEngine.extractHeaders(conn)

                    if (status in 301..308 && status != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
                        val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                        if (loc != null) {
                            currentUrl = URL(URL(currentUrl), loc).toString()
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }
                    conn.disconnect()
                    lenResult = len ?: respHeaders.entries.firstOrNull {
                        it.key.equals("Content-Length", ignoreCase = true)
                    }?.value?.firstOrNull()?.toLongOrNull()
                    done = true
                } catch (_: Exception) {
                    conn.disconnect()
                    lenResult = null
                    done = true
                }
            }

            lenResult
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    suspend inline fun <reified T> callJson(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T {
        val simple = performRequest(url, method, headers, body, sslSocketFactory, useSystemTrust)

        if (simple.status == 204 || simple.body.isEmpty() || simple.contentLength == 0L) {
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Boolean::class -> return (simple.status in 200..299) as T
                Unit::class -> return Unit as T
            }
        }

        if (!simple.isSuccess) {
            throw java.io.IOException("HTTP ${simple.status}: ${simple.body.take(500)}")
        }

        return if (simple.body.isBlank()) {
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Boolean::class -> (true as T)
                Unit::class -> (Unit as T)
                else -> jsonConfig.decodeFromString(simple.body.ifBlank { "null" })
            }
        } else {
            jsonConfig.decodeFromString(simple.body)
        }
    }

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T = callJson(url, "GET", headers, null, sslSocketFactory, useSystemTrust)
}
