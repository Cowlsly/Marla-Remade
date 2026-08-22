package com.vayunmathur.communicate.data.signal.call

import android.content.Context
import android.util.Log
import com.vayunmathur.communicate.data.call.WebRtcInit
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.Remote
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * Signal 1:1 calling, driven by RingRTC.
 *
 * RingRTC owns the call state machine and the media; this class is the seam to the app. Two directions:
 *  - RingRTC asks us to put signaling on the wire ([Observer.onSendOffer] and friends), which becomes a
 *    `CallMessage` sent through [signaling].
 *  - Inbound `CallMessage`s are fed back in via `received*`.
 *
 * Offers and answers carry **opaque V4 protobuf bytes**, never SDP — modern Signal removed the `sdp`
 * fields entirely, and RingRTC synthesises SDP locally on both ends.
 *
 * [CallManager.messageSent]/[CallManager.messageSendFailure] are not optional: RingRTC serialises
 * signaling one message at a time and will not proceed until one of them is called.
 *
 * Scope is 1:1 audio. Group calls, call links and video capture are not implemented; the group and
 * ad-hoc observer callbacks are refused rather than silently dropped.
 */
class SignalCallManager(
    private val appContext: Context,
    private val signaling: Signaling,
    private val sslSocketFactory: () -> SSLSocketFactory?,
) : CallManager.Observer {

    /** How call signaling reaches the network, and how call state reaches the app. */
    interface Signaling {
        /** Send one `CallMessage` to [aci]. [deviceId] is null when the message should be broadcast. */
        suspend fun sendCallMessage(
            aci: String,
            deviceId: Int?,
            message: SignalCallMessage,
            urgent: Boolean,
        ): Boolean

        /** The identity keys RingRTC binds the SRTP key derivation to. */
        suspend fun identityKeys(aci: String): IdentityKeyPairBytes?

        fun onCallStateChanged(aci: String, callId: Long, state: CallState, isVideo: Boolean)
    }

    data class IdentityKeyPairBytes(val local: ByteArray, val remote: ByteArray)

    enum class CallState { Ringing, Connecting, Connected, Ended }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    private var callManager: CallManager? = null
    private var eglBase: EglBase? = null

    /** Media type per call, so an answer knows whether video was offered. */
    private val callMediaTypes = java.util.concurrent.ConcurrentHashMap<Long, CallManager.CallMediaType>()

    @Synchronized
    fun ensureInitialized(localAci: String): CallManager? {
        callManager?.let { return it }
        return try {
            if (initialized.compareAndSet(false, true)) {
                WebRtcInit.ensureInitialized(appContext)
                CallManager.initialize(appContext, SignalRingRtcLogger(), ringRtcFieldTrials())
            }
            val manager = CallManager.createCallManager(this) ?: return null
            manager.setSelfUuid(UUID.fromString(localAci))
            eglBase = EglBase.create()
            callManager = manager
            manager
        } catch (t: Throwable) {
            // A failed initialize leaves nothing usable; let a later attempt retry.
            initialized.set(false)
            Log.e(TAG, "RingRTC initialization failed", t)
            null
        }
    }

    fun placeCall(localAci: String, localDeviceId: Int, remoteAci: String, video: Boolean): Boolean {
        val manager = ensureInitialized(localAci) ?: return false
        val mediaType = if (video) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL
        return try {
            manager.call(SignalRemote(remoteAci), mediaType, localDeviceId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not place a call to $remoteAci", t)
            false
        }
    }

    fun accept(callId: Long): Boolean = withManager("accept") { it.acceptCall(CallId(callId)) }

    fun hangup(): Boolean = withManager("hangup") { it.hangup() }

    fun setAudioEnabled(enabled: Boolean): Boolean =
        withManager("setAudioEnable") { it.setAudioEnable(enabled) }

    // -- Inbound signaling --

    fun receivedOffer(
        callId: Long,
        senderAci: String,
        senderDeviceId: Int,
        localDeviceId: Int,
        opaque: ByteArray,
        messageAgeSec: Long,
        video: Boolean,
    ) {
        val manager = callManager ?: return
        scope.launch {
            val keys = signaling.identityKeys(senderAci) ?: run {
                Log.w(TAG, "no identity keys for $senderAci, cannot accept the offer")
                return@launch
            }
            val mediaType = if (video) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL
            callMediaTypes[callId] = mediaType
            try {
                manager.receivedOffer(
                    CallId(callId),
                    SignalRemote(senderAci),
                    senderDeviceId,
                    opaque,
                    messageAgeSec,
                    mediaType,
                    localDeviceId,
                    keys.remote,
                    keys.local,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "receivedOffer failed", t)
            }
        }
    }

    fun receivedAnswer(callId: Long, senderAci: String, senderDeviceId: Int, opaque: ByteArray) {
        val manager = callManager ?: return
        scope.launch {
            val keys = signaling.identityKeys(senderAci) ?: return@launch
            try {
                manager.receivedAnswer(
                    CallId(callId),
                    SignalRemote(senderAci),
                    senderDeviceId,
                    opaque,
                    keys.remote,
                    keys.local,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "receivedAnswer failed", t)
            }
        }
    }

    fun receivedIceCandidates(callId: Long, senderAci: String, senderDeviceId: Int, candidates: List<ByteArray>) {
        withManager("receivedIceCandidates") {
            it.receivedIceCandidates(CallId(callId), SignalRemote(senderAci), senderDeviceId, candidates)
        }
    }

    fun receivedHangup(
        callId: Long,
        senderAci: String,
        senderDeviceId: Int,
        type: CallManager.HangupType,
        deviceId: Int,
    ) {
        withManager("receivedHangup") {
            it.receivedHangup(CallId(callId), SignalRemote(senderAci), senderDeviceId, type, deviceId)
        }
    }

    fun receivedBusy(callId: Long, senderAci: String, senderDeviceId: Int) {
        withManager("receivedBusy") {
            it.receivedBusy(CallId(callId), SignalRemote(senderAci), senderDeviceId)
        }
    }

    // -- Observer: call lifecycle --

    override fun onStartCall(
        remote: Remote?,
        callId: CallId?,
        isOutgoing: Boolean?,
        callMediaType: CallManager.CallMediaType?,
    ) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        val id = callId?.longValue() ?: return
        val mediaType = callMediaType ?: CallManager.CallMediaType.AUDIO_CALL
        callMediaTypes[id] = mediaType
        signaling.onCallStateChanged(aci, id, CallState.Ringing, mediaType.isVideo())
        // RingRTC waits for proceed() before touching media.
        proceed(id, mediaType)
    }

    private fun proceed(callId: Long, mediaType: CallManager.CallMediaType) {
        val manager = callManager ?: return
        val egl = eglBase ?: return
        try {
            manager.proceed(
                CallId(callId),
                appContext,
                egl,
                org.signal.ringrtc.AudioConfig(),
                org.signal.ringrtc.VideoConfig(),
                NoVideoSink,
                NoVideoSink,
                NoCameraControl,
                // TURN servers come from GET /v2/calling/relays, which is not wired up; with an empty
                // list only host and STUN-less candidates are available, so calls will fail behind NAT.
                emptyList<PeerConnection.IceServer>(),
                false,
                CallManager.DataMode.NORMAL,
                AUDIO_LEVEL_INTERVAL_MS,
                null,
                mediaType.isVideo(),
                null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "proceed failed for call $callId", t)
        }
    }

    override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        Log.i(TAG, "call with $aci ended: $reason")
        signaling.onCallStateChanged(aci, 0, CallState.Ended, false)
    }

    override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent?) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        val state = when (event) {
            CallManager.CallEvent.LOCAL_CONNECTED, CallManager.CallEvent.REMOTE_CONNECTED -> CallState.Connected
            CallManager.CallEvent.LOCAL_RINGING, CallManager.CallEvent.REMOTE_RINGING -> CallState.Ringing
            else -> return
        }
        signaling.onCallStateChanged(aci, 0, state, false)
    }

    override fun onCallConcluded(remote: Remote?) {
        callMediaTypes.clear()
    }

    override fun onNetworkRouteChanged(remote: Remote?, networkRoute: NetworkRoute?) = Unit

    override fun onAudioLevels(remote: Remote?, capturedLevel: Int, receivedLevel: Int) = Unit

    override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) = Unit

    // -- Observer: outbound signaling --

    override fun onSendOffer(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        opaque: ByteArray,
        callMediaType: CallManager.CallMediaType?,
    ) {
        val id = callId?.longValue() ?: return
        // Offers are urgent so the callee's device wakes and rings.
        dispatch(
            id,
            remote,
            remoteDeviceId,
            broadcast,
            urgent = true,
            message = SignalCallMessage.Offer(
                callId = id,
                opaque = opaque,
                video = (callMediaType ?: CallManager.CallMediaType.AUDIO_CALL).isVideo(),
            ),
        )
    }

    override fun onSendAnswer(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        opaque: ByteArray,
    ) {
        val id = callId?.longValue() ?: return
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Answer(id, opaque))
    }

    override fun onSendIceCandidates(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        iceCandidates: List<ByteArray>?,
    ) {
        val id = callId?.longValue() ?: return
        val candidates = iceCandidates ?: emptyList()
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Ice(id, candidates))
    }

    override fun onSendHangup(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        hangupType: CallManager.HangupType?,
        deviceId: Int?,
    ) {
        val id = callId?.longValue() ?: return
        dispatch(
            id,
            remote,
            remoteDeviceId,
            broadcast,
            urgent = true,
            message = SignalCallMessage.Hangup(
                callId = id,
                type = hangupType ?: CallManager.HangupType.NORMAL,
                deviceId = deviceId ?: 0,
            ),
        )
    }

    override fun onSendBusy(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?) {
        val id = callId?.longValue() ?: return
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Busy(id))
    }

    /**
     * Send one signaling message and report the outcome back. RingRTC blocks its signaling queue until
     * [CallManager.messageSent] or [CallManager.messageSendFailure] is called, so every path must end in
     * one of them.
     */
    private fun dispatch(
        callId: Long,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        urgent: Boolean,
        message: SignalCallMessage,
    ) {
        val aci = (remote as? SignalRemote)?.aci
        if (aci == null) {
            reportSendResult(callId, false)
            return
        }
        // broadcast means "every device"; otherwise the message is addressed to one.
        val deviceId = if (broadcast == true) null else remoteDeviceId
        scope.launch {
            val ok = try {
                signaling.sendCallMessage(aci, deviceId, message, urgent)
            } catch (t: Throwable) {
                Log.w(TAG, "call signaling send failed", t)
                false
            }
            reportSendResult(callId, ok)
        }
    }

    private fun reportSendResult(callId: Long, ok: Boolean) {
        val manager = callManager ?: return
        try {
            if (ok) manager.messageSent(CallId(callId)) else manager.messageSendFailure(CallId(callId))
        } catch (t: Throwable) {
            Log.w(TAG, "could not report the signaling send result", t)
        }
    }

    // -- Observer: RingRTC uses the app as its HTTP stack --

    override fun onSendHttpRequest(
        requestId: Long,
        url: String,
        method: CallManager.HttpMethod,
        headers: List<HttpHeader>?,
        body: ByteArray?,
    ) {
        val manager = callManager ?: return
        scope.launch {
            try {
                val resp = NetworkClient.execute(
                    url,
                    method = when (method) {
                        CallManager.HttpMethod.GET -> "GET"
                        CallManager.HttpMethod.PUT -> "PUT"
                        CallManager.HttpMethod.POST -> "POST"
                        CallManager.HttpMethod.DELETE -> "DELETE"
                    },
                    headers = headers?.associate { it.name to it.value }.orEmpty(),
                    body = body,
                    sslSocketFactory = sslSocketFactory(),
                )
                manager.receivedHttpResponse(requestId, resp.status, resp.bytes)
            } catch (t: Throwable) {
                Log.w(TAG, "RingRTC HTTP request failed", t)
                try { manager.httpRequestFailed(requestId) } catch (_: Throwable) {}
            }
        }
    }

    // -- Observer: group calling, out of scope --

    override fun onSendCallMessage(
        recipientUuid: UUID,
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
    ) {
        Log.w(TAG, "onSendCallMessage is only used by group calling, which is not implemented")
    }

    override fun onSendCallMessageToGroup(
        groupId: ByteArray,
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
        overrideRecipients: List<UUID>,
    ) {
        Log.w(TAG, "group calling is not implemented")
    }

    override fun onSendCallMessageToAdhocGroup(
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
        expiration: Instant,
        recipientsToEndorsements: Map<UUID, ByteArray>,
    ) {
        Log.w(TAG, "ad-hoc call groups are not implemented")
    }

    override fun onGroupCallRingUpdate(
        groupId: ByteArray,
        ringId: Long,
        sender: UUID,
        update: CallManager.RingUpdate?,
    ) {
        Log.w(TAG, "group call ring updates are not implemented")
    }

    private inline fun withManager(what: String, block: (CallManager) -> Unit): Boolean {
        val manager = callManager ?: return false
        return try {
            block(manager)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
            false
        }
    }

    /** Media is audio-only for now, so nothing renders video frames. */
    private object NoVideoSink : VideoSink {
        override fun onFrame(frame: org.webrtc.VideoFrame?) {
            frame?.release()
        }
    }

    private companion object {
        const val TAG = "SignalCallManager"
        const val AUDIO_LEVEL_INTERVAL_MS = 500
    }
}
