package com.vayunmathur.communicate.data.whatsapp.call

import android.content.Context
import android.media.AudioManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import com.vayunmathur.communicate.data.call.WebRtcInit
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred

/**
 * WebRTC media leg of a WhatsApp call (Phase D 3d — audio; extended for video in Phase E).
 *
 * Forked from `data/googlevoice/call/WebRtcAudioSession`. Carries one Opus audio m-line over a
 * single [PeerConnection]; SDP is exchanged (via the [WhatsAppCallSignaling] `<webrtc>` extension)
 * with ICE gathered once so the SDP is self-contained for the `<call>` stanza. Exposes mute +
 * speaker routing. Uses the maintained `io.getstream:stream-webrtc-android` prebuilt (`org.webrtc.*`).
 *
 * The 32-byte WhatsApp call key ([WhatsAppCallCrypto]) is exchanged/verified over signaling; the
 * actual client-to-client media confidentiality here is WebRTC's own DTLS-SRTP (WhatsApp's native
 * SRTP schedule can't interop from Java — documented limitation).
 */
open class WhatsAppCallSession(protected val appContext: Context) {

    protected val eglBase: EglBase = EglBase.create()
    protected var factory: PeerConnectionFactory? = null
    protected var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null

    protected val iceComplete = CompletableDeferred<Unit>()

    open fun initialize() {
        WebRtcInit.ensureInitialized(appContext)
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    protected open fun ensurePeerConnection() {
        if (peerConnection != null) return
        val f = factory ?: error("initialize() not called")
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        peerConnection = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE && !iceComplete.isCompleted) {
                    iceComplete.complete(Unit)
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
        addLocalAudio(f)
        onPeerConnectionCreated(f)
    }

    /** Hook for subclasses (video) to add extra local tracks after the audio track. */
    protected open fun onPeerConnectionCreated(f: PeerConnectionFactory) {}

    private fun addLocalAudio(f: PeerConnectionFactory) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source = f.createAudioSource(constraints)
        val track = f.createAudioTrack("wa_audio", source)
        audioSource = source
        localAudioTrack = track
        audioSender = peerConnection?.addTrack(track, listOf("wa_stream"))
    }

    protected open fun offerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    /** Create an SDP offer, set it locally, wait for ICE, and return the final SDP. */
    suspend fun createOffer(): String {
        ensurePeerConnection()
        val pc = peerConnection ?: error("no peer connection")
        val offer = pc.createOfferSuspend(offerConstraints())
        pc.setLocalDescriptionSuspend(offer)
        iceComplete.await()
        return pc.localDescription?.description ?: offer.description
    }

    /** Apply the remote answer SDP received in the peer's `<accept>`. */
    suspend fun setRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: error("no peer connection")
        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** For an inbound call: apply the remote offer, create + set an answer, return answer SDP. */
    suspend fun createAnswer(remoteOfferSdp: String): String {
        ensurePeerConnection()
        val pc = peerConnection ?: error("no peer connection")
        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp))
        val answer = pc.createAnswerSuspend(offerConstraints())
        pc.setLocalDescriptionSuspend(answer)
        iceComplete.await()
        return pc.localDescription?.description ?: answer.description
    }

    /** Add a remote ICE candidate (candidate line "candidate:..."). sdpMid/index default to audio m0. */
    fun addRemoteCandidate(candidate: String, sdpMid: String = "0", sdpMLineIndex: Int = 0) {
        runCatching { peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate)) }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setSpeaker(on: Boolean) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
    }

    open fun close() {
        runCatching { peerConnection?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { eglBase.release() }
        peerConnection = null
        audioSource = null
        localAudioTrack = null
        audioSender = null
    }

    // ---- SdpObserver → coroutine adapters ----

    private suspend fun PeerConnection.createOfferSuspend(c: MediaConstraints): SessionDescription =
        suspendCoroutine { cont ->
            createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createOffer: $error"))
            }, c)
        }

    private suspend fun PeerConnection.createAnswerSuspend(c: MediaConstraints): SessionDescription =
        suspendCoroutine { cont ->
            createAnswer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createAnswer: $error"))
            }, c)
        }

    private suspend fun PeerConnection.setLocalDescriptionSuspend(desc: SessionDescription) =
        suspendCoroutine<Unit> { cont ->
            setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setLocal: $error"))
            }, desc)
        }

    private suspend fun PeerConnection.setRemoteDescriptionSuspend(desc: SessionDescription) =
        suspendCoroutine<Unit> { cont ->
            setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setRemote: $error"))
            }, desc)
        }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
