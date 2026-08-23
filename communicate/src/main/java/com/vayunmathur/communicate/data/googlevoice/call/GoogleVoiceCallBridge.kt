package com.vayunmathur.communicate.data.googlevoice.call

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallDtmfController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors [GoogleVoiceCallManager] into the shared [InAppCallRegistry] so one call screen serves every line.
 *
 * An observer rather than a change inside the manager: Google Voice calling already works end to end,
 * including its own Telecom account, and this only projects the state it already publishes. Google Voice
 * keeps its own `ConnectionService`, so nothing here touches Telecom.
 */
object GoogleVoiceCallBridge {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Idempotent; safe to call from the activity and from the sync service. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var lastPhase: CallPhase? = null
            GoogleVoiceCallManager.state.collect { state ->
                if (state.phase != lastPhase) {
                    val previous = lastPhase
                    lastPhase = state.phase
                    when (state.phase) {
                        CallPhase.Dialing -> start(state, incoming = false)
                        CallPhase.Incoming -> start(state, incoming = true)
                        // Google Voice reports Ringing for the far end alerting on an outgoing call.
                        CallPhase.Ringing -> InAppCallRegistry.onPhase(InAppCallPhase.Outgoing)
                        CallPhase.Active -> InAppCallRegistry.onPhase(InAppCallPhase.Active)
                        CallPhase.Ended -> InAppCallRegistry.onEnded(null)
                        CallPhase.Idle -> if (previous != null) InAppCallRegistry.clearEnded()
                    }
                }
                if (state.phase != CallPhase.Idle) {
                    InAppCallRegistry.onMuted(state.muted)
                    InAppCallRegistry.onSpeaker(state.speaker)
                }
            }
        }
    }

    private fun start(state: CallState, incoming: Boolean) {
        InAppCallRegistry.bind(CommunicateLine.GoogleVoice, controller, CallCapabilities.AudioAndKeypad)
        InAppCallRegistry.dtmfController = dtmf
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.GoogleVoice,
            peerId = state.remoteNumber,
            peerName = state.remoteNumber,
            isVideo = false,
            incoming = incoming,
            capabilities = CallCapabilities.AudioAndKeypad,
        )
    }

    private val controller = object : InAppCallController {
        override fun answer() = GoogleVoiceCallManager.answer()

        override fun reject() = GoogleVoiceCallManager.reject()

        override fun hangup() = GoogleVoiceCallManager.hangup()

        override fun setMuted(muted: Boolean) = GoogleVoiceCallManager.setMuted(muted)

        override fun setSpeaker(on: Boolean) = GoogleVoiceCallManager.setSpeaker(on)
    }

    private val dtmf = object : InAppCallDtmfController {
        override fun sendDtmf(digit: String) = GoogleVoiceCallManager.sendDtmf(digit)
    }
}
