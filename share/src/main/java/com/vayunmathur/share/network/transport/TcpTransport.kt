package com.vayunmathur.share.network.transport

import android.util.Log
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareSession
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.File
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
import kotlinx.coroutines.delay
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
 * How long to wait for the peer's user to accept before giving up.
 *
 * Ours, not a recovered constant: GMS's own timeouts are Phenotype-driven. Long enough for
 * someone to look at the phone and tap.
 */
private const val ACCEPT_WAIT_MS = 60_000L

/**
 * Read timeout inside the pump loop.
 *
 * Short so the loop wakes often enough to send keep-alives and re-poll state, not because
 * the peer is expected to be this chatty.
 */
private const val PUMP_READ_TIMEOUT_MS = 2_000

/**
 * How often to emit `KEEP_ALIVE`, matching the interval we advertise in
 * `ConnectionRequestFrame.keep_alive_interval_millis`.
 *
 * Not optional: a peer that is told to expect keep-alives every 5 s tears the connection
 * down when they never arrive, which looks exactly like an unexplained disconnect a dozen
 * seconds into every transfer.
 */
private const val KEEP_ALIVE_EVERY_MS = 5_000L

/**
 * TCP transport pump that wires raw bytes to/from the Rust [ShareSession]
 * per PROTOCOL_CONTRACT.md §3 and §6.
 *
 * Kotlin owns: TCP listen/connect, raw socket I/O.
 * Rust owns: the 4-byte big-endian length prefix, protobuf, the D2D secure channel, and
 * partial-read buffering — Kotlin does NO framing. Everything
 * [ShareSession.drainOutbound] returns is already framed (`frame.rs frame_with_length`);
 * Kotlin writes it verbatim. Inbound bytes go in verbatim via [ShareSession.feedInbound]
 * and Rust's `inbound_buf` reassembles partial frames.
 *
 * Contract loop (per §3):
 *   bytes = tcpSocket.read()          // raw read, blocking
 *   if (bytes != null) feedInbound(handle, bytes)
 *   while ((rec = drainReceived(handle)) != null) receivedStore.append(rec)
 *   while ((out = drainOutbound(handle)) != null) tcpSocket.write(out)  // raw write
 *   poll state via queryState / queryPendingFiles
 *
 * Each peer gets its own [ShareSession] (handle lifetime owns the Rust session). The
 * session's role follows the socket: the side that dialled is the initiator.
 */
class TcpTransport(
    localName: String,
    private val receivedStore: ReceivedFileStore,
    localEndpointInfo: ByteArray = ByteArray(0),
    localEndpointId: String = "",
) {
    private var localName: String = localName
    private var localEndpointInfo: ByteArray = localEndpointInfo
    private var localEndpointId: String = localEndpointId

    /**
     * Set the identity new sessions announce, after the user renames the device.
     *
     * Must be the same identity that is being advertised over mDNS and BLE: the peer
     * matches `CONNECTION_REQUEST` against what it discovered. Sessions already running
     * keep the identity they were created with.
     */
    fun setLocalIdentity(name: String, endpointInfo: ByteArray, endpointId: String) {
        localName = name
        localEndpointInfo = endpointInfo
        localEndpointId = endpointId
    }
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
        // The side that dialled the socket is the Nearby Connections initiator: it sends
        // CONNECTION_REQUEST and drives UKEY2 as the client. The endpoint info and id are
        // the advertised ones, so the peer sees the identity it discovered.
        val session = ShareSession(
            localName = localName,
            localEndpointInfo = localEndpointInfo,
            localEndpointId = localEndpointId,
            isInitiator = !incoming,
        )
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
            socket.soTimeout = PUMP_READ_TIMEOUT_MS
            var lastKeepAlive = System.currentTimeMillis()
            fun keepAliveIfDue() {
                val now = System.currentTimeMillis()
                if (now - lastKeepAlive < KEEP_ALIVE_EVERY_MS) return
                lastKeepAlive = now
                session.sendKeepAlive()
                drainAll(session, output)
            }
            while (isActive && !socket.isClosed) {
                val n: Int = try {
                    input.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    // Periodic keep-alive: drain outbound even on read timeout so
                    // handshake retries / accept responses still flush.
                    keepAliveIfDue()
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
                Log.d(TAG, "IN  ${hexPrefix(inbound)}")
                conn.bytesReceived.value += n
                val rc = session.feedInbound(inbound)
                if (rc < 0) {
                    Log.w(TAG, "feedInbound failed rc=$rc")
                    conn.error.value = session.failureReason ?: "Protocol error ($rc)"
                    conn.state.value = ShareState.Failed
                    break
                }
                drainReceived(conn)
                drainAll(session, output)
                keepAliveIfDue()
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
            // Graceful shutdown: one last drain in each direction.
            drainReceived(conn)
            try {
                drainAll(session, socket.getOutputStream())
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
            receivedStore.closeSession(session.handle)
            conn.updateStateFromSession()
        }
    }

    /**
     * Move every chunk Rust has decrypted into the staging store, publishing a
     * [ReceivedFile] as each payload completes.
     *
     * Must run after every [ShareSession.feedInbound]: Rust drops each chunk as it
     * hands it over, so an undrained chunk is a lost chunk.
     */
    private fun drainReceived(conn: Connection) {
        val session = conn.session
        while (true) {
            val chunk = session.drainReceived() ?: return
            val finished = receivedStore.append(session.handle, chunk) ?: continue
            val announcedMime = conn.pendingFiles.value
                .firstOrNull { it.name == chunk.name }
                ?.mimeType
                ?.takeIf { it.isNotBlank() }
            conn.receivedFiles.value += ReceivedFile(
                name = finished.name,
                sizeBytes = finished.length(),
                mimeType = announcedMime ?: ReceivedFileStore.mimeTypeOf(finished),
                uri = receivedStore.contentUri(finished),
            )
            Log.i(TAG, "received ${finished.name} (${finished.length()} bytes)")
        }
    }

    /** TEMP DIAGNOSTIC: hex of a frame prefix, for comparing our SecureMessage headers to the peer's. */
    private fun hexPrefix(bytes: ByteArray, n: Int = 96): String =
        bytes.take(n).joinToString("") { "%02x".format(it) } +
            if (bytes.size > n) "…(${bytes.size}B)" else "(${bytes.size}B)"

    private fun drainAll(session: ShareSession, out: OutputStream) {
        while (true) {
            val bytes = session.drainOutbound() ?: break
            Log.d(TAG, "OUT ${hexPrefix(bytes)}")
            // Rust already applied the 4-byte big-endian length prefix to each frame and
            // concatenated them; write verbatim per PROTOCOL_CONTRACT.md §6.
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
            if (error.value == null) error.value = session.failureReason ?: "Transfer failed"
            Log.w(TAG, "session ${session.handle} failed: ${error.value}\n${session.trace}")
        }
    }

    suspend fun disconnect(conn: Connection) {
        conn.pumpJob?.cancel()
        try {
            conn.socket.close()
        } catch (_: Exception) {
        }
        receivedStore.closeSession(conn.session.handle)
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
        receivedStore.closeAll()
        _incomingConnections.value = emptyList()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // File streaming (on top of the Rust session's outbound/files API)
    // ------------------------------------------------------------------

    /**
     * Send the given [files] over [conn]'s session.
     *
     * Stages the metadata, announces it with an `INTRODUCTION` (held by Rust until the
     * paired-key exchange finishes), waits for the peer to accept, then streams each
     * file. The payload ids the peer sees come from the introduction, so the bytes match
     * the metadata it showed the user.
     */
    suspend fun sendFiles(conn: Connection, files: List<File>) = withContext(Dispatchers.IO) {
        val session = conn.session
        val staged = files.map {
            PendingFile(name = it.name, sizeBytes = it.length(), mimeType = "")
        }
        if (session.setFilesToSend(staged) < 0 || session.queueIntroduction() < 0) {
            conn.error.value = "Failed to announce files"
            conn.state.value = ShareState.Failed
            return@withContext
        }
        drainAll(session, conn.socket.getOutputStream())
        // Wait for the peer to accept before streaming a single chunk. Rust flips to
        // Transferring when the Sharing ConnectionResponse arrives; a real device hangs up
        // if payload chunks show up for a transfer its user has not accepted yet, and
        // openFile refuses with -2 in that state anyway.
        val deadline = System.currentTimeMillis() + ACCEPT_WAIT_MS
        while (isActive && conn.state.value != ShareState.Transferring) {
            conn.updateStateFromSession()
            if (conn.state.value == ShareState.Failed) return@withContext
            if (System.currentTimeMillis() > deadline) {
                conn.error.value = "peer never accepted the transfer"
                conn.state.value = ShareState.Failed
                return@withContext
            }
            delay(100)
        }
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
     * Answer the peer's `INTRODUCTION`.
     *
     * Received bytes go to app-private staging via [ReceivedFileStore], so there is no
     * destination to choose here; the user picks one later, per file, with Save.
     */
    fun acceptIncoming(conn: Connection, accept: Boolean): Int {
        val rc = conn.session.accept(accept)
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

    /** Files that finished arriving, each staged in app-private storage. */
    val receivedFiles: MutableStateFlow<List<ReceivedFile>> = MutableStateFlow(emptyList())
    val bytesSent: MutableStateFlow<Long> = MutableStateFlow(0L)
    val bytesReceived: MutableStateFlow<Long> = MutableStateFlow(0L)
    val error: MutableStateFlow<String?> = MutableStateFlow(null)
}
