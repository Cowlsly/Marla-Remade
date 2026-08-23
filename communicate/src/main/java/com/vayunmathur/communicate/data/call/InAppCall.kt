package com.vayunmathur.communicate.data.call

import com.vayunmathur.communicate.data.CommunicateLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the line behind the active call can actually do.
 *
 * The call screen is shared across lines, so it renders from this rather than branching on the line:
 * Google Voice has a DTMF keypad but no video, WhatsApp and Signal have video but no keypad. A control the
 * line cannot honour is simply not shown, instead of being present and doing nothing.
 */
data class CallCapabilities(
    val mute: Boolean = true,
    val speaker: Boolean = true,
    val video: Boolean = false,
    val dtmf: Boolean = false,
) {
    companion object {
        val AudioOnly = CallCapabilities()
        val AudioAndVideo = CallCapabilities(video = true)
        val AudioAndKeypad = CallCapabilities(dtmf = true)
    }
}

/** Phases an in-app VoIP call moves through, shared by every line that isn't handled by the SIM. */
enum class InAppCallPhase { Idle, Outgoing, Incoming, Connecting, Active, Ended }

/**
 * The single active in-app call, in the shape the UI and the Telecom bridge need.
 *
 * Deliberately richer than what the underlying stacks expose: RingRTC reports only
 * ringing/connecting/connected and drops the call id on most callbacks, so mute, speaker, the peer's
 * name and the connect time have to be tracked here or the UI has nothing to show.
 */
data class InAppCallState(
    val phase: InAppCallPhase = InAppCallPhase.Idle,
    val line: CommunicateLine? = null,
    /** Line-specific id (ACI, JID) used to address call actions. */
    val peerId: String = "",
    val peerName: String = "",
    val isVideo: Boolean = false,
    val capabilities: CallCapabilities = CallCapabilities.AudioOnly,
    /** Whether the peer is currently sending video, which is independent of whether we are. */
    val remoteVideoEnabled: Boolean = false,
    /** Whether our own camera is on. */
    val localVideoEnabled: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** When the call became [InAppCallPhase.Active], for the duration readout. 0 until then. */
    val connectedAtMs: Long = 0L,
    val endReason: String? = null,
) {
    val isRinging: Boolean get() = phase == InAppCallPhase.Incoming
    val isOngoing: Boolean get() = phase == InAppCallPhase.Active || phase == InAppCallPhase.Connecting
}

/**
 * What the call UI and the system call surface can do to a call, implemented per line.
 *
 * Kept minimal on purpose: anything a line cannot do is a no-op rather than an error, so the shared
 * screen does not need to know which line it is driving.
 */
interface InAppCallController {
    fun answer()
    fun reject()
    fun hangup()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
}

/** Implemented by the Telecom `Connection` so a line's manager can drive the system call surface. */
interface InAppCallConnectionBridge {
    fun onCallActive()

    fun onCallEnded()
}

/**
 * Tone dialling, for lines that carry a call to the PSTN. Separate from [InAppCallController] because only
 * Google Voice can act on it.
 */
interface InAppCallDtmfController {
    fun sendDtmf(digit: String)
}

/**
 * Video capability, separate from [InAppCallController] because not every line has it and the shared screen
 * should only offer the control when something can act on it.
 */
interface InAppCallVideoController {
    fun setVideoEnabled(enabled: Boolean)

    fun flipCamera()

    /** EGL context the renderers must share with the decoder, or null if video is unavailable. */
    fun eglContext(): org.webrtc.EglBase.Context?

    fun attachRenderers(local: org.webrtc.VideoSink?, remote: org.webrtc.VideoSink?)
}

/**
 * The one place that knows whether a call is up, regardless of line.
 *
 * A single registry rather than one per line because only one in-app call can be active at a time, and
 * because the call screen, the notification and the Telecom connection all need the same view of it.
 * Google Voice deliberately stays outside this: it already has its own Telecom integration.
 */
object InAppCallRegistry {
    private const val TAG = "InAppCallRegistry"

    private val _state = MutableStateFlow(InAppCallState())
    val state: StateFlow<InAppCallState> = _state.asStateFlow()

    @Volatile
    private var controller: InAppCallController? = null

    /** Set only by lines that can carry video, so the UI can offer the control. */
    @Volatile
    var videoController: InAppCallVideoController? = null

    /** Set only by lines that can send tones. */
    @Volatile
    var dtmfController: InAppCallDtmfController? = null

    /** Set by the Telecom ConnectionService so state changes can be reflected into the system UI. */
    @Volatile
    var connection: InAppCallConnectionBridge? = null

    fun bind(
        line: CommunicateLine,
        controller: InAppCallController,
        capabilities: CallCapabilities = CallCapabilities.AudioOnly,
    ) {
        this.controller = controller
        _state.value = _state.value.copy(line = line, capabilities = capabilities)
    }

    /** Called by a line when its call starts, so the UI can appear before any media is up. */
    fun onCallStarting(
        line: CommunicateLine,
        peerId: String,
        peerName: String,
        isVideo: Boolean,
        incoming: Boolean,
        capabilities: CallCapabilities = _state.value.capabilities,
    ) {
        android.util.Log.i(TAG, "call starting: line=$line incoming=$incoming video=$isVideo peer=$peerId")
        _state.value = InAppCallState(
            phase = if (incoming) InAppCallPhase.Incoming else InAppCallPhase.Outgoing,
            line = line,
            peerId = peerId,
            peerName = peerName.ifBlank { peerId },
            isVideo = isVideo,
            capabilities = capabilities,
        )
    }

    fun onPhase(phase: InAppCallPhase) {
        val current = _state.value
        if (current.phase == InAppCallPhase.Idle && phase != InAppCallPhase.Incoming) {
            android.util.Log.i(TAG, "ignoring phase $phase while idle")
            return
        }
        if (current.phase != phase) android.util.Log.i(TAG, "phase ${current.phase} -> $phase")
        _state.value = current.copy(
            phase = phase,
            // Stamped once, on the transition into Active, so the duration does not restart.
            connectedAtMs = if (phase == InAppCallPhase.Active && current.connectedAtMs == 0L) {
                System.currentTimeMillis()
            } else {
                current.connectedAtMs
            },
        )
        if (phase == InAppCallPhase.Active) connection?.onCallActive()
    }

    fun onEnded(reason: String?) {
        val current = _state.value
        if (current.phase == InAppCallPhase.Idle) return
        _state.value = current.copy(phase = InAppCallPhase.Ended, endReason = reason)
        connection?.onCallEnded()
    }

    fun onMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
    }

    fun onSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speaker = on)
    }

    fun onRemoteVideo(enabled: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        android.util.Log.i(TAG, "remote video enabled=$enabled")
        _state.value = _state.value.copy(remoteVideoEnabled = enabled)
    }

    fun onLocalVideo(enabled: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        _state.value = _state.value.copy(localVideoEnabled = enabled, isVideo = _state.value.isVideo || enabled)
    }

    /** Turn our own camera on or off mid-call. */
    fun toggleVideo() {
        val next = !_state.value.localVideoEnabled
        videoController?.setVideoEnabled(next)
        onLocalVideo(next)
    }

    fun flipCamera() {
        videoController?.flipCamera()
    }

    fun sendDtmf(digit: String) {
        dtmfController?.sendDtmf(digit)
    }

    /** Called by the UI once a terminal state has been shown, returning to Idle. */
    fun clearEnded() {
        if (_state.value.phase == InAppCallPhase.Ended) {
            _state.value = InAppCallState()
            controller = null
            videoController = null
            dtmfController = null
            connection = null
        }
    }

    // -- Actions, forwarded to whichever line owns the call --

    /** [source] is logged so it is clear what ended a call; several paths can. */
    fun answer(source: String = "ui") {
        android.util.Log.i(TAG, "answer requested by $source")
        controller?.answer()
    }

    fun reject(source: String = "ui") {
        android.util.Log.i(TAG, "reject requested by $source")
        controller?.reject()
    }

    fun hangup(source: String = "ui") {
        android.util.Log.i(TAG, "hangup requested by $source")
        controller?.hangup()
    }

    fun toggleMuted() {
        val next = !_state.value.muted
        controller?.setMuted(next)
        onMuted(next)
    }

    fun toggleSpeaker() {
        val next = !_state.value.speaker
        controller?.setSpeaker(next)
        onSpeaker(next)
    }
}
