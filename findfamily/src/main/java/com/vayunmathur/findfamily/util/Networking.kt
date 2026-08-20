package com.vayunmathur.findfamily.util
import android.util.Log
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/**
 * FindFamily networking — **WebSocket only**. Every server interaction (register,
 * key lookup, publish, receive) rides one persistent binary socket at
 * `wss://findfamily.cc/api/ws`; there are deliberately no HTTP requests and no
 * HTTP fallback. If the socket drops, [startLive] reconnects (1s→15s backoff) and
 * re-registers on connect.
 *
 * Post-quantum only: there is no classic RSA identity. The PQC identity mirrors
 * Office's `PqcIdentity.loadOrCreate` with a distinct `ff_pqc` DataStore prefix.
 * Bundle format [4B kemLen][kemPubDer][dsaPubDer]; sealed layout
 * [4B encapLen][encap][aesGCM] with KDF SHA256(BE32(1)||Z) — iOS must match to interop.
 */
object Networking {
    private const val TAG = "FF-Networking"
    private const val PLATFORM = "android"

    private val json = Json { ignoreUnknownKeys = true }

    /** Shared end-to-end-encryption identity (key generation/storage/crypto lives in :library:e2ee-p2p). */
    private lateinit var pqcIdentity: PqcIdentity
    @Volatile private var pqcReady = false
    @Volatile private var pqcInitAttempted = false

    var userid = 0L
        private set

    private lateinit var repository: FindFamilyRepository
    private lateinit var dataStoreUtils: DataStoreUtils

    // init() is called from both the app UI (on launch) and the location
    // foreground service (which may start first). Guard it so only one coroutine
    // runs the identity/userid bootstrap at a time, and make it a no-op once done.
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    /** Adapts the app's encrypted DataStore to the e2ee module's storage abstraction. */
    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    suspend fun init(repository: FindFamilyRepository, dataStoreUtils: DataStoreUtils, meName: String) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            Networking.dataStoreUtils = dataStoreUtils
            Networking.repository = repository
            // FindFamily is post-quantum only — there is no classic RSA identity. The PQC identity
            // uses the same library as Office with a distinct `ff_pqc` prefix. The try/catch keeps the
            // app from crashing if libe2ee_pqc.so fails to load (pqcReady stays false and the app
            // cannot share until the native lib is present); there is deliberately no RSA fallback.
            if (!pqcInitAttempted) {
                pqcInitAttempted = true
                try {
                    pqcIdentity = PqcIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils), "ff_pqc")
                    pqcReady = true
                    Log.d(TAG, "PQC identity ready bundleLen=${pqcIdentity.publicBundle.size}")
                } catch (e: Throwable) {
                    Log.w(TAG, "PQC identity unavailable (native lib load failed)", e)
                    pqcReady = false
                }
            }
            // Avoid negative IDs: server keys on ULong; generating only positive IDs keeps both
            // sides consistent and makes Base26 encoding stable.
            if (dataStoreUtils.getLongAwait("userid") == null) {
                dataStoreUtils.setLong("userid", Random.nextLong(from = 1, until = Long.MAX_VALUE), true)
            }
            userid = dataStoreUtils.getLongAwait("userid")!!

            if (repository.getUser(userid) == null) {
                repository.upsertUser(
                    User(
                        meName,
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        userid,
                    )
                )
            }
            initialized = true
        }
    }

    // ----------------------------------------------------------------
    // Binary wire protocol (no JSON, no base64 on the wire):
    //   client→server SUB    : [0x01][u64 userid][optional raw PQC bundle…]  (subscribe + register)
    //   client→server PUB    : [0x02][flags][u64 recipient][raw ciphertext…]
    //   client→server GETKEY : [0x04][u64 target userid]
    //   server→client MSG    : [0x03][flags][raw ciphertext…]
    //   server→client KEYRESP: [0x05][status][u64 target userid][optional raw PQC bundle…]
    // flags: bit0 = kind (0 location, 1 uwb). KEYRESP status: 0 none, 1 classic, 2 pqc.
    // ----------------------------------------------------------------

    private const val WS_URL = "wss://findfamily.cc/api/ws"

    private const val WS_OP_SUB: Byte = 0x01
    private const val WS_OP_PUB: Byte = 0x02
    private const val WS_OP_MSG: Byte = 0x03
    private const val WS_OP_GETKEY_REQ: Byte = 0x04
    private const val WS_OP_GETKEY_RESP: Byte = 0x05
    private const val WS_FLAG_UWB = 0x01

    // Custom UWB tracker crowd-finding opcodes (DEV_BUILD). Mirrored on the server
    // (src/handlers/findfamily.rs). Older servers ignore unknown opcodes, so these
    // are backward-compatible: resolve/report-get simply time out to null/empty.
    private const val WS_OP_TRACKER_REGISTER: Byte = 0x06 // [0x06][u64 tracker_id][u16 secretLen][secret][bundle…]
    private const val WS_OP_RESOLVE_REQ: Byte = 0x07 //      [0x07][16B epochId]
    private const val WS_OP_RESOLVE_RESP: Byte = 0x08 //     [0x08][status][16B epochId][bundle…]
    private const val WS_OP_REPORT_PUT: Byte = 0x09 //       [0x09][16B epochId][ciphertext…]
    private const val WS_OP_REPORT_GET_REQ: Byte = 0x0A //   [0x0A][u16 n]([16B epochId]×n)
    private const val WS_OP_REPORT_GET_RESP: Byte = 0x0B //  [0x0B][u16 count]([u32 len][ct]×count)

    private const val WS_KEY_NONE = 0
    private const val WS_KEY_CLASSIC = 1
    private const val WS_KEY_PQC = 2

    private const val GETKEY_TIMEOUT_MS = 5_000L

    /**
     * Keepalive ping cadence. Mobile NATs/proxies drop idle sockets within a few
     * minutes, and a half-open socket is otherwise only noticed on the next write;
     * a periodic ping keeps the connection alive and surfaces breakage fast so the
     * server's fan-outs land and queued backlog drains promptly on reconnect.
     */
    private const val PING_INTERVAL_MS = 30_000L

    /**
     * If no frame at all (pong, location push, key response — anything) arrives
     * within this window, the socket is treated as half-open and force-closed to
     * trigger a reconnect. This is the crucial half-open detector: a half-open
     * socket still accepts writes into the local TCP send buffer, so `send`/`ping`
     * keep returning success and would otherwise never be noticed as dead. The
     * server replies to our pings with pongs, so a healthy socket always refreshes
     * this well within the window.
     */
    private const val LIVENESS_TIMEOUT_MS = 75_000L

    @Volatile private var wsSession: WsSession? = null
    private var liveJob: Job? = null

    /** In-flight GETKEY requests, keyed by target userid, completed when the KEYRESP arrives. */
    private val pendingKeyRequests = ConcurrentHashMap<Long, CompletableDeferred<KeyResult?>>()

    /** In-flight tracker RESOLVE requests, keyed by the epoch-id hex, completed on RESOLVE_RESP. */
    private val pendingResolves = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** In-flight tracker REPORT_GET requests (FIFO — the owner polls them one at a time). */
    private val pendingReportGets = ConcurrentLinkedQueue<CompletableDeferred<List<ByteArray>>>()

    /** True while the live socket is connected. */
    val liveConnected: Boolean get() = wsSession != null

    private data class KeyResult(val status: Int, val bundle: ByteArray?)

    /** Encodes [v] as 8 big-endian bytes into [dst] starting at [off]. */
    private fun putU64Be(dst: ByteArray, off: Int, v: ULong) {
        for (i in 0 until 8) dst[off + i] = (v shr (56 - i * 8)).toByte()
    }

    /** Reads 8 big-endian bytes at [off] back into the original Long bit pattern. */
    private fun readU64Be(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }

    private fun wsFlags(kind: String): Int = if (kind == "uwb") WS_FLAG_UWB else 0

    /**
     * Start (or no-op if already running) the live relay loop. On connect it subscribes
     * and registers this device's PQC bundle in one frame; [onLocations]/[onUwb] then
     * receive already-decrypted pushes. Reconnects with 1s→15s backoff — there is no
     * HTTP fallback, so a dropped socket is simply re-established.
     */
    // @Synchronized (with stopLive) makes the check-and-set atomic. onStartCommand can
    // fire concurrently (START_STICKY redelivery, ServiceRestartWorker, BootReceiver),
    // each launching startTracking()->startLive() on Dispatchers.IO. Without this, two
    // invocations both pass the isActive check before either assigns liveJob, leaving
    // two live WebSocket connections — one orphaned and unstoppable. Idempotent: a call
    // while a connection is already live is a no-op.
    @Synchronized
    fun startLive(
        scope: CoroutineScope,
        onLocations: suspend (List<LocationValue>) -> Unit,
        onUwb: suspend (List<UwbEnvelope>) -> Unit,
    ) {
        if (liveJob?.isActive == true) return
        stopLive()
        liveJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                // Per-connection scope for the reader. Deliberately independent of the
                // supervisor loop below: a blocking readFrame() — or a stuck delivery
                // callback — that refuses to unwind must never be able to wedge reconnect,
                // so the reader is launched here and abandoned on teardown rather than
                // structurally awaited.
                val connScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                runCatching {
                    webSocket(WS_URL) {
                        wsSession = this
                        // SUB doubles as registration: append our PQC bundle so the server stores
                        // it (no separate register call). Sent every (re)connect, so it self-heals.
                        val bundle = if (pqcReady) pqcIdentity.publicBundle else ByteArray(0)
                        val sub = ByteArray(9 + bundle.size)
                        sub[0] = WS_OP_SUB
                        putU64Be(sub, 1, userid.toULong())
                        bundle.copyInto(sub, 9)
                        send(sub)
                        backoff = 1000L
                        Log.d(TAG, "live WS connected, subscribed+registered as ${userid.toULong()} bundleLen=${bundle.size}")

                        val lastInboundMs = AtomicLong(System.currentTimeMillis())

                        // Reader: decrypts and delivers inbound frames, refreshing liveness on
                        // each one. Runs in the detached connScope and is never joined, so if it
                        // wedges on a half-open socket the supervisor below can still reconnect.
                        // It ends on its own once abort() closes the socket and the read errors.
                        connScope.launch {
                            runCatching {
                                incoming.collect { frame ->
                                    lastInboundMs.set(System.currentTimeMillis())
                                    when (frame) {
                                        is WebSocketClient.WsFrame.Binary ->
                                            dispatchLiveFrame(frame.bytes, onLocations, onUwb)
                                        else -> Unit
                                    }
                                }
                            }
                        }

                        // Supervisor: only ever suspends on delay(), so it can always make
                        // progress to reconnect. Pings are fire-and-forget (a write blocked on a
                        // half-open socket must not stall this loop). If no inbound frame — pong,
                        // push, key response, anything — arrives within the timeout, the socket is
                        // half-open, so break to abort and reconnect.
                        try {
                            while (isActive) {
                                connScope.launch { runCatching { ping() } }
                                delay(PING_INTERVAL_MS)
                                val idle = System.currentTimeMillis() - lastInboundMs.get()
                                if (idle > LIVENESS_TIMEOUT_MS) {
                                    Log.w(TAG, "no inbound for ${idle}ms; socket half-open, reconnecting")
                                    break
                                }
                            }
                        } finally {
                            // Hard, non-blocking close, then abandon the reader (never joined).
                            // abort() sets closed first, so webSocket()'s graceful close() is a
                            // no-op and cannot hang the block's return.
                            runCatching { abort() }
                            connScope.cancel()
                        }
                    }
                }.onFailure { Log.w(TAG, "live WS loop error", it) }
                connScope.cancel()
                wsSession = null
                // Fail any awaiting key lookups so their callers don't hang until timeout.
                pendingKeyRequests.values.forEach { it.complete(null) }
                pendingKeyRequests.clear()
                pendingResolves.values.forEach { it.complete(null) }
                pendingResolves.clear()
                while (true) { (pendingReportGets.poll() ?: break).complete(emptyList()) }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    @Synchronized
    fun stopLive() {
        liveJob?.cancel(); liveJob = null; wsSession = null
        pendingKeyRequests.values.forEach { it.complete(null) }
        pendingKeyRequests.clear()
        pendingResolves.values.forEach { it.complete(null) }
        pendingResolves.clear()
        while (true) { (pendingReportGets.poll() ?: break).complete(emptyList()) }
    }

    /** Parse one server frame and dispatch: MSG → decrypt+deliver; KEYRESP → complete the lookup. */
    private suspend fun dispatchLiveFrame(
        buf: ByteArray,
        onLocations: suspend (List<LocationValue>) -> Unit,
        onUwb: suspend (List<UwbEnvelope>) -> Unit,
    ) {
        val op = buf.firstOrNull() ?: return
        when (op) {
            WS_OP_MSG -> {
                if (buf.size < 2) return
                val isUwb = (buf[1].toInt() and WS_FLAG_UWB) != 0
                val raw = buf.copyOfRange(2, buf.size)
                if (!isUwb) {
                    val decoded = runCatching { decryptLocationPqcBytes(raw) }
                        .onFailure { Log.w(TAG, "live location decrypt fail", it) }.getOrNull() ?: return
                    val (loc, platform) = decoded
                    if (platform != null) runCatching { repository.setPlatform(loc.userid, platform) }
                    runCatching { onLocations(listOf(loc)) }
                } else {
                    val env = runCatching {
                        val plain = pqcIdentity.decrypt(raw)
                        json.decodeFromString<UwbEnvelope>(plain.decodeToString())
                    }.onFailure { Log.w(TAG, "live uwb decrypt fail", it) }.getOrNull() ?: return
                    runCatching { onUwb(listOf(env)) }
                }
            }
            WS_OP_GETKEY_RESP -> {
                if (buf.size < 10) return
                val status = buf[1].toInt()
                val target = readU64Be(buf, 2)
                val bundle = if (buf.size > 10) buf.copyOfRange(10, buf.size) else null
                pendingKeyRequests.remove(target)?.complete(KeyResult(status, bundle))
            }
            WS_OP_RESOLVE_RESP -> {
                // [0x08][status][16B epochId][bundle…]
                if (buf.size < 18) return
                val found = buf[1].toInt() == 1
                val epochHex = buf.copyOfRange(2, 18).toHex()
                val bundle = if (found && buf.size > 18) buf.copyOfRange(18, buf.size) else null
                pendingResolves.remove(epochHex)?.complete(bundle)
            }
            WS_OP_REPORT_GET_RESP -> {
                // [0x0B][u16 count]([u32 len][ct]×count)
                if (buf.size < 3) return
                val count = ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
                val out = ArrayList<ByteArray>(count)
                var off = 3
                var i = 0
                while (i < count && off + 4 <= buf.size) {
                    val len = ((buf[off].toInt() and 0xFF) shl 24) or
                        ((buf[off + 1].toInt() and 0xFF) shl 16) or
                        ((buf[off + 2].toInt() and 0xFF) shl 8) or
                        (buf[off + 3].toInt() and 0xFF)
                    off += 4
                    if (len < 0 || off + len > buf.size) break
                    out.add(buf.copyOfRange(off, off + len)); off += len; i++
                }
                pendingReportGets.poll()?.complete(out)
            }
            else -> Unit
        }
    }

    /** Send an already-encrypted payload to [recipient] over the socket. False if the socket is down. */
    private suspend fun sendLivePublish(recipient: Long, kind: String, raw: ByteArray): Boolean {
        val session = wsSession ?: return false
        return runCatching {
            val frame = ByteArray(10 + raw.size)
            frame[0] = WS_OP_PUB
            frame[1] = wsFlags(kind).toByte()
            putU64Be(frame, 2, recipient.toULong())
            raw.copyInto(frame, 10)
            session.send(frame)
            true
        }.onFailure { Log.w(TAG, "live publish failed", it) }.getOrDefault(false)
    }

    /**
     * Look up a peer's key over the socket (request/response, [GETKEY_TIMEOUT_MS] timeout).
     * Returns null when the socket is down or the reply times out — callers treat that as
     * "unknown" and rely on reconnection rather than any HTTP fallback.
     */
    private suspend fun wsGetKey(userId: Long): KeyResult? {
        val session = wsSession ?: return null
        val deferred = CompletableDeferred<KeyResult?>()
        pendingKeyRequests[userId] = deferred
        return try {
            val req = ByteArray(9)
            req[0] = WS_OP_GETKEY_REQ
            putU64Be(req, 1, userId.toULong())
            session.send(req)
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "wsGetKey failed for ${userId.toULong()}", e)
            null
        } finally {
            pendingKeyRequests.remove(userId)
        }
    }

    // ----------------------------------------------------------------
    // Publish (WebSocket only)
    // ----------------------------------------------------------------

    /**
     * Publish location to a connected user over the socket. Returns false if the peer has no
     * PQC bundle (outdated app — surface [PeerCrypto.NEEDS_UPDATE] via [peerCryptoStatus]) or
     * the socket is down (the next heartbeat retries once reconnected).
     */
    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val bundle = peerPqcBundle(user)
        if (bundle == null) {
            Log.w(TAG, "publishLocation: no PQC bundle for ${user.id.toULong()} (${user.name}); peer must update")
            return false
        }
        return try {
            val ok = sendLivePublish(user.id, "location", sealLocation(location, bundle))
            Log.d(TAG, "publishLocation PQC to ${user.id.toULong()} (${user.name}) ok=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation to ${user.id.toULong()} exception", e)
            false
        }
    }

    /** Publish to an anonymous share link (post-quantum only). */
    suspend fun publishLocation(location: LocationValue, link: TemporaryLink): Boolean {
        return try {
            val bundle = Base64.decode(link.pqcPublicKey)
            val ok = sendLivePublish(link.id, "location", sealLocation(location, bundle))
            Log.d(TAG, "publishLocation PQC to temp link ${link.id} ok=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation temp link ${link.id} failed", e)
            false
        }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel — small handshake envelopes (request / ack /
    // config / cancel), end-to-end encrypted over the same socket. Ranging
    // samples never touch the server.
    // ----------------------------------------------------------------

    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val resolvedUser = recipient ?: repository.getUser(recipientUserId)
        val bundle = if (resolvedUser != null) {
            peerPqcBundle(resolvedUser)
        } else {
            wsGetKey(recipientUserId)?.takeIf { it.status == WS_KEY_PQC }?.bundle
        }
        if (bundle == null) {
            Log.w(TAG, "publishUwbMessage: no PQC bundle for ${recipientUserId.toULong()}; peer must update")
            return false
        }
        return try {
            val sealed = Pqc.encryptTo(bundle, json.encodeToString(envelope).encodeToByteArray())
            val ok = sendLivePublish(recipientUserId, "uwb", sealed)
            Log.d(TAG, "publishUwbMessage PQC to ${recipientUserId.toULong()} ok=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishUwbMessage to ${recipientUserId.toULong()} failed", e)
            false
        }
    }

    // ----------------------------------------------------------------
    // Custom UWB tracker crowd-finding (DEV_BUILD). All methods no-op / return
    // empty when the socket is down or the server is an older build that doesn't
    // implement these opcodes (resolve/report-get time out). See TrackerProtocol
    // and the mirror implementation in the server's findfamily.rs.
    // ----------------------------------------------------------------

    /** Owner: register/refresh a tracker's secret + ML-KEM public bundle on the server. */
    suspend fun registerTracker(trackerUserId: Long, secret: ByteArray, publicBundle: ByteArray): Boolean {
        val session = wsSession ?: return false
        return try {
            val frame = ByteArray(11 + secret.size + publicBundle.size)
            frame[0] = WS_OP_TRACKER_REGISTER
            putU64Be(frame, 1, trackerUserId.toULong())
            frame[9] = (secret.size ushr 8).toByte()
            frame[10] = secret.size.toByte()
            secret.copyInto(frame, 11)
            publicBundle.copyInto(frame, 11 + secret.size)
            session.send(frame)
            true
        } catch (e: Exception) {
            Log.w(TAG, "registerTracker failed", e); false
        }
    }

    /** Finder: resolve a beacon epoch-id to the owning tracker's ML-KEM public bundle. */
    suspend fun resolveTrackerBundle(epochId: ByteArray): ByteArray? {
        val session = wsSession ?: return null
        val hex = epochId.toHex()
        val deferred = CompletableDeferred<ByteArray?>()
        pendingResolves[hex] = deferred
        return try {
            val req = ByteArray(1 + epochId.size)
            req[0] = WS_OP_RESOLVE_REQ
            epochId.copyInto(req, 1)
            session.send(req)
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "resolveTrackerBundle failed", e); null
        } finally {
            pendingResolves.remove(hex)
        }
    }

    /** Finder: upload a sealed crowd report keyed by the beacon epoch-id. */
    suspend fun uploadTrackerReport(epochId: ByteArray, ciphertext: ByteArray): Boolean {
        val session = wsSession ?: return false
        return try {
            val frame = ByteArray(1 + epochId.size + ciphertext.size)
            frame[0] = WS_OP_REPORT_PUT
            epochId.copyInto(frame, 1)
            ciphertext.copyInto(frame, 1 + epochId.size)
            session.send(frame)
            true
        } catch (e: Exception) {
            Log.w(TAG, "uploadTrackerReport failed", e); false
        }
    }

    /** Owner: fetch (and drain) sealed reports for a batch of recent epoch-ids. */
    suspend fun fetchTrackerReports(epochIds: List<ByteArray>): List<ByteArray> {
        val session = wsSession ?: return emptyList()
        if (epochIds.isEmpty()) return emptyList()
        val deferred = CompletableDeferred<List<ByteArray>>()
        pendingReportGets.add(deferred)
        return try {
            val n = epochIds.size.coerceAtMost(0xFFFF)
            val frame = ByteArray(3 + n * 16)
            frame[0] = WS_OP_REPORT_GET_REQ
            frame[1] = (n ushr 8).toByte()
            frame[2] = n.toByte()
            var off = 3
            for (i in 0 until n) {
                epochIds[i].copyInto(frame, off, 0, 16.coerceAtMost(epochIds[i].size))
                off += 16
            }
            session.send(frame)
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchTrackerReports failed", e); emptyList()
        } finally {
            pendingReportGets.remove(deferred)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ----------------------------------------------------------------
    // Encryption helpers (post-quantum only)
    // ----------------------------------------------------------------

    private fun sealLocation(location: LocationValue, bundle: ByteArray): ByteArray {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        // PQC hybrid: ML-KEM encapsulate → AES-256-GCM, layout [4B encapLen][encap][aes].
        return Pqc.encryptTo(bundle, str.encodeToByteArray())
    }

    private fun decryptLocationPqcBytes(raw: ByteArray): Pair<LocationValue, String?> {
        val plainBytes = pqcIdentity.decrypt(raw)
        val compat = json.decodeFromString<LocationValueCompatible>(plainBytes.decodeToString())
        return compat.toLocationValue() to compat.senderPlatform
    }

    /**
     * Generates a fresh share-link key. The link is ML-KEM only and derived from a
     * 32-byte seed, which is the whole secret handed to the recipient in the URL
     * fragment — short enough to fit in an SMS. The private key is never stored:
     * the sender only ever encrypts, and the recipient re-derives it from the seed.
     */
    fun generatePqcLinkKey(): PqcLinkKey {
        val key = Pqc.generateLinkKey()
        return PqcLinkKey(
            seedB64Url = Base64.UrlSafe.encode(key.seed).trimEnd('='),
            publicBundleB64 = Base64.encode(key.publicBundle)
        )
    }

    data class PqcLinkKey(val seedB64Url: String, val publicBundleB64: String)

    /**
     * Generates a full PQC identity (ML-KEM + ML-DSA) for a device we own but that cannot
     * run keygen itself — currently only UWB trackers, whose private bundle we hold on
     * their behalf. Returns (publicBundleBase64, privateBundleBase64) where the private
     * bundle is [4B kemPrivLen][kemPrivDer][dsaPrivDer] — DERs, BC-compatible, same KDF
     * as Office. Share links use [generatePqcLinkKey] instead.
     */
    fun generatePqcIdentityKeyPair(): PqcIdentityKeyPair {
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val pubBundle = Pqc.bundle(kemPub, dsaPub)
        val privBundle = buildPrivBundle(kemPriv, dsaPriv)
        return PqcIdentityKeyPair(
            publicBundleB64 = Base64.encode(pubBundle),
            privateBundleB64 = Base64.encode(privBundle)
        )
    }

    data class PqcIdentityKeyPair(val publicBundleB64: String, val privateBundleB64: String)

    /** Private bundle layout: [4B kemPrivLen BE][kemPriv][dsaPriv] — mirrors public bundle. */
    private fun buildPrivBundle(kemPriv: ByteArray, dsaPriv: ByteArray): ByteArray {
        val out = ByteArray(4 + kemPriv.size + dsaPriv.size)
        out[0] = (kemPriv.size ushr 24).toByte()
        out[1] = (kemPriv.size ushr 16).toByte()
        out[2] = (kemPriv.size ushr 8).toByte()
        out[3] = kemPriv.size.toByte()
        kemPriv.copyInto(out, 4)
        dsaPriv.copyInto(out, 4 + kemPriv.size)
        return out
    }

    /**
     * Computes the quantum-safe verification "security code" for a connection: a fingerprint of
     * *both* this device's and [user]'s PQC bundles. Identical on both peers; comparing them
     * confirms the channel isn't intercepted. Null if PQC is unavailable or the peer has no bundle.
     */
    suspend fun securityCode(user: User): String? {
        if (!pqcReady) return null
        val theirBundle = peerPqcBundle(user) ?: return null
        return runCatching { Pqc.securityCode(pqcIdentity.publicBundle, theirBundle) }.getOrNull()
    }

    /** PQC safety number from a cached peer bundle (no lookup). */
    fun securityCodePqc(user: User): String? {
        val theirBundle = user.pqcEncryptionKey?.let { Base64.decode(it) } ?: return null
        if (!pqcReady) return null
        return runCatching { Pqc.securityCode(pqcIdentity.publicBundle, theirBundle) }.getOrNull()
    }

    /**
     * The peer's PQC public bundle — from cached `User.pqcEncryptionKey` or looked up over the
     * socket. If looked up, caches it. Non-null means the peer supports post-quantum sharing.
     */
    private suspend fun peerPqcBundle(user: User): ByteArray? {
        if (!pqcReady) return null
        user.pqcEncryptionKey?.let { return Base64.decode(it) }
        val res = wsGetKey(user.id) ?: return null
        if (res.status == WS_KEY_PQC && res.bundle != null) {
            runCatching { repository.setPqcEncryptionKey(user.id, Base64.encode(res.bundle)) }
            return res.bundle
        }
        return null
    }

    /** The post-quantum capability of a peer, used to gate connecting/sharing. */
    enum class PeerCrypto {
        /** Peer has a PQC bundle registered — sharing works. */
        PQC,

        /** Peer has only a classic (RSA) key — they are on an outdated app and must update. */
        NEEDS_UPDATE,

        /** Peer is not registered, or the lookup couldn't complete (socket down/timeout). */
        UNKNOWN,
    }

    /**
     * Checks a peer's post-quantum capability "when connecting", over the socket. PQC bundle →
     * [PeerCrypto.PQC]; only a classic key → [PeerCrypto.NEEDS_UPDATE] (show the update dialog);
     * neither, or the socket is down/times out → [PeerCrypto.UNKNOWN].
     */
    suspend fun peerCryptoStatus(userId: Long): PeerCrypto {
        val res = wsGetKey(userId) ?: return PeerCrypto.UNKNOWN
        return when (res.status) {
            WS_KEY_PQC -> PeerCrypto.PQC
            WS_KEY_CLASSIC -> PeerCrypto.NEEDS_UPDATE
            else -> PeerCrypto.UNKNOWN
        }
    }
}
