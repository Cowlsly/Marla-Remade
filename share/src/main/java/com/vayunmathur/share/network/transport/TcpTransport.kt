package com.vayunmathur.share.network.transport

import android.util.Log
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareSession
import com.vayunmathur.share.domain.protocol.ShareState
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TcpTransport"
private const val SOCKET_TIMEOUT_MS = 30_000
private const val CONNECT_TIMEOUT_MS = 10_000

/**
 * TCP transport pump that wires raw bytes to/from the Rust [ShareSession]
 * per PROTOCOL_CONTRACT.md §3 and §6.
 *
 * Kotlin owns: TCP listen/connect, raw socket I/O.
 * Rust owns: varint length-prefix, protobuf, secure-message, and it buffers
 * partial reads internally — Kotlin does NO framing. All Rust outbound from
 * [ShareSession.drainOutbound] is already varint length-prefixed (frame.rs
 * frame_with_length); Kotlin writes it verbatim. Inbound bytes are fed
 * verbatim via [ShareSession.feedInbound] and Rust's inbound_buf reassembly
 * handles partial frames.
 *
 * Contract loop (per §3):
 *   bytes = tcpSocket.read()          // raw read, blocking
 *   if (bytes != null) feedInbound(handle, bytes)
 *   while ((out = drainOutbound(handle)) != null) tcpSocket.write(out)  // raw write
 *   poll state via queryState / queryPendingFiles
 *
 * Each peer gets its own [ShareSession] (handle lifetime owns the Rust session).
 */
class TcpTransport(
    private val localName: String,
    private val localEndpointInfo: ByteArray = ByteArray(0),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null

    private val _listenPort = MutableStateFlow<Int?>(null)
    val listenPort: StateFlow<Int?> = _listenPort.asStateFlow()

    private val _incomingConnections = MutableStateFlow<List<Connection>>(emptyList())
    val incomingConnections: StateFlow<List<Connection>> = _incomingConnections.asStateFlow()

    /** Active session pumps, keyed by handle for teardown tracking. */
    private val connections = mutableListOf<Connection>()
    private val connectionsLock = Any()

    private fun publishConnections() {
        synchronized(connectionsLock) {
            _incomingConnections.value = connections.toList()
        }
    }

    // ------------------------------------------------------------------
    // Server (Receive)
    // ------------------------------------------------------------------

    /**
     * Open an ephemeral TCP ServerSocket and pump each incoming client through
     * a fresh [ShareSession]. Returns the bound port (for NSD advertisement).
     */
    fun listen(): Int {
        if (serverSocket != null) return _listenPort.value ?: 0
        val server = ServerSocket(0, 10, InetAddress.getByName("0.0.0.0"))
        serverSocket = server
        _listenPort.value = server.localPort
        Log.i(TAG, "listening on port ${server.localPort}")
        listenJob = scope.launch {
            while (isActive) {
                try {
                    val client = withContext(Dispatchers.IO) { server.accept() }
                    client.tcpNoDelay = true
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    Log.i(TAG, "accepted ${client.inetAddress.hostAddress}:${client.port}")
                    launchConnection(client, incoming = true)
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "accept failed", e)
                    else break
                }
            }
        }
        return server.localPort
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _listenPort.value = null
        Log.d(TAG, "stopped listening")
    }

    // ------------------------------------------------------------------
    // Client (Send)
    // ------------------------------------------------------------------

    /**
     * Connect to [host]:[port] and pump a session over the socket.
     * Returns the [Connection] for UI progress binding.
     */
    suspend fun connect(host: String, port: Int): Connection = withContext(Dispatchers.IO) {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = SOCKET_TIMEOUT_MS
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        Log.i(TAG, "connected to $host:$port")
        launchConnection(sock, incoming = false)
    }

    private fun launchConnection(socket: Socket, incoming: Boolean): Connection {
        val session = ShareSession(localName, localEndpointInfo)
        val conn = Connection(
            socket = socket,
            session = session,
            incoming = incoming,
            remoteEndpoint = "${socket.inetAddress.hostAddress}:${socket.port}",
        )
        synchronized(connectionsLock) { connections += conn }
        publishConnections()
        conn.pumpJob = scope.launch {
            pump(conn)
        }
        return conn
    }

    // ------------------------------------------------------------------
    // Per-connection pump — raw stream, no Kotlin framing
    // ------------------------------------------------------------------

    private suspend fun pump(conn: Connection) = withContext(Dispatchers.IO) {
        val session = conn.session
        val socket = conn.socket
        try {
            // Flush any initial outbound (e.g. UKEY2 ClientInit) before the first read.
            drainAll(session, socket.getOutputStream())
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buf = ByteArray(8192)
            while (isActive && !socket.isClosed) {
                val n: Int = try {
                    input.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    // Periodic keep-alive: drain outbound even on read timeout so
                    // handshake retries / accept responses still flush.
                    drainAll(session, output)
                    conn.updateStateFromSession()
                    if (conn.state.value == ShareState.Failed || conn.state.value == ShareState.Completed) break
                    continue
                }
                if (n == -1) {
                    Log.i(TAG, "peer closed cleanly for ${conn.remoteEndpoint}")
                    break
                }
                if (n == 0) continue
                val inbound = buf.copyOf(n)
                conn.bytesReceived.value += n
                val rc = session.feedInbound(inbound)
                if (rc < 0) {
                    Log.w(TAG, "feedInbound failed rc=$rc")
                    conn.error.value = "Protocol error ($rc)"
                    conn.state.value = ShareState.Failed
                    break
                }
                drainAll(session, output)
                conn.updateStateFromSession()
                if (conn.state.value == ShareState.Completed || conn.state.value == ShareState.Failed) break
            }
        } catch (e: Exception) {
            if (isActive) Log.w(TAG, "pump error for ${conn.remoteEndpoint}", e)
            conn.error.value = e.message
            try {
                conn.state.value = ShareState.Failed
            } catch (_: Exception) {
            }
        } finally {
            // Graceful shutdown: one last drain.
            try {
                drainAll(session, socket.getOutputStream())
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
            conn.updateStateFromSession()
        }
    }

    private fun drainAll(session: ShareSession, out: OutputStream) {
        while (true) {
            val bytes = session.drainOutbound() ?: break
            // Rust already varint length-prefixed each frame and concatenated them;
            // write verbatim — Kotlin does no framing per PROTOCOL_CONTRACT.md §6.
            out.write(bytes)
            out.flush()
        }
    }

    private fun Connection.updateStateFromSession() {
        val polled = session.state
        state.value = polled
        // Pending files may become available asynchronously once Introduction decodes.
        if (polled == ShareState.AwaitingAccept || polled == ShareState.Transferring) {
            pendingFiles.value = session.pendingFiles
        }
        if (polled == ShareState.Failed) {
            if (error.value == null) error.value = "Transfer failed"
        }
    }

    suspend fun disconnect(conn: Connection) {
        conn.pumpJob?.cancel()
        try {
            conn.socket.close()
        } catch (_: Exception) {
        }
        try {
            conn.session.destroy()
        } catch (_: Exception) {
        }
        synchronized(connectionsLock) { connections.remove(conn) }
        publishConnections()
    }

    fun release() {
        stopListening()
        synchronized(connectionsLock) {
            connections.forEach { c ->
                try {
                    c.socket.close()
                } catch (_: Exception) {
                }
                try {
                    c.session.destroy()
                } catch (_: Exception) {
                }
                c.pumpJob?.cancel()
            }
            connections.clear()
        }
        _incomingConnections.value = emptyList()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // File streaming (on top of the Rust session's outbound/files API)
    // ------------------------------------------------------------------

    /**
     * Send the given [files] over [conn]'s session. Must only be called when
     * conn is in Handshaking/AwaitingAccept so the Introduction + payload
     * exchange can kick in. The actual wire bytes are handled by the session
     * pump; this helper stages OPEN/WRITE/CLOSE sequence and updates progress.
     */
    suspend fun sendFiles(conn: Connection, files: List<File>) = withContext(Dispatchers.IO) {
        val session = conn.session
        for (file in files) {
            if (!isActive) break
            val rc = session.openFile(file.name, file.length())
            if (rc < 0) {
                conn.error.value = "openFile failed ($rc) for ${file.name}"
                conn.state.value = ShareState.Failed
                break
            }
            file.inputStream().use { input ->
                val chunkBuf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunkBuf)
                    if (n <= 0) break
                    val chunk = if (n == chunkBuf.size) chunkBuf.copyOf() else chunkBuf.copyOf(n)
                    val wrc = session.writeChunk(chunk)
                    if (wrc < 0) {
                        conn.error.value = "writeChunk failed ($wrc)"
                        conn.state.value = ShareState.Failed
                        break
                    }
                    conn.bytesSent.value += n
                    // Flush after each chunk so the pump's next drain picks it up.
                    drainAll(session, conn.socket.getOutputStream())
                    conn.updateStateFromSession()
                    if (conn.state.value == ShareState.Failed) break
                }
            }
            session.closeFile()
            if (conn.state.value == ShareState.Failed) break
        }
    }

    /**
     * Receive files for an incoming connection — called after [ShareSession.accept]
     * has transitioned the session to Transferring. Buffers inbound payload bytes
     * into [destDir].
     */
    fun acceptIncoming(conn: Connection, destDir: File, accept: Boolean): Int {
        val rc = conn.session.accept(accept, destDir.absolutePath)
        if (rc < 0) {
            conn.error.value = "accept failed ($rc)"
            return rc
        }
        conn.updateStateFromSession()
        // Flush the ACCEPT outbound immediately.
        try {
            drainAll(conn.session, conn.socket.getOutputStream())
        } catch (e: Exception) {
            Log.w(TAG, "drain after accept failed", e)
        }
        return rc
    }

    /** Write a chunk from the peer (payload path) into [destDir]/filename. */
    fun writeIncomingChunk(
        conn: Connection,
        destDir: File,
        fileName: String,
        fileSize: Long,
        chunk: ByteArray,
        currentOut: FileOutputStream?,
    ): FileOutputStream? {
        var out = currentOut
        if (out == null) {
            conn.session.openFile(fileName, fileSize)
            destDir.mkdirs()
            val dest = File(destDir, fileName).let { f ->
                // Avoid clobbering.
                if (!f.exists()) f else {
                    var i = 1
                    var cand: File
                    do {
                        val dot = fileName.lastIndexOf('.')
                        val base = if (dot >= 0) fileName.substring(0, dot) else fileName
                        val ext = if (dot >= 0) fileName.substring(dot) else ""
                        cand = File(destDir, "${base}_$i$ext")
                        i++
                    } while (cand.exists())
                    cand
                }
            }
            out = FileOutputStream(dest)
        }
        // Pass through Rust decryptor.
        conn.session.writeChunk(chunk)
        out.write(chunk)
        conn.bytesReceived.value += chunk.size
        return out
    }

    fun closeIncomingFile(conn: Connection, out: FileOutputStream?) {
        try {
            out?.close()
        } catch (_: Exception) {
        }
        conn.session.closeFile()
    }
}

/**
 * A single peer connection + its Rust session pump.
 */
class Connection(
    val socket: Socket,
    val session: ShareSession,
    val incoming: Boolean,
    val remoteEndpoint: String,
) {
    var pumpJob: Job? = null
    val state: MutableStateFlow<ShareState> = MutableStateFlow(ShareState.Handshaking)
    val pendingFiles: MutableStateFlow<List<PendingFile>> = MutableStateFlow(emptyList())
    val bytesSent: MutableStateFlow<Long> = MutableStateFlow(0L)
    val bytesReceived: MutableStateFlow<Long> = MutableStateFlow(0L)
    val error: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Derived PIN/verification code for the transfer — surfaced once Introduction is decoded. */
    val verificationCode: String? get() = null // Rust will expose a PIN when implemented; wire it via nativeQueryPin or pendingFiles extra.
}
