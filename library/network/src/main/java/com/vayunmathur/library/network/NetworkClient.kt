package com.vayunmathur.library.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.PushbackInputStream
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

    /**
     * How much of a non-success body [stream] keeps for the error message. Error bodies are
     * diagnostics, not payloads, so they are read as a bounded prefix rather than in full.
     */
    private const val ERROR_PREFIX_BYTES = 64 * 1024

    /** The JSON path only quotes 500 characters, so it needs far less than [ERROR_PREFIX_BYTES]. */
    private const val JSON_ERROR_PREFIX_BYTES = 4 * 1024

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

    /** Reads at most [maxBytes] as UTF-8; a failed read just shortens the diagnostic. */
    private fun readErrorPrefix(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        var used = 0
        try {
            while (used < buffer.size) {
                val n = input.read(buffer, used, buffer.size - used)
                if (n < 0) break
                used += n
            }
        } catch (_: Exception) {
        }
        return String(buffer, 0, used, Charsets.UTF_8)
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
        // The body is decoded straight off the socket here rather than after returning, so the
        // (potentially hundreds of MB) UTF-8 conversion never lands on the caller's dispatcher —
        // the main thread, for Compose callers.
        return withContext(Dispatchers.IO) {
            val response = HttpUrlEngine.openResponse(
                url, method, headers, HttpUrlEngine.toBodyBytes(body),
                followRedirects = true, connectTimeoutMs = null,
                sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            )
            response.use {
                val text = try {
                    it.stream.reader(Charsets.UTF_8).buffered().readText()
                } catch (e: Exception) {
                    throw java.io.IOException("Failed to read response body from ${it.finalUrl}", e)
                }
                SimpleResponse(it.status, it.statusMessage, text, it.headers, it.finalUrl)
            }
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
     * the block gets a null stream and a bounded prefix of the error body in
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
        val response = HttpUrlEngine.openResponse(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            // This entry point has always let connect failures surface as they are.
            wrapConnectErrors = false,
        )

        var simple = SimpleResponse(response.status, response.statusMessage, "", response.headers, response.finalUrl)

        if (simple.isSuccess || simple.status == 206) {
            if (!response.hasStream) {
                withContext(Dispatchers.IO) { response.close() }
                block(null, simple)
                return simple
            }
            val raw = response.stream
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
                withContext(Dispatchers.IO) { response.close() }
            }
        } else {
            val errorBody = withContext(Dispatchers.IO) {
                response.use { readErrorPrefix(it.stream, ERROR_PREFIX_BYTES) }
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
        val response = HttpUrlEngine.openResponse(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            // SABR callers expect the raw IOException, not a wrapped one.
            wrapConnectErrors = false,
        )
        return Triple(response.status, response.headers, response.stream)
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

    /**
     * Streaming JSON request. The body is parsed straight off the socket, so no whole-body
     * `ByteArray` or `String` is ever materialized.
     *
     * Not inline itself so that [callJson] only has to supply the reified pieces.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal suspend fun <T> decodeJsonResponse(
        url: String,
        method: String,
        headers: Map<String, *>,
        body: Any?,
        sslSocketFactory: SSLSocketFactory?,
        useSystemTrust: Boolean,
        deserializer: DeserializationStrategy<T>,
        isBoolean: Boolean,
        isUnit: Boolean,
    ): T = withContext(Dispatchers.IO) {
        val response = HttpUrlEngine.openResponse(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        response.use { resp ->
            // The empty-body shortcuts below need to know whether there is anything to parse.
            // Leading whitespace means nothing to a JSON parser, so it is skipped while looking:
            // a body of just a newline counted as empty before this was a stream, and endpoints
            // that answer `callJson<Unit>` that way must keep working.
            val peekable = PushbackInputStream(resp.stream, 1)
            var first = -1
            try {
                do {
                    first = peekable.read()
                } while (first == ' '.code || first == '\t'.code || first == '\r'.code || first == '\n'.code)
            } catch (e: Exception) {
                throw java.io.IOException("Failed to read response body from ${resp.finalUrl}", e)
            }
            if (first >= 0) peekable.unread(first)
            val empty = first < 0

            if (resp.status == 204 || empty) {
                if (isBoolean) return@use resp.isSuccess as T
                if (isUnit) return@use Unit as T
            }

            if (!resp.isSuccess) {
                val prefix = readErrorPrefix(peekable, JSON_ERROR_PREFIX_BYTES)
                throw java.io.IOException("HTTP ${resp.status}: ${prefix.take(500)}")
            }

            if (empty) {
                jsonConfig.decodeFromString(deserializer, "null")
            } else {
                jsonConfig.decodeFromStream(deserializer, peekable)
            }
        }
    }

    suspend inline fun <reified T> callJson(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T = decodeJsonResponse(
        url, method, headers, body, sslSocketFactory, useSystemTrust,
        deserializer = serializer<T>(),
        isBoolean = T::class == Boolean::class,
        isUnit = T::class == Unit::class,
    )

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T = callJson(url, "GET", headers, null, sslSocketFactory, useSystemTrust)
}
