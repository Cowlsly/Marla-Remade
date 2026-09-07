package com.vayunmathur.appstore.data.play

import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Implementation of gplayapi's IHttpClient using HttpURLConnection.
 * Mirrors Aurora Store's HttpClient.kt behavior but avoids OkHttp.
 *
 * Uses manual redirect handling (301-308, up to 5 hops) similar to
 * library:network HttpUrlEngine, and ensures POST with empty body does not
 * force an unwanted Content-Type.
 *
 * Requests are paced and a refusal is waited out rather than passed on. Play answers a
 * burst — "update all" across a phone's worth of Play apps is one purchase call and a
 * delivery per app — with 429, and the store used to hand that straight to the user as
 * "Failed: Too Many Requests" (issue #596). Pacing and backoff live here rather than at
 * each call site because the limit is on the account, not on any one feature: browse, the
 * update check and an install all spend from the same budget, and the background worker
 * runs in its own process-wide instance of the store's Play stack.
 */
class PlayHttpClient : IHttpClient {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 30_000
        private const val MAX_REDIRECTS = 5

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVICE_UNAVAILABLE = 503

        /** Smallest gap between two requests starting, before anything has been refused. */
        private const val MIN_REQUEST_GAP_MS = 250L
        private const val MAX_RETRIES = 3
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_CEILING_MS = 30_000L

        /** Spread so several waiting callers don't all return at the same instant. */
        private const val BACKOFF_JITTER_MS = 500L

        private val pacingLock = Any()

        /** Earliest the next request may start. Shared: the limit is per account, not per client. */
        private var nextRequestAtMs = 0L

        private val waiting = AtomicInteger(0)
        private val _throttled = MutableStateFlow(false)

        /** True while any caller is sitting out a refusal, for a status line rather than an error. */
        val throttled: StateFlow<Boolean> = _throttled.asStateFlow()
    }

    private val _responseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> get() = _responseCode.asStateFlow()

    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return execute(url, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val fullUrl = if (paramString.isNotEmpty()) {
            if (paramString.startsWith("?")) "$url$paramString" else "$url?$paramString"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun getAuth(url: String): PlayResponse {
        return execute(url, "GET", emptyMap(), null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        // POST with query params encoded in URL (as Aurora does)
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "POST", headers, ByteArray(0), contentType = null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): PlayResponse {
        return execute(url, "POST", headers, body)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        return execute(url, "POST", emptyMap(), body, contentType = "application/json")
    }

    /** One attempt's answer, plus how long the server asked us to wait before the next. */
    private data class Attempt(val response: PlayResponse, val retryAfterMs: Long)

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String? = null
    ): PlayResponse {
        var attempt = 0
        while (true) {
            awaitTurn()
            val (response, retryAfterMs) = executeOnce(url, method, headers, rawBody, contentType)
            val refused = response.code == TOO_MANY_REQUESTS || response.code == SERVICE_UNAVAILABLE
            if (!refused || attempt >= MAX_RETRIES) return response
            backOff(attempt, retryAfterMs)
            attempt++
        }
    }

    /** Block until the shared pacing window opens, and claim it. */
    private fun awaitTurn() {
        val wait = synchronized(pacingLock) {
            val now = System.currentTimeMillis()
            val startAt = maxOf(now, nextRequestAtMs)
            nextRequestAtMs = startAt + MIN_REQUEST_GAP_MS
            startAt - now
        }
        if (wait > 0) sleep(wait)
    }

    /**
     * Wait out a refusal, and hold every other caller back for the same period — a limit
     * this request hit is one the next one would hit too.
     */
    private fun backOff(attempt: Int, retryAfterMs: Long) {
        val delay = (retryAfterMs.takeIf { it > 0 } ?: (BACKOFF_BASE_MS shl attempt))
            .coerceAtMost(BACKOFF_CEILING_MS)
        synchronized(pacingLock) {
            nextRequestAtMs = maxOf(nextRequestAtMs, System.currentTimeMillis() + delay)
        }
        waiting.incrementAndGet()
        _throttled.value = true
        try {
            sleep(delay + Random.nextLong(BACKOFF_JITTER_MS))
        } finally {
            _throttled.value = waiting.decrementAndGet() > 0
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** `Retry-After` in milliseconds, or 0 when the server did not name a delay in seconds. */
    private fun retryAfterMs(conn: HttpURLConnection): Long =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.times(1_000L)
            ?: 0L

    private fun executeOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String? = null
    ): Attempt {
        return try {
            var currentUrl = url
            var currentMethod = method
            var currentBody: ByteArray? = rawBody
            var currentContentType: String? = contentType
            var redirects = 0
            var lastConn: HttpURLConnection? = null
            var result: Attempt? = null

            while (result == null) {
                val conn = openConnection(currentUrl, currentMethod, headers, currentBody, currentContentType)
                lastConn = conn
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    conn.disconnect()
                    throw e
                }

                // Manual redirect handling
                if (code in 301..308 && code != 304 && redirects < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                    if (loc != null) {
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        if (code == 303) {
                            currentMethod = "GET"
                            currentBody = null
                            currentContentType = null
                        }
                        redirects++
                        try { conn.inputStream?.close() } catch (_: Exception) {}
                        conn.disconnect()
                        continue
                    }
                }

                val ct = conn.getHeaderField("Content-Type")
                val responseMessage = try { conn.responseMessage } catch (_: Exception) { "" } ?: ""
                val retryAfter = retryAfterMs(conn)
                _responseCode.value = code

                val bytes = try {
                    val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                    stream?.readBytes() ?: ByteArray(0)
                } catch (_: Exception) {
                    ByteArray(0)
                } finally {
                    try { lastConn.inputStream?.close() } catch (_: Exception) {}
                    try { lastConn.errorStream?.close() } catch (_: Exception) {}
                    lastConn.disconnect()
                }

                val isSuccessful = code in 200..299
                val errStr = if (!isSuccessful) {
                    responseMessage.ifEmpty { "Error $code" }
                } else ""

                val errBytes = if (!isSuccessful) bytes else ByteArray(0)
                val respBytes = if (isSuccessful) bytes else ByteArray(0)

                result = Attempt(
                    PlayResponse(
                        responseBytes = respBytes,
                        errorBytes = errBytes,
                        errorString = errStr,
                        isSuccessful = isSuccessful,
                        code = code,
                        type = ct
                    ),
                    retryAfter,
                )
            }
            result
        } catch (e: Exception) {
            Attempt(
                PlayResponse(
                    isSuccessful = false,
                    code = -1,
                    errorString = e.message ?: "Network error",
                    errorBytes = ByteArray(0),
                    responseBytes = ByteArray(0)
                ),
                0L,
            )
        }
    }

    private fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        contentType: String?,
    ): HttpURLConnection {
        val rawConn = URL(urlString).openConnection()
        // Play Store auth uses Google GTS — pin via app-wide STANDARD bundle (contains GTS R1-R4).
        val factory = com.vayunmathur.library.network.NetworkClient.defaultSslSocketFactory
        if (factory != null && rawConn is javax.net.ssl.HttpsURLConnection) {
            rawConn.sslSocketFactory = factory
        }
        val conn = (rawConn as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        // Set method, with reflection fallback for custom verbs
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
        }

        headers.forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }

        // Content-Type handling: explicit param wins, otherwise default protobuf for POST
        val effectiveContentType = when {
            contentType != null -> contentType
            method == "POST" && bodyBytes != null -> "application/x-protobuf"
            else -> null
        }
        if (effectiveContentType != null) {
            conn.setRequestProperty("Content-Type", effectiveContentType)
        }

        if (bodyBytes != null) {
            try {
                conn.setFixedLengthStreamingMode(bodyBytes.size)
            } catch (_: Exception) {
                try { conn.setChunkedStreamingMode(0) } catch (_: Exception) {}
            }
            try {
                conn.outputStream.use { it.write(bodyBytes) }
            } catch (e: Exception) {
                // If output fails, propagate
                throw e
            }
        }

        return conn
    }
}
