package com.vayunmathur.communicate.data.signal.transport

import android.util.Base64
import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.security.SecureRandom

/**
 * Persistent WebSocket to Signal's chat server for the primary client.
 *
 * Real Signal transport (grounded in C:\Users\Vayun\signal-ref):
 *  - Host/path: wss://grpc.chat.signal.org:443/v1/websocket/  (libsignal/rust/net/src/env.rs:52,924)
 *  - Auth: header `Authorization: Basic base64("{aci}.{deviceId}:{password}")`
 *    (libsignal/rust/net/src/auth.rs:25, chat.rs:206) — NOT ?login= query.
 *  - Framing: binary protobuf WebSocketMessage (rust/net/src/proto/chat_websocket.proto)
 *    with uint64 id, verb/path/body/headers.
 *  - Keepalive: PUT /v1/keepalive WebSocketRequestMessage (SignalWebSocket.sendKeepAlive)
 *  - Close codes: 4401 invalid auth, 4409 connected elsewhere (env.rs:39-40)
 *  - Validate x-signal-timestamp to defeat captive portals (env.rs:57,947)
 *
 * Uses :library:network WebSocketClient (binary frames).
 */
class SignalSocket(
    private val authData: SignalAuthData,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val useTls: Boolean = true,
    private val passwordOverride: String? = null,
) {
    companion object {
        private const val TAG = "SignalSocket"
        const val DEFAULT_HOST = "grpc.chat.signal.org"
        const val DEFAULT_PORT = 443
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L
        const val CLOSE_INVALID_AUTH = 4401
        const val CLOSE_CONNECTED_ELSEWHERE = 4409
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isConnected = false
    private var session: WsSession? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    private fun wsUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        return "$scheme://$host:$port/v1/websocket/"
    }

    private fun authHeaders(): Map<String, String> {
        val login = if (authData.aci.isNotEmpty()) "${authData.aci}.${authData.deviceId}" else authData.phoneNumber
        val password = passwordOverride ?: resolvePassword() ?: ""
        val credentials = "$login:$password"
        val basic = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return mapOf(
            "Authorization" to "Basic $basic",
            "X-Signal-Receive-Stories" to "true",
            "User-Agent" to SignalPayload.userAgent(),
        )
    }

    private fun resolvePassword(): String? {
        return try {
            val fromAuth = authData.password.takeIf { it.isNotEmpty() }
            fromAuth ?: passwordOverride
        } catch (_: Exception) { passwordOverride }
    }

    fun connect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    _connectionState.emit(ConnectionState.Connecting)
                    val url = wsUrl()
                    Log.i(TAG, "connecting $url as ${authData.aci.take(8)}.${authData.deviceId} host=$host")
                    doConnectOnce()
                    attempt = 0
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "connect failed: $reason")
                    try { _connectionState.emit(ConnectionState.Disconnected(reason)) } catch (_: Exception) {}
                    if (reason.contains("4401")) {
                        Log.e(TAG, "4401 invalid auth — stopping reconnect until credentials refreshed (needs live server)")
                        break
                    }
                }
                attempt++
                val backoff = (RECONNECT_BASE_MS * (1 shl minOf(attempt, 6))).coerceAtMost(RECONNECT_MAX_MS)
                delay(backoff)
                Log.i(TAG, "reconnect attempt $attempt in ${backoff}ms")
            }
        }
    }

    private suspend fun doConnectOnce() {
        val url = wsUrl()
        val headers = authHeaders()
        webSocket(url, headers, captureResponseHeaders = listOf("x-signal-timestamp")) {
            session = this
            isConnected = true
            val tsHeader = capturedHeader["x-signal-timestamp"] ?: responseHeaders.entries
                .firstOrNull { it.key.equals("x-signal-timestamp", ignoreCase = true) }?.value?.firstOrNull()
            if (tsHeader == null) {
                Log.w(TAG, "missing x-signal-timestamp — possible captive portal or proxy (needs live server)")
            } else {
                Log.i(TAG, "x-signal-timestamp=$tsHeader")
            }
            _connectionState.emit(ConnectionState.Connected)
            Log.i(TAG, "WebSocket connected to $host")
            startKeepalive()
            try {
                incoming.collect { frame ->
                    when (frame) {
                        is WebSocketClient.WsFrame.Binary -> _messages.emit(frame.bytes)
                        is WebSocketClient.WsFrame.Text -> {
                            Log.w(TAG, "unexpected text frame len=${frame.text.length}")
                            _messages.emit(frame.text.toByteArray(Charsets.UTF_8))
                        }
                        is WebSocketClient.WsFrame.Close -> {
                            Log.i(TAG, "ws close ${frame.code} ${frame.reason}")
                            when (frame.code) {
                                CLOSE_INVALID_AUTH -> throw RuntimeException("ws close 4401 invalid auth")
                                CLOSE_CONNECTED_ELSEWHERE -> throw RuntimeException("ws close 4409 connected elsewhere")
                                else -> throw RuntimeException("ws close ${frame.code}")
                            }
                        }
                        else -> {}
                    }
                }
            } finally {
                isConnected = false
                stopKeepalive()
                session = null
                Log.i(TAG, "WebSocket session ended")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            stopKeepalive()
            try { session?.close() } catch (_: Exception) {}
            session = null
            isConnected = false
            _connectionState.emit(ConnectionState.Disconnected("client disconnect"))
        }
    }

    suspend fun send(data: ByteArray): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(data)
            true
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            false
        }
    }

    suspend fun sendRequest(request: WebSocketRequestMessage): Boolean {
        val msg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(request)
            .build()
        return send(msg.toByteArray())
    }

    suspend fun sendResponse(response: WebSocketResponseMessage): Boolean {
        val msg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()
        return send(msg.toByteArray())
    }

    suspend fun sendText(text: String): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(text.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    val keepaliveRequest = WebSocketRequestMessage.newBuilder()
                        .setVerb("PUT")
                        .setPath("/v1/keepalive")
                        .setId(nextRequestId())
                        .build()
                    val msg = WebSocketMessage.newBuilder()
                        .setType(WebSocketMessage.Type.REQUEST)
                        .setRequest(keepaliveRequest)
                        .build()
                    val ok = session?.let {
                        try { it.send(msg.toByteArray()); true } catch (_: Exception) { false }
                    } ?: false
                    if (!ok) {
                        Log.w(TAG, "keepalive send failed")
                        break
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "keepalive failed")
                    break
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun nextRequestId(): Long = (SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }
}
