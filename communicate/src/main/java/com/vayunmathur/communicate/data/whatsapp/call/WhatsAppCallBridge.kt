package com.vayunmathur.communicate.data.whatsapp.call

import android.content.Context
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.telephony.InAppCallTelecom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors [WhatsAppCallManager]'s state into the shared [InAppCallRegistry] and hands calls to Telecom.
 *
 * An observer rather than a change inside the manager: its call flow already works, and the shared UI only
 * needs a projection of the state it already publishes.
 */
object WhatsAppCallBridge {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Idempotent; safe to call from both the activity and the sync service. */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            var lastPhase: WhatsAppCallPhase? = null
            WhatsAppCallManager.state.collect { state ->
                if (state.phase == lastPhase) return@collect
                val previous = lastPhase
                lastPhase = state.phase

                when (state.phase) {
                    WhatsAppCallPhase.Outgoing -> {
                        InAppCallRegistry.bind(
                            CommunicateLine.WhatsApp,
                            controller,
                            CallCapabilities.AudioAndVideo,
                        )
                        InAppCallRegistry.onCallStarting(
                            line = CommunicateLine.WhatsApp,
                            peerId = state.peerJid,
                            peerName = state.peerName.ifBlank { state.peerJid },
                            isVideo = state.isVideo,
                            incoming = false,
                            capabilities = CallCapabilities.AudioAndVideo,
                        )
                        InAppCallTelecom.addOutgoing(appContext, state.peerJid)
                    }
                    WhatsAppCallPhase.Incoming -> {
                        InAppCallRegistry.bind(
                            CommunicateLine.WhatsApp,
                            controller,
                            CallCapabilities.AudioAndVideo,
                        )
                        InAppCallRegistry.onCallStarting(
                            line = CommunicateLine.WhatsApp,
                            peerId = state.peerJid,
                            peerName = state.peerName.ifBlank { state.peerJid },
                            isVideo = state.isVideo,
                            incoming = true,
                            capabilities = CallCapabilities.AudioAndVideo,
                        )
                        InAppCallTelecom.addIncoming(appContext, state.peerJid)
                    }
                    WhatsAppCallPhase.Connecting -> InAppCallRegistry.onPhase(InAppCallPhase.Connecting)
                    WhatsAppCallPhase.Active -> InAppCallRegistry.onPhase(InAppCallPhase.Active)
                    WhatsAppCallPhase.Ended -> InAppCallRegistry.onEnded(null)
                    WhatsAppCallPhase.Idle ->
                        // Only meaningful as a return to rest after a call, not at startup.
                        if (previous != null) InAppCallRegistry.clearEnded()
                }
                if (state.phase != WhatsAppCallPhase.Idle) {
                    InAppCallRegistry.onMuted(state.muted)
                    InAppCallRegistry.onSpeaker(state.speaker)
                }
            }
        }
    }

    private val controller = object : InAppCallController {
        override fun answer() = WhatsAppCallManager.answer()

        override fun reject() = WhatsAppCallManager.reject()

        override fun hangup() = WhatsAppCallManager.hangup()

        override fun setMuted(muted: Boolean) = WhatsAppCallManager.setMuted(muted)

        override fun setSpeaker(on: Boolean) = WhatsAppCallManager.setSpeaker(on)
    }
}
