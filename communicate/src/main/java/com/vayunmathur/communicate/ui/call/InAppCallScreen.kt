package com.vayunmathur.communicate.ui.call

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.InAppCallState
import com.vayunmathur.communicate.ui.initialsFor
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconCallEnd
import com.vayunmathur.library.ui.IconCameraOff
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDialpad
import com.vayunmathur.library.ui.IconFlipCamera
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconScreenShare
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.scrim
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The call screen for every in-app line — Google Voice, WhatsApp and Signal.
 *
 * One screen rather than one per line, driven by [CallCapabilities]: Google Voice offers a keypad and no
 * camera, the other two the reverse, and a control the line cannot honour is not drawn at all. That keeps
 * the layout honest — nothing on screen is inert.
 *
 * Hierarchy comes from size and colour rather than from a row of identical buttons: the peer is a large
 * monogram, the primary action (answer / hang up) is a bigger coloured circle, and secondary toggles are
 * smaller and tonal.
 */
@Composable
fun InAppCallScreen(onClose: () -> Unit) {
    val state by InAppCallRegistry.state.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }

    // MediaProjection consent must be granted per share; the result Intent is the capture token.
    val screenShareConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val permission = result.data
        if (result.resultCode == Activity.RESULT_OK && permission != null) {
            InAppCallRegistry.setScreenShare(true, permission)
        }
    }
    val context = LocalContext.current

    // A terminal state is held briefly so the outcome is readable, then dismissed.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            InAppCallPhase.Ended -> {
                delay(1500)
                InAppCallRegistry.clearEnded()
                onClose()
            }
            InAppCallPhase.Idle -> onClose()
            else -> Unit
        }
    }

    val videoActive = state.remoteVideoEnabled || state.localVideoEnabled

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (videoActive) {
                CallVideo(showRemote = state.remoteVideoEnabled, showLocal = state.localVideoEnabled)
                // Keeps the name and controls legible over arbitrary video.
                Box(modifier = Modifier.fillMaxSize().scrim { 0.35f })
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.xxl))
                CallHeader(state = state, showMonogram = !videoActive)

                Spacer(Modifier.weight(1f))

                if (showKeypad && state.capabilities.dtmf) {
                    DtmfKeypad(onDigit = { InAppCallRegistry.sendDtmf(it) })
                    Spacer(Modifier.height(Spacing.lg))
                }

                when (state.phase) {
                    InAppCallPhase.Incoming -> IncomingControls()
                    InAppCallPhase.Ended -> Unit
                    else -> OngoingControls(
                        state = state,
                        keypadShown = showKeypad,
                        onToggleKeypad = { showKeypad = !showKeypad },
                        onToggleScreenShare = {
                            if (state.screenSharing) {
                                InAppCallRegistry.setScreenShare(false, null)
                            } else {
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                manager?.createScreenCaptureIntent()?.let(screenShareConsent::launch)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

/** Peer identity and call status. The monogram is dropped when video occupies the screen. */
@Composable
private fun CallHeader(state: InAppCallState, showMonogram: Boolean) {
    if (showMonogram) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsFor(state.peerName),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
    Text(
        state.peerName,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(Spacing.sm))
    Text(
        callStatusText(state),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.phase == InAppCallPhase.Active && state.connectedAtMs > 0L) {
        Spacer(Modifier.height(Spacing.xs))
        CallDuration(state.connectedAtMs)
    }
}

/** Ticks once a second, so only this subtree recomposes as the call runs. */
@Composable
private fun CallDuration(connectedAtMs: Long) {
    var elapsed by remember(connectedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAtMs).coerceAtLeast(0L) / 1000
            delay(1000)
        }
    }
    Text(
        "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Decline and answer, weighted equally but coloured oppositely so they cannot be confused. */
@Composable
private fun IncomingControls() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallAction(
            label = stringResource(R.string.call_decline),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            size = PRIMARY_ACTION,
            onClick = { InAppCallRegistry.reject() },
        ) { IconCallEnd(tint = it) }
        CallAction(
            label = stringResource(R.string.call_answer),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            size = PRIMARY_ACTION,
            onClick = { InAppCallRegistry.answer() },
        ) { IconCall(tint = it) }
    }
}

/**
 * In-call controls, drawn from [CallCapabilities]: only what the line supports appears. Hang up is the
 * largest and the only destructive colour, so it reads as the primary action.
 */
@Composable
private fun OngoingControls(
    state: InAppCallState,
    keypadShown: Boolean,
    onToggleKeypad: () -> Unit,
    onToggleScreenShare: () -> Unit,
) {
    val caps = state.capabilities
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (caps.mute) {
                CallToggle(
                    label = stringResource(R.string.call_mute),
                    active = state.muted,
                    onClick = { InAppCallRegistry.toggleMuted() },
                ) { tint -> if (state.muted) IconMicOff(tint = tint) else IconMic(tint = tint) }
            }
            if (caps.speaker && !state.localVideoEnabled) {
                // Sending video implies hands-free, so the route is forced and the control would be a
                // no-op the user could fight with.
                CallToggle(
                    label = stringResource(R.string.call_speaker),
                    active = state.speaker,
                    onClick = { InAppCallRegistry.toggleSpeaker() },
                ) { tint -> IconVolumeUp(tint = tint) }
            }
            if (caps.video) {
                CallToggle(
                    label = stringResource(R.string.call_video),
                    active = state.localVideoEnabled,
                    onClick = { InAppCallRegistry.toggleVideo() },
                ) { tint ->
                    if (state.localVideoEnabled) IconVideoCamera(tint = tint) else IconCameraOff(tint = tint)
                }
            }
            // Flipping the camera is meaningless while the source is the screen.
            if (caps.video && state.localVideoEnabled && !state.screenSharing) {
                CallToggle(
                    label = stringResource(R.string.call_flip_camera),
                    active = false,
                    onClick = { InAppCallRegistry.flipCamera() },
                ) { tint -> IconFlipCamera(tint = tint) }
            }
            if (caps.screenShare) {
                CallToggle(
                    label = stringResource(R.string.call_screen_share),
                    active = state.screenSharing,
                    onClick = onToggleScreenShare,
                ) { tint -> IconScreenShare(tint = tint) }
            }
            if (caps.dtmf) {
                CallToggle(
                    label = stringResource(R.string.call_keypad),
                    active = keypadShown,
                    onClick = onToggleKeypad,
                ) { tint -> IconDialpad(tint = tint) }
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        CallAction(
            label = stringResource(R.string.call_end),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            size = PRIMARY_ACTION,
            onClick = { InAppCallRegistry.hangup() },
        ) { IconCallEnd(tint = it) }
    }
}

/** A large, colour-carrying circular action. The library's FilledIconButton takes no colours. */
@Composable
private fun CallAction(
    label: String,
    container: Color,
    content: Color,
    size: Int,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            // Both dimensions, so the circle cannot be squashed by the row's constraints.
            modifier = Modifier.size(width = size.dp, height = size.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(content) }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** A secondary toggle. Filled when on, tonal when off, so state is visible without reading the icon. */
@Composable
private fun CallToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(width = SECONDARY_ACTION.dp, height = SECONDARY_ACTION.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(content) }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tone dialling, for lines that reach the PSTN. */
@Composable
private fun DtmfKeypad(onDigit: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the call's video. Renderers share the decoder's EGL context and are detached on release, since a
 * renderer handed frames after release crashes the decoder thread.
 */
@Composable
private fun CallVideo(showRemote: Boolean, showLocal: Boolean) {
    val controller = InAppCallRegistry.videoController ?: return
    val eglContext = remember(controller) { controller.eglContext() } ?: return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
                    controller.attachRemoteRenderer(null)
                    renderer.release()
                },
                update = { renderer -> controller.attachRemoteRenderer(renderer) },
            )
        }
        if (showLocal) {
            SelfView(
                eglContext = eglContext,
                bounds = DpSize(maxWidth, maxHeight),
                onRenderer = { renderer -> controller.attachLocalRenderer(renderer) },
                onRelease = { controller.attachLocalRenderer(null) },
            )
        }
    }
}

/**
 * The self-view: draggable anywhere on screen and pinch-resizable, like a picture-in-picture window.
 *
 * Position and scale are kept in this composable rather than in call state — they are a view preference, not
 * something the call or the other participant cares about. Both are clamped to [bounds] so the window cannot
 * be dragged or grown off-screen where it could not be recovered.
 */
@Composable
private fun SelfView(
    eglContext: org.webrtc.EglBase.Context,
    bounds: DpSize,
    onRenderer: (SurfaceViewRenderer) -> Unit,
    onRelease: () -> Unit,
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    val width = (SELF_VIEW_WIDTH.dp * scale).coerceIn(SELF_VIEW_MIN.dp, bounds.width * 0.6f)
    val height = width * SELF_VIEW_ASPECT

    // Starts bottom-end, clear of the controls.
    var offset by remember(bounds) {
        mutableStateOf(
            with(density) {
                Offset(
                    x = (bounds.width - width - Spacing.lg).toPx(),
                    y = (bounds.height - height - SELF_VIEW_BOTTOM_INSET.dp).toPx(),
                )
            },
        )
    }

    val maxOffset = with(density) {
        Offset((bounds.width - width).toPx().coerceAtLeast(0f), (bounds.height - height).toPx().coerceAtLeast(0f))
    }
    // Re-clamped after a resize, so growing near an edge does not push it out of reach.
    offset = Offset(offset.x.coerceIn(0f, maxOffset.x), offset.y.coerceIn(0f, maxOffset.y))

    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .size(width = width, height = height)
            .clip(RoundedCornerShape(Spacing.md))
            .pointerInput(bounds, width) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(SELF_VIEW_MIN_SCALE, SELF_VIEW_MAX_SCALE)
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(0f, maxOffset.x),
                        (offset.y + pan.y).coerceIn(0f, maxOffset.y),
                    )
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { renderer ->
                onRelease()
                renderer.release()
            },
            update = onRenderer,
        )
    }
}

@Composable
private fun callStatusText(state: InAppCallState): String {
    val lineName = when (state.line) {
        CommunicateLine.Signal -> stringResource(R.string.account_signal)
        CommunicateLine.WhatsApp -> stringResource(R.string.account_whatsapp)
        CommunicateLine.GoogleVoice -> stringResource(R.string.account_google_voice)
        else -> ""
    }
    return when (state.phase) {
        InAppCallPhase.Outgoing -> stringResource(R.string.call_state_dialing)
        InAppCallPhase.Incoming -> if (lineName.isBlank()) {
            stringResource(R.string.call_state_incoming_generic)
        } else {
            "${stringResource(R.string.call_state_incoming_generic)} - $lineName"
        }
        InAppCallPhase.Connecting -> stringResource(R.string.call_state_connecting)
        // Once connected the duration carries the status, so name the line instead.
        InAppCallPhase.Active -> lineName
        InAppCallPhase.Ended -> state.endReason?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.call_state_ended)
        InAppCallPhase.Idle -> ""
    }
}

private const val PRIMARY_ACTION = 72
private const val SECONDARY_ACTION = 56

private const val SELF_VIEW_WIDTH = 108
private const val SELF_VIEW_MIN = 72
private const val SELF_VIEW_ASPECT = 4f / 3f
private const val SELF_VIEW_MIN_SCALE = 0.7f
private const val SELF_VIEW_MAX_SCALE = 2.5f

/** Initial gap above the control row, so the self-view does not start on top of the buttons. */
private const val SELF_VIEW_BOTTOM_INSET = 220
