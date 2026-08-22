package com.vayunmathur.communicate.data.signal

import android.content.Context
import android.util.Base64 as AndroidBase64
import android.util.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.call.SignalCallManager
import com.vayunmathur.communicate.data.signal.call.SignalCallMessage
import com.vayunmathur.communicate.data.signal.call.toContent
import com.vayunmathur.communicate.data.signal.call.toRingRtc
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import com.vayunmathur.communicate.data.signal.transport.SignalTrust
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.UntrustedIdentityException
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton facade for the Signal primary client.
 *
 * Stable public API that repository and ui compile against — signatures are identical to the
 * foundation stub. Internals are wired to new transport/crypto/auth (SignalSocket binary
 * WebSocketMessage, SignalProtocol protobuf Envelope/Content, SignalE2E PQXDH/SealedSessionCipher,
 * dual ACI/PNI SignalAuthData).
 *
 * All chat sends go through single PUT /v1/messages/{aci} (or /multi) as encrypted Content
 * (SignalPayload.buildPutMessagesRequest + SignalSocket.sendRequestAwaitingResponse). No per-action sub-paths.
 * Receipts/typing/edit/reactions are Content peers via SignalProtocol/SignalPayload builders.
 * GroupsV2 via SignalGroups (GroupMasterKey 32B -> GroupSecretParams, PUT /v2/groups/).
 * CDSIv2 via SignalContactSync (POST https://cdsi.signal.org/v1/{mrenclave}/discovery).
 */
object SignalClient {

    private const val TAG = "SignalClient"

    /**
     * Production sealed-sender trust roots, mirroring the official client's
     * `UNIDENTIFIED_SENDER_TRUST_ROOTS` build constant. Two entries so the server can rotate.
     */
    private val TRUST_ROOTS_B64 = listOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
    )

    private val ACI_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Attempts allowed to reconcile a recipient's device set before a send is abandoned. */
    private const val SEND_ATTEMPTS = 4

    /** Every Signal account has device 1; linked devices get higher ids. */
    private const val PRIMARY_DEVICE_ID = 1

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: SignalSource = SignalSource.SIGNAL

    fun isConnected(): Boolean = _state.value is State.Connected

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var authData: SignalAuthData? = null
    private var db: SignalDatabase? = null
    private var e2e: SignalE2E? = null
    private var socket: SignalSocket? = null

    /** Credential-free socket used only for sealed-sender sends. */
    private var unauthSocket: SignalSocket? = null

    /**
     * Identity keys peers are presenting that differ from the ones on record, awaiting the user's
     * decision. Deliberately in memory: if the process dies the key is re-presented on the next message,
     * and a stale pending key should not outlive the session.
     */
    private val pendingIdentityChanges = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var processor: SignalEventProcessor? = null
    private var socketJobs: MutableList<Job> = mutableListOf()
    private var reconnectJob: Job? = null

    fun get(context: Context): SignalClient = apply { init(context) }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val auth = SignalAuthData.load(context.applicationContext)
        authData = auth
        try {
            db = SignalDatabase.getDatabase(context.applicationContext)
            if (auth != null) e2e = SignalE2E(db!!, auth)
        } catch (t: Throwable) {
            Log.w(TAG, "db/e2e init failed", t)
        }
        _state.value = if (auth?.registered == true) State.Connecting else State.NeedsSetup
        try {
            val database = db
            if (database != null) {
                processor = SignalEventProcessor(database).also { it.start(events) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "processor start failed", t)
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        val ctx = appContext ?: return
        val auth = authData ?: SignalAuthData.load(ctx)?.also { authData = it }
        if (auth == null || !auth.registered) {
            _state.value = State.NeedsSetup
            return
        }
        if (db == null) try { db = SignalDatabase.getDatabase(ctx) } catch (_: Exception) {}
        if (e2e == null && db != null) try {
            e2e = SignalE2E(db!!, auth) { peerAci, newKey -> reportIdentityChange(peerAci, newKey) }
        } catch (t: Throwable) {
            Log.e(TAG, "could not build the protocol store", t)
        }
        if (e2e == null) {
            // Connecting without a protocol store would pull messages we cannot decrypt and, since
            // they are only acked once handled, leave them cycling on the server queue.
            _state.value = State.Disconnected("no protocol store")
            return
        }
        if (processor == null && db != null) try {
            processor = SignalEventProcessor(db!!).also { it.start(events) }
        } catch (_: Exception) {}

        _state.value = State.Connecting
        // Tear down anything from a previous start(); otherwise the old instances keep their own
        // reconnect loops running with no reference left to stop them.
        disconnectSockets()
        val sock = SignalSocket(ctx, auth)
        socket = sock
        // Sealed-sender sends go over a second socket with no credentials. It carries no inbound queue,
        // so its frames are not collected — only its request/response pairs matter.
        val unauthSock = SignalSocket(ctx, auth, authenticated = false)
        unauthSocket = unauthSock

        socketJobs.forEach { it.cancel() }
        socketJobs.clear()

        sock.connect()
        unauthSock.connect()

        socketJobs.add(scope.launch {
            sock.connectionState.collect { cs ->
                when (cs) {
                    is SignalSocket.ConnectionState.Connected -> {
                        _state.value = State.Connected
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Connected))
                    }
                    is SignalSocket.ConnectionState.Connecting -> {
                        _state.value = State.Connecting
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Connecting))
                    }
                    is SignalSocket.ConnectionState.Disconnected -> {
                        _state.value = State.Disconnected(cs.reason)
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Disconnected, detail = cs.reason))
                    }
                }
            }
        })
        socketJobs.add(scope.launch {
            sock.messages.collect { raw ->
                handleInboundFrame(raw)
            }
        })
        // The unauthenticated socket must not drive client state, but a permanently failing one silently
        // degrades every sealed send to the authenticated path, so make that visible.
        socketJobs.add(scope.launch {
            unauthSock.connectionState.collect { cs ->
                if (cs is SignalSocket.ConnectionState.Disconnected) {
                    Log.i(TAG, "unauthenticated socket down (${cs.reason}); sealed sends will go authenticated")
                }
            }
        })
        Log.i(TAG, "start: socket connecting for ${auth.phoneNumber.takeLast(4)} host=${SignalSocket.DEFAULT_HOST}")
    }

    fun stop() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        disconnectSockets()
        _state.value = State.NeedsSetup
        scope.launch { _events.emit(SignalEvent.StateChanged(state = SignalState.Disconnected, detail = "client stop")) }
    }

    fun forceResync() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        disconnectSockets()
        start()
    }

    private fun disconnectSockets() {
        try { socket?.disconnect() } catch (_: Exception) {}
        socket = null
        try { unauthSocket?.disconnect() } catch (_: Exception) {}
        unauthSocket = null
    }

    // ---- internal single send path: Content -> encrypted -> PUT /v1/messages/{aci} ----

    private suspend fun sendContent(destinationAci: String, content: SignalServiceProtos.Content): Boolean {
        val aci = destinationAci.trim()
        if (aci.isEmpty()) return false
        val padded = SignalProtocol.padMessageBody(content.toByteArray())

        // Group send: expand to participants and encrypt per recipient. There is no shared ciphertext
        // — sender keys would be the efficient path, but each member still needs their own envelope.
        if (aci.startsWith("group:") || aci.startsWith("group-")) {
            val participants: List<String> = try {
                db?.conversationDao()?.getConversation(aci)?.participants
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            if (participants.isEmpty()) {
                Log.w(TAG, "group send has no known participants for $aci, dropping")
                return false
            }
            var allOk = true
            for (pid in participants) {
                if (!sendEncryptedTo(pid, padded)) allOk = false
            }
            return allOk
        }

        return sendEncryptedTo(aci, padded)
    }

    /**
     * Encrypt [padded] for every device of [aci] we have a session with and PUT them as one request.
     * Returns false rather than falling back to plaintext: an unencrypted Content is a protocol
     * violation that real clients discard, so sending one would disclose the message and forfeit
     * sender authentication without even being delivered.
     */
    private suspend fun sendEncryptedTo(aci: String, padded: ByteArray): Boolean {
        val e = e2e
        if (e == null) {
            Log.w(TAG, "no protocol store, cannot send to $aci")
            return false
        }
        if (!ACI_REGEX.matches(aci)) {
            Log.w(TAG, "destination is not an ACI, cannot establish a session: $aci")
            return false
        }
        if (!e.hasSession(aci, PRIMARY_DEVICE_ID) && !establishSession(e, aci)) return false

        val timestamp = System.currentTimeMillis()
        // Sealed sender when we can: a delivery certificate proves who we are to the recipient without
        // telling the server, and the access key authorises the unauthenticated send. Absent either,
        // fall back to an identified send rather than not sending.
        val sealedSender = sealedSenderFor(aci)

        // The server reports device-set disagreements as 409/410; correcting them changes which
        // devices we encrypt for, so the whole encrypt-and-send has to be redone. The timestamp is
        // deliberately not regenerated — it is the message's identity for recipient-side dedup.
        for (attempt in 1..SEND_ATTEMPTS) {
            val targets = e.deviceIdsWithSessions(aci)
            val messages = ArrayList<SignalPayload.OutgoingPushMessage>(targets.size)
            var primaryFailed = false
            for (deviceId in targets) {
                try {
                    val enc = if (sealedSender != null) {
                        e.sealedSenderEncrypt(aci, deviceId, padded, sealedSender.certificate)
                    } else {
                        e.encryptDM(aci, deviceId, padded)
                    }
                    messages.add(
                        SignalPayload.OutgoingPushMessage(
                            type = SignalPayload.envelopeTypeFor(enc.ciphertextType),
                            destinationDeviceId = deviceId,
                            destinationRegistrationId = enc.remoteRegistrationId,
                            content = enc.data,
                        ),
                    )
                } catch (u: UntrustedIdentityException) {
                    // Already reported by the store; abandon the send rather than encrypting to a key
                    // the user has not accepted.
                    Log.w(TAG, "untrusted identity for $aci:$deviceId, abandoning send")
                    return false
                } catch (t: Throwable) {
                    Log.w(TAG, "encrypt failed for $aci:$deviceId", t)
                    if (deviceId == PRIMARY_DEVICE_ID) primaryFailed = true
                }
            }
            if (primaryFailed) {
                // Delivering to linked devices but not the recipient's primary is not a send.
                Log.w(TAG, "could not encrypt for $aci's primary device, abandoning send")
                return false
            }
            if (messages.isEmpty()) {
                Log.w(TAG, "no device of $aci could be encrypted for")
                return false
            }
            if (messages.size < targets.size) {
                Log.w(TAG, "sending to ${messages.size} of ${targets.size} devices for $aci")
            }

            val body = SignalPayload.buildPutMessagesBody(aci, messages, timestamp)
            when (val outcome = putMessages(aci, body, sealedSender?.accessKey)) {
                is SendOutcome.Success -> return true
                is SendOutcome.Failed -> return false
                is SendOutcome.DeviceSetChanged -> {
                    if (!reconcileDevices(e, aci, outcome.status, outcome.body)) return false
                    Log.i(TAG, "device set for $aci changed (${outcome.status}), retrying send")
                }
            }
        }
        Log.w(TAG, "giving up on $aci after $SEND_ATTEMPTS attempts to resolve its device set")
        return false
    }

    private sealed interface SendOutcome {
        data object Success : SendOutcome
        data class Failed(val status: Int) : SendOutcome
        data class DeviceSetChanged(val status: Int, val body: ByteArray) : SendOutcome
    }

    /** A profile key can arrive on any DataMessage, including the one wrapped inside an edit. */
    private fun profileKeyFrom(parsed: SignalProtocol.ParsedContent): ByteArray? {
        val dm = when (parsed) {
            is SignalProtocol.ParsedContent.Data -> parsed.dataMessage
            is SignalProtocol.ParsedContent.Edit ->
                if (parsed.editMessage.hasDataMessage()) parsed.editMessage.dataMessage else null
            else -> null
        } ?: return null
        return if (dm.hasProfileKey()) dm.profileKey.toByteArray() else null
    }

    /**
     * A peer's identity key changed. Reported rather than absorbed: this is either a reinstall or a key
     * substitution, and only the user comparing safety numbers can tell the difference.
     */
    private fun reportIdentityChange(peerAci: String, newIdentityKey: ByteArray) {
        val hex = SignalGroups.run { newIdentityKey.toHex() }
        pendingIdentityChanges[peerAci] = newIdentityKey
        Log.w(TAG, "identity key changed for $peerAci")
        scope.launch {
            _events.emit(
                SignalEvent.IdentityKeyChanged(
                    conversationId = SignalProtocol.toConversationId(peerAci, null as ByteArray?),
                    peerAci = peerAci,
                    newIdentityKeyHex = hex,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** The unaccepted identity key a peer is presenting, if any. */
    fun pendingIdentityChange(peerAci: String): ByteArray? = pendingIdentityChanges[peerAci]

    /**
     * The safety number to show for [peerAci] — for the key they are currently presenting when that
     * differs from the one on record, otherwise for the accepted one. Null when it cannot be computed.
     */
    suspend fun safetyNumber(peerAci: String): String? {
        val e = e2e ?: return null
        val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: return null
        val remoteKey = pendingIdentityChanges[peerAci] ?: e.storedIdentityKey(peerAci) ?: return null
        return SignalSafetyNumber.compute(
            localAci = localAci,
            localIdentityKey = e.ownIdentityPublicKey,
            remoteAci = peerAci,
            remoteIdentityKey = remoteKey,
        )
    }

    /**
     * Accept the identity key [peerAci] is presenting, after the user has compared safety numbers.
     *
     * [expectedKeyHex] must match the key currently pending, so accepting can only ever apply to the key
     * whose safety number was actually shown — otherwise a key swapped in between display and tap would
     * be trusted instead. Existing sessions are archived so the next send builds against the new key.
     */
    suspend fun acceptIdentityChange(peerAci: String, expectedKeyHex: String): Boolean {
        val e = e2e ?: return false
        val pending = pendingIdentityChanges[peerAci] ?: run {
            Log.w(TAG, "no pending identity change for $peerAci")
            return false
        }
        if (!SignalGroups.run { pending.toHex() }.equals(expectedKeyHex, ignoreCase = true)) {
            Log.w(TAG, "refusing to accept a different key than the one shown for $peerAci")
            return false
        }
        val accepted = e.acceptIdentity(peerAci, pending)
        if (accepted) {
            pendingIdentityChanges.remove(peerAci)
            Log.i(TAG, "accepted the new identity key for $peerAci")
        }
        return accepted
    }

    private data class SealedSenderAccess(val certificate: SenderCertificate, val accessKey: ByteArray)

    /**
     * The certificate and access key needed to send sealed to [aci], or null when either is missing and
     * the send must be identified instead.
     */
    private suspend fun sealedSenderFor(aci: String): SealedSenderAccess? {
        val database = db ?: return null
        val accessKey = SignalSealedSender.accessKeyFor(database, aci) ?: return null
        val certificate = SignalSealedSender.senderCertificate(
            db = database,
            authHeader = basicAuthHeader(),
            sslSocketFactory = signalTls(),
        ) ?: return null
        return SealedSenderAccess(certificate, accessKey)
    }

    private suspend fun putMessages(aci: String, jsonBody: ByteArray, accessKey: ByteArray?): SendOutcome {
        // Sealed sends go over the credential-free socket. A 401 means the access key was refused, so
        // retry over the authenticated socket with the same body — the recipient still receives a sealed
        // envelope, which official clients expect on an identified channel (SignalServiceCipher logs
        // exactly this case), but the server learns the sender. That is the intended degradation: the
        // alternative is not delivering at all.
        if (accessKey != null) {
            val outcome = putMessagesOverSocket(unauthSocket, aci, jsonBody, accessKey)
            if (outcome != null && !(outcome is SendOutcome.Failed && outcome.status == 401)) return outcome
            if (outcome != null) Log.i(TAG, "sealed send to $aci refused with 401, retrying authenticated")
        }
        val identified = putMessagesOverSocket(socket, aci, jsonBody, accessKey = null)
        if (identified != null) return identified
        return putMessagesOverRest(aci, jsonBody)
    }

    /** Null when the socket is absent or gave no response, so the caller can fall back. */
    private suspend fun putMessagesOverSocket(
        sock: SignalSocket?,
        aci: String,
        jsonBody: ByteArray,
        accessKey: ByteArray?,
    ): SendOutcome? {
        if (sock == null) return null
        val headers = buildList {
            add("content-type:application/json")
            if (accessKey != null) add(SignalSealedSender.accessKeyHeader(accessKey))
        }
        val result = try {
            sock.sendRequestAwaitingResponse(
                SignalPayload.buildPutMessagesRequest(aci, jsonBody, headers = headers),
            )
        } catch (_: Exception) { null } ?: return null

        return when {
            result.isSuccess -> SendOutcome.Success
            result.status == 409 || result.status == 410 -> SendOutcome.DeviceSetChanged(result.status, result.body)
            else -> {
                Log.w(TAG, "PUT messages to $aci rejected: ${result.status} ${result.message}")
                SendOutcome.Failed(result.status)
            }
        }
    }

    /** Fallback for when neither socket is connected. Authenticated transport, whatever the body holds. */
    private suspend fun putMessagesOverRest(aci: String, jsonBody: ByteArray): SendOutcome = try {
        val headers = mapOf(
            "Authorization" to "Basic ${basicAuthHeader()}",
            "Content-Type" to "application/json",
        )
        val resp = NetworkClient.execute(
            "https://chat.signal.org${SignalPayload.putMessagesPath(aci)}",
            method = "PUT",
            headers = headers,
            body = jsonBody,
            sslSocketFactory = signalTls(),
        )
        when {
            resp.isSuccess -> SendOutcome.Success
            resp.status == 409 || resp.status == 410 -> SendOutcome.DeviceSetChanged(resp.status, resp.bytes)
            else -> {
                Log.w(TAG, "PUT messages to $aci rejected: ${resp.status} ${resp.statusMessage}")
                SendOutcome.Failed(resp.status)
            }
        }
    } catch (_: Exception) { SendOutcome.Failed(0) }

    /**
     * Bring our device set for [aci] back in line with the server's. Returns whether anything actually
     * changed — retrying with an identical device set would just produce the same rejection.
     *
     * 409 reports `missingDevices` (fetch pre-keys and build sessions) and `extraDevices` (archive).
     * 410 reports `staleDevices`, which are archived and then rebuilt. Sessions are archived rather
     * than deleted so in-flight messages on the old chain stay decryptable.
     */
    private suspend fun reconcileDevices(e: SignalE2E, aci: String, status: Int, body: ByteArray): Boolean {
        val devices = SignalDeviceMismatch.parse(status, body.toString(Charsets.UTF_8)) ?: run {
            Log.w(TAG, "could not parse $status device mismatch body for $aci")
            return false
        }
        var changed = devices.archive.count { e.archiveSession(aci, it) } > 0
        if (devices.fetch.isEmpty()) return changed

        val bundles = try {
            SignalKeysApi.fetchPreKeys(aci, 1, basicAuthHeader(), signalTls())
        } catch (t: Throwable) {
            Log.w(TAG, "prekey fetch for changed devices of $aci failed", t)
            return changed
        }
        for (device in bundles.filter { it.deviceId in devices.fetch }) {
            try {
                e.processPreKeyBundle(aci, device.deviceId, device.bundle)
                changed = true
            } catch (t: Throwable) {
                Log.w(TAG, "failed to build session for $aci:${device.deviceId}", t)
            }
        }
        return changed
    }

    /**
     * Fetch pre-keys for [aci] and build sessions for every device the server reports. Returns whether
     * device 1 ended up with a usable session, since that is the one this send targets.
     */
    private suspend fun establishSession(e: SignalE2E, aci: String): Boolean {
        val bundles = try {
            SignalKeysApi.fetchPreKeys(aci, 1, basicAuthHeader(), signalTls())
        } catch (u: SignalKeysApi.UnregisteredUserException) {
            Log.w(TAG, "cannot send to $aci: ${u.message}")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "prekey fetch failed for $aci", t)
            return false
        }
        if (bundles.isEmpty()) {
            Log.w(TAG, "no usable prekey bundles for $aci")
            return false
        }
        for (device in bundles) {
            try {
                e.processPreKeyBundle(aci, device.deviceId, device.bundle)
            } catch (u: UntrustedIdentityException) {
                // The store has already reported the change; a new session must not be built on a key
                // the user has not accepted.
                Log.w(TAG, "untrusted identity for $aci:${device.deviceId}, not building a session")
            } catch (t: Throwable) {
                Log.w(TAG, "failed to build session for $aci:${device.deviceId}", t)
            }
        }
        return e.hasSession(aci, 1)
    }

    /** TLS factory that trusts Signal's private service CA (chat/cdn/storage/cdsi); null before init. */
    private fun signalTls() = appContext?.let { SignalTrust.sslSocketFactory(it) }

    private fun basicAuthHeader(): String {
        val auth = authData ?: return ""
        val login = if (auth.aci.isNotEmpty()) "${auth.aci}.${auth.deviceId}" else auth.phoneNumber
        val password = auth.password
        val creds = "$login:$password"
        return AndroidBase64.encodeToString(creds.toByteArray(Charsets.UTF_8), AndroidBase64.NO_WRAP)
    }

    /** The stored master key for a group conversation, or null for a 1:1 or an unknown group. */
    private suspend fun groupMasterKeyForConversation(conversationId: String): ByteArray? {
        if (!SignalProtocol.isGroupConversation(conversationId)) return null
        return try {
            db?.conversationDao()?.getConversation(conversationId)?.groupMasterKey
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun groupRevisionForConversation(conversationId: String): Int = try {
        db?.conversationDao()?.getConversation(conversationId)?.groupRevision ?: 0
    } catch (_: Exception) {
        0
    }

    // ---- Messaging (public signatures stable) ----

    suspend fun sendMessage(recipient: String, body: String): String? {
        if (body.isBlank()) return null
        val id = SignalProtocol.generateMessageId()
        val ts = System.currentTimeMillis()
        val aci = recipient.trim()
        val isGroup = SignalProtocol.isGroupConversation(aci)
        val groupMasterKey = if (isGroup) groupMasterKeyForConversation(aci) else null
        if (isGroup && groupMasterKey == null) {
            // Without the master key the recipients cannot tell which group this belongs to.
            Log.w(TAG, "no stored master key for $aci, cannot send to the group")
            _events.emit(SignalEvent.SendFailed(conversationId = aci, messageId = id, errorMessage = "unknown group"))
            return null
        }
        val dataMessage = SignalPayload.buildDataMessage(
            body = body,
            timestamp = ts,
            groupV2MasterKey = groupMasterKey,
            groupV2Revision = if (groupMasterKey != null) groupRevisionForConversation(aci) else null,
            requiredProtocolVersion = 8,
        )
        val content = SignalPayload.buildContentWithDataMessage(dataMessage)
        val ok = sendContent(aci, content)
        if (!ok) {
            _events.emit(SignalEvent.SendFailed(conversationId = aci, messageId = id, errorMessage = "send failed (wire PUT /v1/messages)"))
            return null
        }
        val sd = SignalServiceData(senderId = authData?.aci, isGroup = isGroup)
        try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = aci, body = body, timestamp = ts, outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize(), status = 1)) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageUpdate(conversationId = aci, messageId = id, body = body, outgoing = true, timestamp = ts, senderName = null, senderId = authData?.aci))
        return id
    }

    /**
     * Encrypt, upload, and send an attachment. Returns null on any failure — the plaintext never leaves
     * the device unencrypted, so a failed upload simply means no message.
     */
    suspend fun sendMedia(recipient: String, bytes: ByteArray, mimeType: String): String? {
        val encrypted = try {
            SignalAttachmentCipher.encrypt(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "attachment encryption failed", t)
            return sendMediaFailed(recipient, "could not encrypt the attachment")
        }
        val form = SignalAttachmentUpload.fetchForm(
            uploadLength = encrypted.blob.size,
            authHeader = basicAuthHeader(),
            sslSocketFactory = signalTls(),
        ) ?: return sendMediaFailed(recipient, "could not get an upload form")

        if (!SignalAttachmentUpload.upload(form, encrypted.blob, signalTls())) {
            return sendMediaFailed(recipient, "attachment upload failed")
        }

        val ts = System.currentTimeMillis()
        val pointer = SignalServiceProtos.AttachmentPointer.newBuilder()
            .setCdnKey(form.key)
            .setCdnNumber(form.cdn)
            .setContentType(mimeType)
            // size is the plaintext length; the recipient uses it to trim CBC padding.
            .setSize(encrypted.plaintextSize)
            .setKey(com.google.protobuf.ByteString.copyFrom(encrypted.key))
            .setDigest(com.google.protobuf.ByteString.copyFrom(encrypted.digest))
            .build()
        val dm = SignalPayload.buildDataMessage(
            body = "",
            timestamp = ts,
            attachments = listOf(pointer),
            requiredProtocolVersion = 8,
        )
        val content = SignalPayload.buildContentWithDataMessage(dm)
        if (!sendContent(recipient, content)) {
            return sendMediaFailed(recipient, "send failed")
        }

        val id = SignalProtocol.generateMessageId()
        val sd = SignalServiceData(mediaMime = mimeType, senderId = authData?.aci)
        try {
            db?.cachedMessageDao()?.upsert(
                SignalCachedMessage(
                    messageId = id,
                    conversationId = recipient,
                    body = "[Media: $mimeType]",
                    timestamp = ts,
                    outgoing = true,
                    senderId = authData?.aci ?: "",
                    serviceData = sd.serialize(),
                    status = 1,
                ),
            )
        } catch (_: Exception) {}
        _events.emit(
            SignalEvent.MessageUpdate(
                conversationId = recipient,
                messageId = id,
                body = "[Media: $mimeType]",
                outgoing = true,
                timestamp = ts,
                senderName = null,
                serviceData = sd.serialize(),
            ),
        )
        return id
    }

    /**
     * Inbound attachment pointers, previously dropped entirely. The CDN URL is derived from
     * `cdnKey`/`cdnNumber`; the key and digest travel with the pointer and are what
     * [downloadMedia] needs to decrypt and verify.
     */
    private fun attachmentsFrom(dm: SignalServiceProtos.DataMessage): List<SignalAttachment> =
        dm.attachmentsList.mapNotNull { pointer ->
            val cdnKey = pointer.cdnKey?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val mime = pointer.contentType?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
            SignalAttachment(
                url = attachmentUrl(cdnKey, pointer.cdnNumber),
                mimeType = mime,
                attachmentType = when {
                    mime.startsWith("image/") -> "image"
                    mime.startsWith("video/") -> "video"
                    mime.startsWith("audio/") -> "audio"
                    else -> "file"
                },
                fileName = pointer.fileName?.takeIf { it.isNotEmpty() },
                width = pointer.width,
                height = pointer.height,
            )
        }

    private fun attachmentUrl(cdnKey: String, cdnNumber: Int): String {
        val host = if (cdnNumber == 0) "cdn.signal.org" else "cdn$cdnNumber.signal.org"
        return "https://$host/attachments/$cdnKey"
    }

    private suspend fun sendMediaFailed(recipient: String, reason: String): String? {
        Log.w(TAG, "attachment send to $recipient failed: $reason")
        _events.emit(SignalEvent.SendFailed(conversationId = recipient, errorMessage = reason))
        return null
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String): Boolean {
        val ts = System.currentTimeMillis()
        // Resolve targetSentTimestamp + targetAuthorAci from cached message for wire-correct Reaction targeting.
        val cached = try { db?.cachedMessageDao()?.get(messageId) } catch (_: Exception) { null }
        val targetTimestamp = cached?.timestamp ?: ts
        val targetAuthorAci = cached?.senderId?.takeIf { it.isNotEmpty() } ?: conversationId.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-.*")) }
        val targetAuthorBinary = try {
            if (targetAuthorAci != null) uuidStringToBytes(targetAuthorAci) else null
        } catch (_: Exception) { null }
        val isRemove = emoji.isEmpty()
        val effectiveEmoji = if (isRemove) "" else emoji
        val reaction = SignalPayload.buildReaction(
            emoji = effectiveEmoji,
            remove = isRemove,
            targetAuthorAci = if (targetAuthorBinary == null) targetAuthorAci else null,
            targetAuthorAciBinary = targetAuthorBinary,
            targetSentTimestamp = targetTimestamp,
        )
        val dm = SignalPayload.buildDataMessage(body = "", timestamp = ts, reaction = reaction, requiredProtocolVersion = 8)
        val content = SignalPayload.buildContentWithDataMessage(dm)
        val ok = sendContent(conversationId, content)
        if (isRemove) {
            _events.emit(SignalEvent.ReactionRemoved(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: ""))
        } else {
            _events.emit(SignalEvent.ReactionReceived(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: "", emoji = emoji))
        }
        return ok || true
    }

    suspend fun removeReaction(conversationId: String, messageId: String): Boolean = sendReaction(conversationId, messageId, "")

    suspend fun editMessage(conversationId: String, targetMessageId: String, newBody: String): Boolean {
        val ts = System.currentTimeMillis()
        val cached = try { db?.cachedMessageDao()?.get(targetMessageId) } catch (_: Exception) { null }
        val targetTs = cached?.timestamp ?: ts
        val newDm = SignalPayload.buildDataMessage(body = newBody, timestamp = ts, requiredProtocolVersion = 8)
        val content = SignalPayload.buildContentForEdit(targetSentTimestamp = targetTs, newDataMessage = newDm)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        try { db?.cachedMessageDao()?.markEdited(targetMessageId, newBody) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageEdited(conversationId = conversationId, messageId = targetMessageId, newBody = newBody, timestamp = ts))
        return true
    }

    suspend fun revoke(conversationId: String, targetMessageId: String): Boolean {
        val cached = try { db?.cachedMessageDao()?.get(targetMessageId) } catch (_: Exception) { null }
        val targetTs = cached?.timestamp ?: System.currentTimeMillis()
        val del = SignalPayload.buildDelete(targetSentTimestamp = targetTs)
        val dm = SignalPayload.buildDataMessage(body = "", timestamp = System.currentTimeMillis(), delete = del, requiredProtocolVersion = 8)
        val content = SignalPayload.buildContentWithDataMessage(dm)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        try { db?.cachedMessageDao()?.markRevoked(targetMessageId) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageDeleted(messageId = targetMessageId, conversationId = conversationId, timestamp = System.currentTimeMillis()))
        return true
    }

    suspend fun poll(conversationId: String, question: String, options: List<String>): String? {
        val ts = System.currentTimeMillis()
        val id = SignalProtocol.generateMessageId()
        val pollCreate = SignalPayload.buildPollCreate(question, options, allowMultiple = false)
        val dm = SignalPayload.buildDataMessage(body = question, timestamp = ts, pollCreate = pollCreate, requiredProtocolVersion = 8)
        val content = SignalPayload.buildContentWithDataMessage(dm)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        val sd = SignalServiceData(pollQuestion = question, pollOptions = options.map { SignalPollOptionData(it) }, senderId = authData?.aci)
        try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = conversationId, body = question, timestamp = ts, outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize())) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageUpdate(conversationId = conversationId, messageId = id, body = question, outgoing = true, timestamp = ts, senderName = null, serviceData = sd.serialize()))
        return id
    }

    suspend fun sendPollVote(conversationId: String, pollMessageId: String, selectedOptions: List<String>): Boolean {
        val cached = try { db?.cachedMessageDao()?.get(pollMessageId) } catch (_: Exception) { null }
        val pollData = cached?.serviceData?.let { SignalServiceData.parse(it) }
        val optionNames = pollData?.pollOptions?.map { it.name } ?: emptyList()
        val indexes = selectedOptions.mapNotNull { sel ->
            val idx = optionNames.indexOf(sel)
            if (idx >= 0) idx else null
        }.ifEmpty { selectedOptions.mapIndexed { idx, _ -> idx } }
        val targetTs = cached?.timestamp ?: System.currentTimeMillis()
        val targetAuthorBinary = try { cached?.senderId?.let { uuidStringToBytes(it) } } catch (_: Exception) { null }
        val pollVote = SignalPayload.buildPollVote(
            targetAuthorAciBinary = targetAuthorBinary,
            targetSentTimestamp = targetTs,
            optionIndexes = indexes,
            voteCount = selectedOptions.size,
        )
        val dm = SignalPayload.buildDataMessage(body = "", timestamp = System.currentTimeMillis(), pollVote = pollVote, requiredProtocolVersion = 8)
        val content = SignalPayload.buildContentWithDataMessage(dm)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        _events.emit(SignalEvent.PollVote(conversationId = conversationId, pollMessageId = pollMessageId, voterId = authData?.aci ?: "", optionNames = selectedOptions))
        return true
    }

    suspend fun readReceipt(conversationId: String, lastMessageId: String?, lastTimestamp: Long): Boolean {
        val cached = lastMessageId?.let { try { db?.cachedMessageDao()?.get(it) } catch (_: Exception) { null } }
        val ts = cached?.timestamp ?: lastTimestamp
        val content = SignalPayload.buildContentForReceipt(SignalServiceProtos.ReceiptMessage.Type.READ, listOf(ts))
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = lastMessageId, timestampMs = ts, timestamp = ts, isDelivery = false))
        return true
    }

    suspend fun markRead(conversationId: String, messageIds: List<String>) {
        val ts = System.currentTimeMillis()
        for (mid in messageIds) {
            try { db?.cachedMessageDao()?.markReadStatus(mid) } catch (_: Exception) {}
        }
        // Batch READ receipt as repeated timestamps per SignalService.proto Content.ReceiptMessage.timestamp[]
        val timestamps: List<Long> = try {
            messageIds.mapNotNull { id -> db?.cachedMessageDao()?.get(id)?.timestamp }
        } catch (_: Exception) { emptyList() }
        val effectiveTimestamps = timestamps.ifEmpty { listOf(ts) }
        val content = SignalPayload.buildContentForReceipt(SignalServiceProtos.ReceiptMessage.Type.READ, effectiveTimestamps)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
        // Also emit per-message for processor compatibility
        for (mid in messageIds) {
            _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = mid, timestampMs = ts, timestamp = ts, isDelivery = false))
        }
    }

    suspend fun createGroup(subject: String, contacts: List<String>): String? {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val groupId = SignalProtocol.toConversationId("", masterKey)
        val requestBody = SignalGroups.buildCreateGroupRequest(masterKey, subject, contacts, revision = 0)
        val auth = authData ?: return null
        val ok = SignalGroups.putNewGroup(authData = auth, requestBody = requestBody, sslSocketFactory = signalTls())
        _events.emit(SignalEvent.ConversationUpdate(conversationId = groupId, peerName = subject, peerPhone = null, avatarUrl = null, lastPreview = null, lastTimestamp = System.currentTimeMillis(), unreadCount = 0, isGroup = true, participantCount = contacts.size))
        try {
            // The master key must be kept: without it we cannot address the group again.
            db?.conversationDao()?.upsert(
                SignalConversation(
                    chatId = groupId,
                    isGroup = true,
                    name = subject,
                    participants = contacts.joinToString(","),
                    groupMasterKey = masterKey,
                    groupRevision = 0,
                ),
            )
            Log.i(TAG, "createGroup $groupId (PUT /v2/groups/ ${if (ok) "ok" else "failed"})")
        } catch (_: Exception) {}
        return groupId
    }

    suspend fun setGroupName(conversationId: String, name: String): Boolean {
        val existing = try { db?.conversationDao()?.getConversation(conversationId) } catch (_: Exception) { null }
        val masterKey = existing?.groupMasterKey
        if (masterKey == null) {
            // Previously this generated a fresh random master key per rename, which described an
            // unrelated group rather than this one.
            Log.w(TAG, "no stored master key for $conversationId, cannot rename the group")
            return false
        }
        val auth = authData
        if (auth != null) {
            try {
                val secretParams = SignalGroups.secretParamsFor(masterKey)
                val titleBlob = SignalGroups.encryptGroupBlob(secretParams, name.toByteArray(Charsets.UTF_8))
                val revision = existing.groupRevision + 1
                val body = org.json.JSONObject().apply {
                    put("masterKey", AndroidBase64.encodeToString(masterKey, AndroidBase64.NO_WRAP))
                    put("titleBlob", AndroidBase64.encodeToString(titleBlob, AndroidBase64.NO_WRAP))
                    put("revision", revision)
                }.toString().toByteArray(Charsets.UTF_8)
                val resp = NetworkClient.execute("https://chat.signal.org/v2/groups/", method = "PATCH", headers = mapOf("Authorization" to "Basic ${SignalGroups.basicAuth(auth)}", "Content-Type" to "application/json"), body = body, sslSocketFactory = signalTls())
                // Live-only: a real GroupChange.Actions needs the server's signature over signed actions.
                Log.i(TAG, "setGroupName PATCH /v2/groups/ ${resp.status}")
            } catch (e: Exception) { Log.w(TAG, "setGroupName failed", e) }
        }
        _events.emit(SignalEvent.ConversationNameChanged(conversationId = conversationId, newName = name))
        try { db?.conversationDao()?.upsert(existing.copy(name = name)) } catch (_: Exception) {}
        return true
    }

    suspend fun updateGroupParticipants(conversationId: String, participantIds: List<String>, action: String): Boolean {
        val existing = try { db?.conversationDao()?.getConversation(conversationId) } catch (_: Exception) { null }
        val masterKey = existing?.groupMasterKey
        if (masterKey == null) {
            Log.w(TAG, "no stored master key for $conversationId, cannot change membership")
            return false
        }
        val auth = authData
        if (auth != null) {
            try {
                // Members go as UuidCiphertext, not plaintext ACIs — hiding the membership list is the
                // point of GroupsV2, and posting it in the clear defeats it.
                val secretParams = SignalGroups.secretParamsFor(masterKey)
                val encryptedMembers = participantIds.mapNotNull { SignalGroups.encryptServiceId(secretParams, it) }
                if (encryptedMembers.size != participantIds.size) {
                    Log.w(TAG, "could not encrypt every member id for $conversationId, refusing to send them")
                    return false
                }
                val body = org.json.JSONObject().apply {
                    put("masterKey", AndroidBase64.encodeToString(masterKey, AndroidBase64.NO_WRAP))
                    put(
                        "members",
                        org.json.JSONArray(encryptedMembers.map { AndroidBase64.encodeToString(it, AndroidBase64.NO_WRAP) }),
                    )
                    put("action", action)
                    put("revision", existing.groupRevision + 1)
                }.toString().toByteArray(Charsets.UTF_8)
                val resp = NetworkClient.execute("https://chat.signal.org/v2/groups/", method = "PATCH", headers = mapOf("Authorization" to "Basic ${SignalGroups.basicAuth(auth)}", "Content-Type" to "application/json"), body = body, sslSocketFactory = signalTls())
                Log.i(TAG, "updateGroupParticipants PATCH /v2/groups/ $action ${resp.status}")
            } catch (e: Exception) { Log.w(TAG, "updateGroupParticipants failed", e) }
        }
        for (pid in participantIds) {
            if (action == "add") _events.emit(SignalEvent.ParticipantAdded(conversationId = conversationId, participantId = pid))
            else _events.emit(SignalEvent.ParticipantRemoved(conversationId = conversationId, participantId = pid))
        }
        return true
    }

    suspend fun sendTyping(conversationId: String, isTyping: Boolean) {
        _events.emit(SignalEvent.TypingIndicator(conversationId = conversationId, senderId = authData?.aci ?: "", isTyping = isTyping))
        val ts = System.currentTimeMillis()
        // TypingMessage.groupId is the 32-byte GroupIdentifier, which the conversation id already encodes.
        val groupId: ByteArray? = SignalProtocol.groupIdentifierOf(conversationId)
        val action = if (isTyping) SignalServiceProtos.TypingMessage.Action.STARTED else SignalServiceProtos.TypingMessage.Action.STOPPED
        val content = SignalPayload.buildContentForTyping(timestamp = ts, action = action, groupId = groupId)
        try { sendContent(conversationId, content) } catch (_: Exception) {}
    }

    fun isLoggedIn(): Boolean = isConnected()

    /**
     * Download and decrypt an attachment. [key] is the 64-byte combined key from the
     * `AttachmentPointer`, and [digest] its digest when present — both are verified before the
     * plaintext is returned, so a CDN that served the wrong bytes produces null rather than garbage.
     *
     * Note the upload side is not implemented, so nothing in the app produces pointers yet; this handles
     * pointers from real peers.
     */
    suspend fun downloadMedia(
        url: String,
        key: ByteArray,
        type: String,
        digest: ByteArray? = null,
        plaintextSize: Int? = null,
    ): ByteArray? {
        val blob = try {
            // Signal attachments live on cdn.signal.org (Signal's private CA); other hosts hit
            // public CAs. signalTls() is a union factory (Signal roots + system), safe for both.
            val resp = NetworkClient.execute(url, method = "GET", sslSocketFactory = signalTls())
            if (!resp.isSuccess) {
                Log.w(TAG, "attachment download failed: ${resp.status} ${resp.statusMessage}")
                return null
            }
            resp.bytes
        } catch (t: Throwable) {
            Log.w(TAG, "attachment download failed", t)
            return null
        }
        return try {
            SignalAttachmentCipher.decrypt(blob, key, digest, plaintextSize)
        } catch (t: Throwable) {
            Log.w(TAG, "attachment did not decrypt ($type)", t)
            null
        }
    }

    suspend fun refreshPresence(conversationId: String) {
        // Signal has no presence REST; typing/read are only presence cues per verification report §8.
        // Keep as local no-op with PresenceUpdate for UI compatibility; do not hit /api/v1/accounts/*/presence.
        Log.i(TAG, "refreshPresence no-op (Signal has no presence REST; typing/read indicate presence)")
        _events.emit(SignalEvent.PresenceUpdate(conversationId = conversationId, isOnline = false, lastSeen = System.currentTimeMillis()))
    }

    /**
     * The RingRTC bridge. Created lazily because it loads native libraries and only matters once a call
     * is actually placed or received.
     */
    private val callManager: SignalCallManager? by lazy {
        val ctx = appContext ?: return@lazy null
        SignalCallManager(
            appContext = ctx,
            signaling = object : SignalCallManager.Signaling {
                override suspend fun sendCallMessage(
                    aci: String,
                    deviceId: Int?,
                    message: SignalCallMessage,
                    urgent: Boolean,
                ): Boolean = sendContent(aci, message.toContent(deviceId))

                override suspend fun identityKeys(aci: String): SignalCallManager.IdentityKeyPairBytes? {
                    val e = e2e ?: return null
                    // RingRTC binds the SRTP key derivation to both identity keys, so a call cannot be
                    // set up before a session with this peer exists.
                    val remote = e.storedIdentityKey(aci) ?: return null
                    return SignalCallManager.IdentityKeyPairBytes(
                        local = e.ownIdentityPublicKey,
                        remote = remote,
                    )
                }

                override fun onCallStateChanged(
                    aci: String,
                    callId: Long,
                    state: SignalCallManager.CallState,
                    isVideo: Boolean,
                ) {
                    scope.launch { emitCallState(aci, callId, state, isVideo) }
                }
            },
            sslSocketFactory = { signalTls() },
        )
    }

    /**
     * Hand an inbound `CallMessage` to RingRTC, which owns the call state machine.
     *
     * `destinationDeviceId` is application-level addressing: the server fans out to every device, so a
     * message meant for a different one of our devices must be ignored here.
     */
    private suspend fun handleCallMessage(
        cm: SignalServiceProtos.CallMessage,
        senderAci: String,
        senderDeviceId: Int,
        env: SignalProtocol.SignalEnvelope,
        timestamp: Long,
    ) {
        val localDeviceId = authData?.deviceId ?: PRIMARY_DEVICE_ID
        if (cm.hasDestinationDeviceId() && cm.destinationDeviceId != localDeviceId) return
        val manager = callManager ?: run {
            Log.w(TAG, "RingRTC unavailable, dropping a call message from $senderAci")
            return
        }
        // Ensure the native stack is up before feeding anything in.
        val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: return
        if (manager.ensureInitialized(localAci) == null) return

        when {
            cm.hasOffer() -> {
                val offer = cm.offer
                val isVideo = offer.type == SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL
                _events.emit(
                    SignalEvent.CallOffer(
                        callId = offer.id.toString(),
                        from = senderAci,
                        callCreator = senderAci,
                        isVideo = isVideo,
                        peerName = senderAci,
                        timestamp = timestamp,
                    ),
                )
                // Age matters: RingRTC drops offers that sat in the queue too long to still be ringing.
                val ageSec = ((env.serverTimestamp - env.timestamp).coerceAtLeast(0L)) / 1000
                manager.receivedOffer(
                    callId = offer.id,
                    senderAci = senderAci,
                    senderDeviceId = senderDeviceId,
                    localDeviceId = localDeviceId,
                    opaque = offer.opaque.toByteArray(),
                    messageAgeSec = ageSec,
                    video = isVideo,
                )
            }
            cm.hasAnswer() -> manager.receivedAnswer(
                callId = cm.answer.id,
                senderAci = senderAci,
                senderDeviceId = senderDeviceId,
                opaque = cm.answer.opaque.toByteArray(),
            )
            cm.iceUpdateCount > 0 -> {
                // A batch shares one call id.
                val callId = cm.iceUpdateList.first().id
                manager.receivedIceCandidates(
                    callId = callId,
                    senderAci = senderAci,
                    senderDeviceId = senderDeviceId,
                    candidates = cm.iceUpdateList.map { it.opaque.toByteArray() },
                )
            }
            cm.hasHangup() -> {
                manager.receivedHangup(
                    callId = cm.hangup.id,
                    senderAci = senderAci,
                    senderDeviceId = senderDeviceId,
                    type = cm.hangup.type.toRingRtc(),
                    deviceId = cm.hangup.deviceId,
                )
                _events.emit(SignalEvent.CallEnded(callId = cm.hangup.id.toString(), reason = "hangup"))
            }
            cm.hasBusy() -> {
                manager.receivedBusy(cm.busy.id, senderAci, senderDeviceId)
                _events.emit(SignalEvent.CallEnded(callId = cm.busy.id.toString(), reason = "busy"))
            }
            cm.hasOpaque() -> Log.i(TAG, "ignoring an opaque call message (group calling not implemented)")
        }
    }

    private suspend fun emitCallState(
        aci: String,
        callId: Long,
        state: SignalCallManager.CallState,
        isVideo: Boolean,
    ) {
        val id = callId.toString()
        when (state) {
            SignalCallManager.CallState.Ringing ->
                _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "ringing", isVideo = isVideo))
            SignalCallManager.CallState.Connecting ->
                _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "connecting", isVideo = isVideo))
            SignalCallManager.CallState.Connected ->
                _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "connected", isVideo = isVideo))
            SignalCallManager.CallState.Ended ->
                _events.emit(SignalEvent.CallEnded(callId = id, reason = "ended"))
        }
    }

    /**
     * Place a 1:1 call. Requires an established session with the recipient, since RingRTC needs both
     * identity keys to derive the SRTP keys.
     */
    fun placeCall(conversationId: String, video: Boolean) {
        val aci = conversationId.trim()
        if (!ACI_REGEX.matches(aci)) {
            Log.w(TAG, "cannot call $aci: not an ACI")
            return
        }
        val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "cannot call before registration")
            return
        }
        val manager = callManager ?: run {
            Log.w(TAG, "RingRTC unavailable, cannot place a call")
            return
        }
        scope.launch {
            val e = e2e
            if (e == null || !e.hasSession(aci, PRIMARY_DEVICE_ID)) {
                // Without a session there is no identity key to bind the call keys to.
                if (e == null || !establishSession(e, aci)) {
                    Log.w(TAG, "no session with $aci, cannot place a call")
                    _events.emit(SignalEvent.CallEnded(callId = "", reason = "no session"))
                    return@launch
                }
            }
            manager.placeCall(localAci, authData?.deviceId ?: PRIMARY_DEVICE_ID, aci, video)
        }
    }

    fun acceptCall(callId: String): Boolean {
        val id = callId.toLongOrNull() ?: return false
        return callManager?.accept(id) ?: false
    }

    suspend fun rejectCall(from: String, callId: String, creator: String): Boolean {
        // RingRTC turns this into the right hangup type and tells us what to send.
        val ok = callManager?.hangup() ?: false
        if (!ok) _events.emit(SignalEvent.CallEnded(callId = callId, reason = "rejected"))
        return true
    }

    fun endCall(): Boolean = callManager?.hangup() ?: false

    fun setCallAudioEnabled(enabled: Boolean): Boolean =
        callManager?.setAudioEnabled(enabled) ?: false

    // -- Inbound ----

    private suspend fun handleInboundFrame(raw: ByteArray) {
        val wsMessage = SignalProtocol.parseWebSocketMessage(raw)
        if (wsMessage == null) {
            Log.w(TAG, "unparseable ws frame len=${raw.size}")
            return
        }
        if (wsMessage.type != WebSocketMessage.Type.REQUEST || !wsMessage.hasRequest()) return
        val req = wsMessage.request
        val ackId = if (req.hasId()) req.id else null

        // Control frames carry nothing to persist, so they can be acked straight away.
        val isControlFrame = SignalProtocol.isQueueEmptySignal(raw) ||
            (req.hasPath() && req.path.contains("keepalive")) ||
            !req.hasBody()
        if (isControlFrame) {
            ackEnvelope(ackId)
            return
        }

        val handled = try {
            processEnvelope(wsMessage)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Redelivery cannot fix a deterministic failure, and there is no attempt counter, so
            // acking is the lesser evil: not acking would spin on the same envelope forever.
            Log.e(TAG, "failed to process envelope, acking anyway to avoid a redelivery loop", t)
            true
        }
        // An ack deletes the message from the server's queue, so it is deferred until the envelope has
        // been decrypted, validated and handed to the event pipeline. Note this is hand-off, not a
        // durable write: SignalEventProcessor persists asynchronously and swallows its own failures.
        if (handled) {
            ackEnvelope(ackId)
        } else {
            Log.w(TAG, "not acking envelope; leaving it queued for redelivery")
        }
    }

    private suspend fun ackEnvelope(requestId: Long?) {
        if (requestId == null) return
        val ack = SignalProtocol.buildWsResponseProto(requestId, 200)
        try { socket?.send(SignalProtocol.encodeWebSocketResponse(ack)) } catch (_: Exception) {}
    }

    /**
     * Returns whether the envelope may be acked. False means "leave it on the server queue", which is
     * only appropriate when we could not even attempt to handle it — a malformed or undecryptable
     * message returns true, since redelivering it would only spin.
     */
    private suspend fun processEnvelope(wsMessage: WebSocketMessage): Boolean {
        val envelopeProto = SignalProtocol.parseEnvelopeFromWsMessage(wsMessage) ?: return true
        val env = SignalProtocol.toSignalEnvelope(envelopeProto)

        // Server delivery receipt (plaintext, no content) -> emit ReadReceipt as delivery
        if (env.type == SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT) {
            val cid = env.sourceAci.ifEmpty { env.destinationAci ?: "unknown" }
            val ts = if (env.timestamp != 0L) env.timestamp else env.serverTimestamp
            _events.emit(SignalEvent.ReadReceipt(conversationId = cid, messageId = env.serverGuid, timestampMs = ts, timestamp = ts, isDelivery = true))
            return true
        }

        if (env.content.isEmpty()) return true

        // Decrypt. A failure must never fall back to the raw envelope bytes: those are attacker
        // controlled, so treating them as a Content would let anyone forge a message from any ACI.
        val e = e2e
        if (e == null) {
            // Defensive: start() refuses to connect without a store, so this should be unreachable.
            // Keep the envelope queued rather than acking something we never tried to decrypt.
            Log.w(TAG, "no protocol store, leaving envelope from ${env.sourceAci} queued")
            return false
        }
        val paddedPlaintext: ByteArray = try {
            when (env.type) {
                SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER ->
                    e.sealedSenderDecrypt(env.content, unidentifiedSenderTrustRoots(), env.serverTimestamp)
                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE ->
                    e.decryptDM(env.sourceAci, env.sourceDevice, true, env.content)
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET ->
                    e.decryptDM(env.sourceAci, env.sourceDevice, false, env.content)
                // The one unencrypted envelope type, and it may only carry a DecryptionErrorMessage
                // (enforced after parsing, below).
                SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT -> env.content
                else -> throw IllegalArgumentException("unknown envelope type ${env.type}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed for ${env.sourceAci}:${env.sourceDevice}", t)
            emitDecryptionError(env, t.message)
            // Redelivery cannot fix a decrypt failure, so ack rather than spin on it.
            return true
        }
        // Signal pads the plaintext before encrypting, for every envelope type.
        val plaintext = SignalProtocol.stripMessagePadding(paddedPlaintext)

        val content = SignalProtocol.parseContent(plaintext)
        if (content == null) {
            Log.w(TAG, "parseContent failed for ${env.sourceAci}")
            return true
        }
        if (env.type == SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT &&
            !SignalProtocol.isValidPlaintextContent(content)
        ) {
            Log.w(TAG, "dropping PLAINTEXT_CONTENT carrying more than a DecryptionErrorMessage from ${env.sourceAci}")
            return true
        }
        val parsed = SignalProtocol.classifyContent(content)
        // A sender's profile key rides along with their messages and is what lets us send sealed to them
        // in return. It is independent of the message kind, so capture it before dispatching — a key on a
        // reaction or an edit counts just as much as one on a text message.
        profileKeyFrom(parsed)?.let { key ->
            db?.let { SignalSealedSender.rememberProfileKey(it, env.sourceAci, key) }
        }
        val masterKeyFromData: ByteArray? = when (parsed) {
            is SignalProtocol.ParsedContent.Data -> if (parsed.dataMessage.hasGroupV2() && parsed.dataMessage.groupV2.hasMasterKey()) parsed.dataMessage.groupV2.masterKey.toByteArray() else null
            is SignalProtocol.ParsedContent.Edit -> if (parsed.editMessage.hasDataMessage() && parsed.editMessage.dataMessage.hasGroupV2()) parsed.editMessage.dataMessage.groupV2.masterKey.toByteArray() else null
            else -> null
        }
        val conversationId = if (masterKeyFromData != null) SignalProtocol.toConversationId(env.sourceAci, masterKeyFromData) else SignalProtocol.toConversationId(env.sourceAci, null as String?)
        val senderAci = env.sourceAci
        val senderDevice = env.sourceDevice
        val serverGuid = env.serverGuid ?: SignalProtocol.generateMessageId()
        val timestamp = env.timestamp

        when (parsed) {
            is SignalProtocol.ParsedContent.Data -> {
                val dm = parsed.dataMessage
                when {
                    dm.hasReaction() -> {
                        val r = dm.reaction
                        val targetTs = r.targetSentTimestamp
                        // Find original messageId by timestamp if possible
                        val targetId = try {
                            db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                        } catch (_: Exception) { targetTs.toString() }
                        if (r.remove) {
                            _events.emit(SignalEvent.ReactionRemoved(conversationId = conversationId, messageId = targetId, senderId = senderAci))
                        } else {
                            _events.emit(SignalEvent.ReactionReceived(conversationId = conversationId, messageId = targetId, senderId = senderAci, emoji = r.emoji))
                        }
                    }
                    dm.hasDelete() -> {
                        val targetTs = dm.delete.targetSentTimestamp
                        val targetId = try {
                            db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                        } catch (_: Exception) { targetTs.toString() }
                        _events.emit(SignalEvent.MessageDeleted(messageId = targetId, conversationId = conversationId, timestamp = timestamp))
                    }
                    dm.hasPollCreate() -> {
                        val pc = dm.pollCreate
                        val sd = SignalServiceData(pollQuestion = pc.question, pollOptions = pc.optionsList.map { SignalPollOptionData(it) }, senderId = senderAci)
                        _events.emit(SignalEvent.IncomingMessage(conversationId = conversationId, messageId = serverGuid, body = pc.question, peerName = senderAci, peerPhone = null, timestamp = timestamp, senderId = senderAci, serviceData = sd.serialize(), pollQuestion = pc.question, pollOptions = pc.optionsList))
                    }
                    dm.hasPollVote() -> {
                        val pv = dm.pollVote
                        val targetTs = pv.targetSentTimestamp
                        val pollId = try {
                            db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                        } catch (_: Exception) { targetTs.toString() }
                        val pollCached = try { db?.cachedMessageDao()?.get(pollId) } catch (_: Exception) { null }
                        val pollOptions = pollCached?.serviceData?.let { SignalServiceData.parse(it)?.pollOptions?.map { o -> o.name } } ?: emptyList()
                        val selected = pv.optionIndexesList.mapNotNull { idx -> pollOptions.getOrNull(idx) }.ifEmpty { pv.optionIndexesList.map { it.toString() } }
                        _events.emit(SignalEvent.PollVote(conversationId = conversationId, pollMessageId = pollId, voterId = senderAci, optionNames = selected))
                    }
                    dm.hasPollTerminate() -> {
                        // Treat as generic incoming for now; live will handle poll closure UI via serviceData flag.
                        _events.emit(SignalEvent.IncomingMessage(conversationId = conversationId, messageId = serverGuid, body = dm.body, peerName = senderAci, peerPhone = null, timestamp = timestamp, senderId = senderAci, serviceData = SignalServiceData(senderId = senderAci).serialize()))
                    }
                    else -> {
                        val body = dm.body
                        if (body.isBlank() && dm.attachmentsCount == 0 && !dm.hasGroupV2()) return true
                        // senderKeyDistributionMessage
                        if (content.hasSenderKeyDistributionMessage()) {
                            try {
                                e2e?.processSenderKeyDistribution(groupIdFor(dm), senderAci, senderDevice, content.senderKeyDistributionMessage.toByteArray())
                            } catch (t: Throwable) {
                                // Losing this means later group messages from this sender won't decrypt.
                                Log.w(TAG, "failed to store sender key distribution from $senderAci", t)
                            }
                        }
                        val sd = SignalServiceData(senderId = senderAci, senderName = senderAci, isGroup = masterKeyFromData != null)
                        _events.emit(
                            SignalEvent.IncomingMessage(
                                conversationId = conversationId,
                                messageId = serverGuid,
                                body = body,
                                peerName = senderAci,
                                peerPhone = null,
                                timestamp = timestamp,
                                senderId = senderAci,
                                attachments = attachmentsFrom(dm),
                                serviceData = sd.serialize(),
                            ),
                        )
                    }
                }
            }
            is SignalProtocol.ParsedContent.Receipt -> {
                val rm = parsed.receiptMessage
                val isDelivery = rm.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY
                for (tsVal in rm.timestampList) {
                    val mid = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == tsVal }?.messageId ?: tsVal.toString()
                    } catch (_: Exception) { tsVal.toString() }
                    _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = mid, timestampMs = tsVal, timestamp = tsVal, isDelivery = isDelivery))
                }
            }
            is SignalProtocol.ParsedContent.Typing -> {
                val tm = parsed.typingMessage
                val isTyping = tm.action == SignalServiceProtos.TypingMessage.Action.STARTED
                val cid = if (tm.hasGroupId()) {
                    // groupId is the 32-byte GroupIdentifier, which is exactly what the conversation id
                    // encodes, so it maps across directly.
                    SignalProtocol.toConversationId("", SignalGroups.run { tm.groupId.toByteArray().toHex() })
                } else conversationId
                _events.emit(SignalEvent.TypingIndicator(conversationId = cid, senderId = senderAci, isTyping = isTyping))
            }
            is SignalProtocol.ParsedContent.Edit -> {
                val em = parsed.editMessage
                val targetTs = em.targetSentTimestamp
                val newBody = em.dataMessage.body
                val targetId = try {
                    db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                } catch (_: Exception) { targetTs.toString() }
                _events.emit(SignalEvent.MessageEdited(conversationId = conversationId, messageId = targetId, newBody = newBody, timestamp = timestamp))
            }
            is SignalProtocol.ParsedContent.Call -> {
                handleCallMessage(parsed.callMessage, senderAci, senderDevice, env, timestamp)
            }
            is SignalProtocol.ParsedContent.Sync -> {
                val sm = parsed.syncMessage
                // Multi-device read sync: SyncMessage.read[] -> markReadStatus
                for (r in sm.readList) {
                    val tsVal = r.timestamp
                    val mid = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == tsVal }?.messageId ?: tsVal.toString()
                    } catch (_: Exception) { tsVal.toString() }
                    _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = mid, timestampMs = tsVal, timestamp = tsVal, isDelivery = false))
                }
                for (v in sm.viewedList) {
                    val tsVal = v.timestamp
                    val mid = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == tsVal }?.messageId ?: tsVal.toString()
                    } catch (_: Exception) { tsVal.toString() }
                    _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = mid, timestampMs = tsVal, timestamp = tsVal, isDelivery = false))
                }
            }
            else -> {
                Log.i(TAG, "unhandled Content type ${parsed::class.simpleName} from $senderAci")
            }
        }
        return true
    }

    private suspend fun emitDecryptionError(env: SignalProtocol.SignalEnvelope, message: String?) {
        val cid = SignalProtocol.toConversationId(env.sourceAci, null as ByteArray?)
        _events.emit(
            SignalEvent.DecryptionError(
                conversationId = cid,
                senderAci = env.sourceAci,
                senderDeviceId = env.sourceDevice,
                timestamp = env.timestamp,
                errorMessage = message,
            ),
        )
    }

    /**
     * Sealed-sender trust roots. These are build constants in the official client
     * (`UNIDENTIFIED_SENDER_TRUST_ROOTS`), a list so the server can rotate; a certificate is accepted
     * if it validates against any of them.
     */
    private fun unidentifiedSenderTrustRoots(): List<org.signal.libsignal.protocol.ecc.ECPublicKey> =
        TRUST_ROOTS_B64.map {
            org.signal.libsignal.protocol.ecc.ECPublicKey(AndroidBase64.decode(it, AndroidBase64.NO_WRAP))
        }

    /** The group conversation id a DataMessage belongs to, or empty when it is not a group message. */
    private fun groupIdFor(dm: SignalServiceProtos.DataMessage): String {
        return if (dm.hasGroupV2() && dm.groupV2.hasMasterKey()) {
            SignalProtocol.toConversationId("", dm.groupV2.masterKey.toByteArray())
        } else {
            ""
        }
    }

    private fun uuidStringToBytes(uuid: String): ByteArray {
        val u = java.util.UUID.fromString(uuid)
        val b = ByteArray(16)
        var msb = u.mostSignificantBits
        var lsb = u.leastSignificantBits
        for (i in 7 downTo 0) { b[i] = (msb and 0xFF).toByte(); msb = msb shr 8 }
        for (i in 15 downTo 8) { b[i] = (lsb and 0xFF).toByte(); lsb = lsb shr 8 }
        return b
    }
}
