package com.vayunmathur.communicate.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconCameraOff
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconFlipCamera
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen UI for an in-app WhatsApp or Signal call, driven by [InAppCallRegistry].
 *
 * One screen for both lines: the phases and controls are identical, and only the label differs. Google
 * Voice keeps its own screen because it has DTMF and a different phase model.
 */
@Composable
fun InAppCallScreen(onClose: () -> Unit) {
    val state by InAppCallRegistry.state.collectAsState()

    // A terminal state is shown briefly so the outcome is visible, then dismissed.
    LaunchedEffect(state.phase) {
        if (state.phase == InAppCallPhase.Ended) {
            delay(1500)
            InAppCallRegistry.clearEnded()
            onClose()
        } else if (state.phase == InAppCallPhase.Idle) {
            onClose()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Video fills the screen behind the controls when either side has a camera on.
            if (state.remoteVideoEnabled || state.localVideoEnabled) {
                CallVideo(
                    showRemote = state.remoteVideoEnabled,
                    showLocal = state.localVideoEnabled,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    state.peerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    phaseLabel(state.phase, state.line, state.endReason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.phase == InAppCallPhase.Active && state.connectedAtMs > 0L) {
                    Spacer(Modifier.height(4.dp))
                    CallDuration(state.connectedAtMs)
                }

                Spacer(Modifier.weight(1f))

                when (state.phase) {
                    InAppCallPhase.Incoming -> IncomingControls()
                    InAppCallPhase.Ended -> Unit
                    else -> InCallControls(
                        muted = state.muted,
                        video = state.localVideoEnabled,
                        canVideo = InAppCallRegistry.videoController != null,
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Renders the call's video.
 *
 * The renderers share the decoder's EGL context and are attached to the line's sinks for as long as they
 * are composed, then detached — a renderer that outlives its composition would be handed frames after
 * release.
 */
@Composable
private fun CallVideo(showRemote: Boolean, showLocal: Boolean) {
    val controller = InAppCallRegistry.videoController ?: return
    val eglContext = remember(controller) { controller.eglContext() } ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        if (showRemote) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { renderer ->
                    controller.attachRenderers(local = null, remote = null)
                    renderer.release()
                },
                update = { renderer -> controller.attachRenderers(local = null, remote = renderer) },
            )
        }
        if (showLocal) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(true)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 108.dp, height = 144.dp),
                onRelease = { renderer -> renderer.release() },
                update = { renderer -> controller.attachRenderers(local = renderer, remote = null) },
            )
        }
    }
}

/** Ticks once a second so the readout advances without recomposing the whole screen on every frame. */
@Composable
private fun CallDuration(connectedAtMs: Long) {
    var elapsed by remember(connectedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAtMs).coerceAtLeast(0L) / 1000
            delay(1000)
        }
    }
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    Text(
        "%d:%02d".format(minutes, seconds),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IncomingControls() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallActionButton(onClick = { InAppCallRegistry.reject() }) { IconClose() }
        CallActionButton(onClick = { InAppCallRegistry.answer() }) { IconCall() }
    }
}

@Composable
private fun InCallControls(muted: Boolean, video: Boolean, canVideo: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallActionButton(onClick = { InAppCallRegistry.toggleMuted() }) {
            if (muted) IconMicOff() else IconMic()
        }
        CallActionButton(onClick = { InAppCallRegistry.toggleSpeaker() }) { IconVolumeUp() }
        if (canVideo) {
            CallActionButton(onClick = { InAppCallRegistry.toggleVideo() }) {
                if (video) IconCameraOff() else IconVideoCamera()
            }
            if (video) {
                CallActionButton(onClick = { InAppCallRegistry.flipCamera() }) { IconFlipCamera() }
            }
        }
        CallActionButton(onClick = { InAppCallRegistry.hangup() }) { IconClose() }
    }
}

@Composable
private fun CallActionButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) { icon() }
    }
}

@Composable
private fun phaseLabel(phase: InAppCallPhase, line: CommunicateLine?, endReason: String?): String {
    val lineName = when (line) {
        CommunicateLine.Signal -> stringResource(R.string.account_signal)
        CommunicateLine.WhatsApp -> stringResource(R.string.account_whatsapp)
        else -> ""
    }
    return when (phase) {
        InAppCallPhase.Outgoing -> stringResource(R.string.call_state_dialing)
        InAppCallPhase.Incoming -> if (lineName.isBlank()) {
            stringResource(R.string.call_state_incoming_generic)
        } else {
            "${stringResource(R.string.call_state_incoming_generic)} - $lineName"
        }
        InAppCallPhase.Connecting -> stringResource(R.string.call_state_connecting)
        InAppCallPhase.Active -> lineName
        InAppCallPhase.Ended -> endReason?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.call_state_ended)
        InAppCallPhase.Idle -> ""
    }
}
