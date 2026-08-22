package com.vayunmathur.communicate.data.call

import com.vayunmathur.communicate.data.CommunicateLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * The one place that knows whether a call is up, regardless of line.
 *
 * A single registry rather than one per line because only one in-app call can be active at a time, and
 * because the call screen, the notification and the Telecom connection all need the same view of it.
 * Google Voice deliberately stays outside this: it already has its own Telecom integration.
 */
object InAppCallRegistry {
    private val _state = MutableStateFlow(InAppCallState())
    val state: StateFlow<InAppCallState> = _state.asStateFlow()

    @Volatile
    private var controller: InAppCallController? = null

    /** Set by the Telecom ConnectionService so state changes can be reflected into the system UI. */
    @Volatile
    var connection: InAppCallConnectionBridge? = null

    fun bind(line: CommunicateLine, controller: InAppCallController) {
        this.controller = controller
        _state.value = _state.value.copy(line = line)
    }

    /** Called by a line when its call starts, so the UI can appear before any media is up. */
    fun onCallStarting(
        line: CommunicateLine,
        peerId: String,
        peerName: String,
        isVideo: Boolean,
        incoming: Boolean,
    ) {
        _state.value = InAppCallState(
            phase = if (incoming) InAppCallPhase.Incoming else InAppCallPhase.Outgoing,
            line = line,
            peerId = peerId,
            peerName = peerName.ifBlank { peerId },
            isVideo = isVideo,
        )
    }

    fun onPhase(phase: InAppCallPhase) {
        val current = _state.value
        if (current.phase == InAppCallPhase.Idle && phase != InAppCallPhase.Incoming) return
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

    /** Called by the UI once a terminal state has been shown, returning to Idle. */
    fun clearEnded() {
        if (_state.value.phase == InAppCallPhase.Ended) {
            _state.value = InAppCallState()
            controller = null
            connection = null
        }
    }

    // -- Actions, forwarded to whichever line owns the call --

    fun answer() = controller?.answer() ?: Unit

    fun reject() = controller?.reject() ?: Unit

    fun hangup() = controller?.hangup() ?: Unit

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
