package com.vayunmathur.library.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Thrown when the HTTP upgrade is answered with something other than 101.
 * [statusCode] is 0 when the status line could not be parsed. Callers branch on
 * it the way okhttp's `onFailure(.., response)` used to (403 = logged out, other
 * 4xx = fatal, 5xx = retryable).
 */
class WebSocketHandshakeException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/**
 * Android-only pure Socket/SSLSocket WebSocket (RFC6455).
 *
 * No OkHttp / Ktor dependency.
 * - Handshake: Sec-WebSocket-Key = base64(random 16 bytes), verify SHA1 accept (lenient log)
 * - Frame codec: TEXT(0x1), BINARY(0x2), CLOSE(0x8), PING(0x9), PONG(0xA), masking on send
 * - Exposes WsSession { send(String/ByteArray), incoming Flow<WsFrame>, close() }
 * - Top-level webSocket(url, headers, block) for OfficeSync WS & CableTunnel WS
 * - TLS: uses explicit factory, else NetworkClient.defaultSslSocketFactory, else system default.
 */
class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val responseHeaders: Map<String, List<String>>,
    val capturedHeaders: Map<String, String> = emptyMap(),
) {
    @Volatile private var closed = false

    sealed class WsFrame {
        data class Text(val text: String) : WsFrame()
        data class Binary(val bytes: ByteArray) : WsFrame()
        data class Close(val code: Int = 1000, val reason: String = "") : WsFrame()
        object Ping : WsFrame()
        object Pong : WsFrame()
    }

    /** Blocking read loop exposed as Flow. Cancel coroutine to stop. Auto-replies PING with PONG. */
    fun incomingFlow(): Flow<WsFrame> = flow {
        while (true) {
            val frame = try {
                readFrame()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (closed) break
                Log.d(TAG, "ws read error ${e.message}")
                break
            }
            when (frame) {
                is WsFrame.Ping -> {
                    try {
                        sendPong()
                    } catch (_: Exception) { }
                }
                is WsFrame.Close -> {
                    closed = true
                    emit(frame)
                    break
                }
                else -> emit(frame)
            }
        }
    }

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        val payload = text.toByteArray(Charsets.UTF_8)
        writeFrame(opcode = 0x1, payload = payload, mask = true)
    }

    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        writeFrame(opcode = 0x2, payload = bytes, mask = true)
    }

    /**
     * Sends a PING frame. okhttp did this on a timer via `pingInterval`; callers
     * that need keepalive (Signal, Meta MQTT) run their own loop over this.
     */
    suspend fun ping() = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        writeFrame(opcode = 0x9, payload = ByteArray(0), mask = true)
    }

    /** True once a CLOSE frame was seen or [close] was called. */
    val isClosed: Boolean get() = closed

    suspend fun close(code: Int = 1000, reason: String = "") {
        if (closed) return
        closed = true
        try {
            withContext(Dispatchers.IO) {
                val buf = ByteArrayOutputStream()
                buf.write((code shr 8) and 0xFF)
                buf.write(code and 0xFF)
                if (reason.isNotEmpty()) buf.write(reason.toByteArray(Charsets.UTF_8))
                runCatching { writeFrame(opcode = 0x8, payload = buf.toByteArray(), mask = true) }
                runCatching { output.flush() }
                runCatching { socket.close() }
            }
        } catch (_: Exception) { }
    }

    /**
     * Hard, non-blocking teardown: closes the underlying socket immediately WITHOUT
     * the graceful close-frame write. Unlike [close], this can never hang on a
     * half-open socket (a blocking close-frame write into a full send buffer would).
     * Closing the socket unblocks any read or write currently blocked on it, so a
     * keepalive watchdog can use this to break a wedged connection and force a
     * reconnect. Safe to call from any thread; idempotent.
     */
    fun abort() {
        closed = true
        runCatching { socket.close() }
    }

    private fun sendPong() {
        writeFrame(opcode = 0xA, payload = ByteArray(0), mask = true)
        output.flush()
    }

    private fun writeFrame(opcode: Int, payload: ByteArray, mask: Boolean) {
        val maskKey = if (mask) {
            val k = ByteArray(4)
            SecureRandom().nextBytes(k)
            k
        } else null

        val out = ByteArrayOutputStream()
        out.write((0x80 or opcode) and 0xFF)

        val len = payload.size
        var second = if (mask) 0x80 else 0x00
        when {
            len < 126 -> {
                second = second or len
                out.write(second)
            }
            len <= 0xFFFF -> {
                second = second or 126
                out.write(second)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                second = second or 127
                out.write(second)
                val bb = ByteBuffer.allocate(8).putLong(len.toLong())
                out.write(bb.array())
            }
        }

        if (maskKey != null) out.write(maskKey)

        if (maskKey != null) {
            for (i in payload.indices) {
                out.write((payload[i].toInt() xor maskKey[i % 4].toInt()) and 0xFF)
            }
        } else {
            out.write(payload)
        }

        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    private fun readFrame(): WsFrame {
        val b1 = input.read()
        if (b1 == -1) throw EOFException("ws closed")
        val b2 = input.read()
        if (b2 == -1) throw EOFException("ws closed")

        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var payloadLen = (b2 and 0x7F).toLong()

        if (payloadLen == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi == -1 || lo == -1) throw EOFException("ws len EOF")
            payloadLen = ((hi.toLong() shl 8) or lo.toLong())
        } else if (payloadLen == 127L) {
            val buf = ByteArray(8)
            var off = 0
            while (off < 8) {
                val r = input.read(buf, off, 8 - off)
                if (r == -1) throw EOFException("ws len64 EOF")
                off += r
            }
            payloadLen = ByteBuffer.wrap(buf).long
        }

        val maskKey = if (masked) {
            val k = ByteArray(4)
            var off = 0
            while (off < 4) {
                val r = input.read(k, off, 4 - off)
                if (r == -1) throw EOFException("ws mask EOF")
                off += r
            }
            k
        } else null

        if (payloadLen > Int.MAX_VALUE) throw IOException("ws frame too large $payloadLen")

        val payload = ByteArray(payloadLen.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n == -1) throw EOFException("ws truncated frame")
            read += n
        }

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return when (opcode) {
            0x1 -> WsFrame.Text(payload.toString(Charsets.UTF_8))
            0x2 -> WsFrame.Binary(payload)
            0x8 -> {
                val code = if (payload.size >= 2)
                    ((payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF))
                else 1000
                val reason = if (payload.size > 2) payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8) else ""
                WsFrame.Close(code, reason)
            }
            0x9 -> WsFrame.Ping
            0xA -> WsFrame.Pong
            0x0 -> WsFrame.Text(payload.toString(Charsets.UTF_8))
            else -> WsFrame.Ping
        }
    }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Opens a WebSocket to [urlStr] with optional [headers] (e.g. Sec-WebSocket-Protocol).
         * Validates Sec-WebSocket-Accept leniently (logs mismatch) per spec.
         * TLS trust: explicit factory wins, else app-wide default from NetworkClient, else system.
         */
        suspend fun connect(
            urlStr: String,
            headers: Map<String, String> = emptyMap(),
            captureResponseHeaders: List<String> = emptyList(),
            sslSocketFactory: SSLSocketFactory? = null,
            useSystemTrust: Boolean = false,
        ): WebSocketClient = withContext(Dispatchers.IO) {
            val uri = try { URI(urlStr) } catch (_: Exception) { URI(URL(urlStr).toString()) }
            val scheme = uri.scheme?.lowercase() ?: if (urlStr.startsWith("wss")) "wss" else "ws"
            val host = uri.host ?: URL(urlStr).host
            val port = when {
                uri.port != -1 -> uri.port
                scheme == "wss" || scheme == "https" -> 443
                scheme == "ws" || scheme == "http" -> 80
                else -> 80
            }
            val path = buildString {
                val rp = uri.rawPath
                append(if (rp.isNullOrBlank()) "/" else rp)
                uri.rawQuery?.let { if (it.isNotBlank()) append("?").append(it) }
            }

            val sock: Socket = if (scheme == "wss" || scheme == "https") {
                val factory: javax.net.SocketFactory = if (useSystemTrust) SSLSocketFactory.getDefault() else sslSocketFactory ?: NetworkClient.defaultSslSocketFactory ?: SSLSocketFactory.getDefault()
                val s = factory.createSocket(host, port) as Socket
                if (s is SSLSocket) {
                    try { s.startHandshake() } catch (_: Exception) { }
                }
                s
            } else {
                Socket(host, port)
            }
            sock.soTimeout = 0
            sock.tcpNoDelay = true

            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val expectedAccept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                Base64.NO_WRAP
            )

            val out = sock.getOutputStream()
            val writer = out.bufferedWriter(Charsets.US_ASCII)

            val hostHeader = when {
                (scheme == "wss" && port != 443) || (scheme == "ws" && port != 80) -> "$host:$port"
                else -> host
            }

            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: $hostHeader\r\n")
            writer.write("Upgrade: websocket\r\n")
            writer.write("Connection: Upgrade\r\n")
            writer.write("Sec-WebSocket-Key: $key\r\n")
            writer.write("Sec-WebSocket-Version: 13\r\n")
            headers.forEach { (k, v) -> writer.write("$k: $v\r\n") }
            writer.write("\r\n")
            writer.flush()

            val rawIn = sock.getInputStream()
            val responseLines = mutableListOf<String>()
            val lineBuf = ByteArrayOutputStream()
            while (true) {
                val r = rawIn.read()
                if (r == -1) throw IOException("ws handshake EOF")
                lineBuf.write(r)
                if (r == '\n'.code) {
                    val line = lineBuf.toString(Charsets.US_ASCII.name()).trim()
                    lineBuf.reset()
                    if (line.isEmpty()) break
                    responseLines.add(line)
                }
                if (lineBuf.size() > 8192) throw IOException("ws handshake header too long")
            }

            if (responseLines.isEmpty()) throw IOException("ws empty handshake response")
            val statusLine = responseLines.first()
            if (!statusLine.contains("101")) {
                runCatching { sock.close() }
                throw WebSocketHandshakeException(
                    statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0,
                    message = "ws handshake failed: $statusLine; ${responseLines.take(10)}",
                )
            }

            val respHeaders = mutableMapOf<String, MutableList<String>>()
            val respHeadersLower = mutableMapOf<String, String>()
            for (i in 1 until responseLines.size) {
                val line = responseLines[i]
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    respHeaders.getOrPut(k) { mutableListOf() }.add(v)
                    respHeadersLower[k.lowercase()] = v
                }
            }

            val accept = respHeadersLower["sec-websocket-accept"]
            if (accept == null || accept != expectedAccept) {
                Log.w(TAG, "ws accept mismatch expected=$expectedAccept got=$accept (continuing)")
            }

            val captured = mutableMapOf<String, String>()
            for (h in captureResponseHeaders) {
                respHeadersLower[h.lowercase()]?.let { captured[h] = it }
                respHeaders[h]?.firstOrNull()?.let { captured[h] = it }
            }

            WebSocketClient(sock, rawIn, out, respHeaders, captured)
        }
    }
}

/**
 * Convenience session wrapper exposing Flow frames like Ktor's API did.
 * Used by office OfficeSync and passwords CableTunnel.
 */
class WsSession internal constructor(
    private val client: WebSocketClient,
) {
    val incoming: Flow<WebSocketClient.WsFrame> = client.incomingFlow()
    val responseHeaders: Map<String, List<String>> get() = client.responseHeaders
    val capturedHeader: Map<String, String> get() = client.capturedHeaders

    suspend fun send(text: String) = client.send(text)
    suspend fun send(bytes: ByteArray) = client.send(bytes)

    /** Sends a PING frame. Callers that need keepalive run their own timer loop over this. */
    suspend fun ping() = client.ping()
    suspend fun close() = client.close()

    /** Hard, non-blocking teardown (see [WebSocketClient.abort]); unblocks a wedged read/write. */
    fun abort() = client.abort()
}

suspend fun webSocket(
    url: String,
    headers: Map<String, String> = emptyMap(),
    captureResponseHeaders: List<String> = emptyList(),
    sslSocketFactory: SSLSocketFactory? = null,
    useSystemTrust: Boolean = false,
    block: suspend WsSession.() -> Unit,
) {
    val c = WebSocketClient.connect(url, headers, captureResponseHeaders, sslSocketFactory, useSystemTrust)
    try {
        WsSession(c).block()
    } finally {
        try { c.close() } catch (_: Exception) { }
    }
}
