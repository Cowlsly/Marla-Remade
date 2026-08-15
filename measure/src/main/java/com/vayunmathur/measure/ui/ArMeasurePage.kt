package com.vayunmathur.measure.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.ui.ArMeasureActions
import com.vayunmathur.measure.ui.ArMeasureUiState
import com.vayunmathur.measure.ui.MeasureViewModel
import com.vayunmathur.measure.ui.components.MeasureCamera
import com.vayunmathur.measure.ui.trackingQualityFrom
import kotlinx.coroutines.delay

@Composable
fun ArMeasurePage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.ar.collectAsState()
    var sessionHandle by remember { mutableLongStateOf(0L) }
    var screenPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val cameraGranted = rememberCameraPermission { viewModel.setCameraPermission(it) }

    // Reproject anchors whenever the anchor set or the pose changes. Projection lives
    // in the engine because it owns the current pose and the intrinsics.
    LaunchedEffect(state.anchors, state.quality, canvasSize) {
        if (sessionHandle == 0L || canvasSize == Size.Zero || state.anchors.isEmpty()) {
            screenPoints = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val flat = DoubleArray(state.anchors.size * 3)
            state.anchors.forEachIndexed { i, a ->
                flat[i * 3] = a.x
                flat[i * 3 + 1] = a.y
                flat[i * 3 + 2] = a.z
            }
            val projected = MeasureNative.projectPoints(
                sessionHandle,
                flat,
                canvasSize.width.toDouble(),
                canvasSize.height.toDouble(),
            )
            screenPoints = projected.toList().chunked(3).map { c ->
                Offset(c[0].toFloat() * canvasSize.width, c[1].toFloat() * canvasSize.height)
            }
            delay(PROJECTION_INTERVAL_MS)
        }
    }

    ArMeasureContent(
        state = state,
        actions = viewModel,
        screenPoints = screenPoints,
        onCanvasSized = { canvasSize = it },
        onTap = { nx, ny ->
            if (canvasSize != Size.Zero) {
                val hit = MeasureNative.rayToWorld(
                    sessionHandle,
                    nx * canvasSize.width,
                    ny * canvasSize.height,
                )
                if (hit != null) {
                    viewModel.addResolvedAnchor(hit[0], hit[1], hit[2], hit[3] > 0.5)
                }
            }
        },
        cameraContent = {
            if (cameraGranted && MeasureNative.available) {
                MeasureCamera(
                    imuRecorder = viewModel.imuRecorder,
                    onTrackingUpdate = { q, hasPlane ->
                        viewModel.onTrackingUpdate(trackingQualityFrom(q), hasPlane)
                    },
                    onSessionReady = { sessionHandle = it },
                    onDiagnostics = { fps, skew, imuHz, tracked, landmarks, confidence ->
                        viewModel.updateDiagnostics {
                            copy(
                                frameRateHz = fps,
                                timestampSkewMs = skew,
                                imuRateHz = imuHz,
                                trackedCount = tracked,
                                featureCount = tracked,
                                landmarkCount = landmarks,
                                scaleConfidence = confidence,
                                nativeEngineAvailable = MeasureNative.available,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        onOpenSettings = { backStack.add(Route.Settings) },
        bottomBar = { MeasureBottomBar(backStack, Route.ArMeasure) },
    )
}

/** Requests CAMERA lazily, so the four sensor tools never trigger a camera prompt. */
@Composable
private fun rememberCameraPermission(onResult: (Boolean) -> Unit): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        onResult(result)
    }
    LaunchedEffect(Unit) {
        onResult(granted)
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    return granted
}

private const val PROJECTION_INTERVAL_MS = 33L

@Composable
fun ArMeasureContent(
    state: ArMeasureUiState,
    actions: ArMeasureActions,
    screenPoints: List<Offset> = emptyList(),
    onCanvasSized: (Size) -> Unit = {},
    onTap: (Float, Float) -> Unit = { _, _ -> },
    cameraContent: @Composable () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    AppScaffold(
        title = "Measure",
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            if (!MeasureNative.available) {
                UnavailableNotice(
                    "AR measuring needs the native tracking engine, which isn't available " +
                        "on this device."
                )
                return@Box
            }
            if (!state.cameraPermissionGranted) {
                UnavailableNotice("Camera access is needed to measure in AR.")
                return@Box
            }

            cameraContent()

            ArOverlay(
                state = state,
                screenPoints = screenPoints,
                onCanvasSized = onCanvasSized,
                onTap = onTap,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackingBanner(state.quality, state.hasPlane)
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadoutCard(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.undoAnchor() },
                        enabled = state.anchors.isNotEmpty(),
                    ) { IconUndo() }
                    OutlinedButton(
                        onClick = { actions.closePolygon() },
                        enabled = state.anchors.size >= 3 && !state.polygonClosed,
                    ) { Text("Close shape") }
                    OutlinedButton(
                        onClick = { actions.clearAnchors() },
                        enabled = state.anchors.isNotEmpty(),
                    ) { Text("Clear") }
                }
            }
        }
    }
}

/**
 * Coaching banner.
 *
 * While initialising this is not decoration — monocular visual-inertial tracking cannot
 * recover metric scale from rotation alone, so the user genuinely has to translate the
 * device before any distance exists. Saying so plainly is more useful than a spinner.
 */
@Composable
private fun TrackingBanner(quality: TrackingQuality, hasPlane: Boolean) {
    val (message, color) = when (quality) {
        TrackingQuality.Initialising ->
            "Move the phone sideways and turn it slightly to start tracking" to
                MaterialTheme.colorScheme.tertiaryContainer

        TrackingQuality.Limited ->
            "Tracking is weak — move slowly and keep texture in view" to
                MaterialTheme.colorScheme.tertiaryContainer

        TrackingQuality.Lost ->
            "Tracking lost — point at a textured surface to recover" to
                MaterialTheme.colorScheme.errorContainer

        TrackingQuality.Good ->
            (if (hasPlane) "Ready — tap to place points" else "Ready — no surface found yet") to
                MaterialTheme.colorScheme.primaryContainer
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadoutCard(state: ArMeasureUiState) {
    if (state.distanceM == null && state.areaM2 == null) return
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            state.distanceM?.let {
                Text(
                    Units.formatLength(it, state.unitSystem),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            state.areaM2?.let {
                Text(
                    "Area ${Units.formatArea(it, state.unitSystem)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.perimeterM?.let {
                Text(
                    "Perimeter ${Units.formatLength(it, state.unitSystem)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Anchor and edge overlay.
 *
 * Anchors are drawn from screen positions the engine projected from their world
 * coordinates, so this layer holds no projection maths and stays previewable.
 */
@Composable
private fun ArOverlay(
    state: ArMeasureUiState,
    screenPoints: List<Offset>,
    onCanvasSized: (Size) -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val measurable = state.quality == TrackingQuality.Good ||
        state.quality == TrackingQuality.Limited

    Canvas(
        modifier
            .onSizeChanged { onCanvasSized(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(measurable) {
                if (!measurable) return@pointerInput
                detectTapGestures { pos ->
                    onTap(pos.x / size.width, pos.y / size.height)
                }
            }
    ) {
        // Reticle at the centre, showing where a tap would land.
        if (measurable) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(primary.copy(alpha = 0.9f), 6f, c)
            drawCircle(primary.copy(alpha = 0.4f), 22f, c, style = Stroke(2f))
        }

        for (i in 0 until screenPoints.size - 1) {
            drawLine(primary, screenPoints[i], screenPoints[i + 1], 4f)
        }
        if (state.polygonClosed && screenPoints.size >= 3) {
            drawLine(primary, screenPoints.last(), screenPoints.first(), 4f)
        }
        for (p in screenPoints) {
            drawCircle(primary, 12f, p)
            drawCircle(onPrimary, 5f, p)
        }
    }
}

@Composable
private fun UnavailableNotice(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AR measure unavailable", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

