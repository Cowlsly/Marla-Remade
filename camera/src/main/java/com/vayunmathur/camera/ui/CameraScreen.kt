package com.vayunmathur.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.provider.MediaStore
import android.util.Patterns
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.DynamicRange
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconBlur
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconContrast
import com.vayunmathur.library.ui.IconFlashAuto
import com.vayunmathur.library.ui.IconFlashOff
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconFlashlight
import com.vayunmathur.library.ui.IconFlipCamera
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconIso
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.IconToolsLevel
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.camera.R
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.AspectRatioOption
import com.vayunmathur.camera.util.BokehAnalyzer
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.BOKEH_SHADER
import com.vayunmathur.camera.util.bokehBlurScale
import com.vayunmathur.camera.util.buildColorAdjustmentMatrix
import com.vayunmathur.camera.util.formatZoomLabel
import com.vayunmathur.camera.util.PhotoAnalyzer
import com.vayunmathur.camera.util.TimerDuration
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


private enum class CameraSetting {
    BRIGHTNESS, SHADOWS, WARMTH, EXPOSURE_TIME, PORTRAIT_BLUR, ISO
}

/** Which camera pipeline drives the preview for the current mode. */
private enum class SessionKind { PHOTO, PORTRAIT, PANORAMA, HIGH_SPEED, VIDEO }

private fun Modifier.selectedPill(
    selected: Boolean,
    shape: Shape,
    color: Color = Color(0xFF3C3C3C)
): Modifier = if (selected) background(color, shape) else this

@Composable
fun CameraScreen(
    backStack: NavBackStack<Route>,
    viewModel: CameraViewModel,
    onCaptureResult: ((android.graphics.Bitmap?) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current

    val cameraMode by viewModel.cameraMode.collectAsState()
    val lensFacing by viewModel.lensFacing.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDurationSec.collectAsState()
    val timerCountdown by viewModel.timerCountdown.collectAsState()
    val qrResult by viewModel.qrResult.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val timerDuration by viewModel.timerDuration.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val burstActive by viewModel.burstActive.collectAsState()
    val burstCount by viewModel.burstCount.collectAsState()
    val focusLocked by viewModel.focusLocked.collectAsState()
    val recordingPaused by viewModel.recordingPaused.collectAsState()
    val micMuted by viewModel.micMuted.collectAsState()
    val videoSnapshotSupported by viewModel.videoSnapshotSupported.collectAsState()
    val lastCaptureUri by viewModel.lastCaptureUri.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val levelEnabled by viewModel.levelEnabled.collectAsState()
    val roll by viewModel.roll.collectAsState()
    val blurStrength by viewModel.blurStrength.collectAsState()
    val exposureComp by viewModel.exposureCompensation.collectAsState()
    val warmth by viewModel.warmth.collectAsState()
    val shadows by viewModel.shadows.collectAsState()
    val exposureTimeIndex by viewModel.exposureTimeIndex.collectAsState()
    val manualIsoIndex by viewModel.manualIsoIndex.collectAsState()
    val isoStops by viewModel.isoStops.collectAsState()
    val longExposureProgress by viewModel.longExposureProgress.collectAsState()
    val longExposureRemaining by viewModel.longExposureRemaining.collectAsState()
    val lowLightDetected by viewModel.lowLightDetected.collectAsState()
    val nightModeActive by viewModel.nightModeActive.collectAsState()
    val extensionStrength by viewModel.extensionStrength.collectAsState()

    var activeSetting by remember { mutableStateOf<CameraSetting?>(null) }
    var maskBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val sloMoSupported by viewModel.sloMoSupported.collectAsState()
    val isPhotoType = cameraMode in listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.PANORAMA, CameraMode.PHOTOSPHERE)
    val isSloMo = cameraMode == CameraMode.SLOW_MO
    val isVideoType = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.TIMELAPSE || cameraMode == CameraMode.CINEMATIC
    val isPanoType = cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE
    val isPortrait = cameraMode == CameraMode.PORTRAIT
    val sessionKind = when {
        isSloMo -> SessionKind.HIGH_SPEED
        isVideoType -> SessionKind.VIDEO
        isPanoType -> SessionKind.PANORAMA
        isPortrait -> SessionKind.PORTRAIT
        else -> SessionKind.PHOTO
    }
    val highSpeedActive by viewModel.highSpeedActive.collectAsState()
    val photoSessionActive by viewModel.photoSessionActive.collectAsState()
    // Whether to offer the NIGHT extension. Reactive: the ViewModel recomputes it on lens/mode
    // change (off-main; support probe + weekly failure cache) AND flips it false immediately if a
    // night bind fails, so a broken extender (GrapheneOS/Pixel) stops engaging night after one try.
    val nightExtAvailable by viewModel.nightExtensionUsable.collectAsState()
    LaunchedEffect(lensFacing, cameraMode) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            viewModel.refreshNightExtensionUsable(cameraMode)
        }
    }
    // Auto night: when getNightModeIndicator()/luminance reports a dark scene (nightModeActive) in
    // PHOTO mode and the extension is usable, bind the vendor NIGHT extension preview.
    val useNightPreview = sessionKind == SessionKind.PHOTO && cameraMode == CameraMode.PHOTO &&
        nightModeActive && nightExtAvailable
    LaunchedEffect(useNightPreview) {
        Log.d("NightPreview", "CameraScreen useNightPreview recomputed=$useNightPreview sessionKind=$sessionKind cameraMode=$cameraMode nightModeActive=$nightModeActive nightExtAvailable=$nightExtAvailable lensFacing=$lensFacing")
    }
    val surfaceRequest by viewModel.surfaceRequest.collectAsState()
    LaunchedEffect(surfaceRequest) {
        Log.d("NightPreview", "CameraScreen surfaceRequest changed res=${surfaceRequest?.resolution} frameType=${surfaceRequest?.javaClass?.simpleName} useNightPreview=$useNightPreview sessionKind=$sessionKind thread=${Thread.currentThread().name} lensFacing=${lensFacing}")
        if (surfaceRequest == null && useNightPreview) {
            Log.w("NightPreview", "CameraScreen WARNING: surfaceRequest is NULL while useNightPreview=true – preview will be black until Provider re-emits. This is the black-frame trap you described: teardown -> delay(250) -> setupNightPreviewSession() tears down standard photo session surface before extension emits!")
        }
        if (surfaceRequest == null && !useNightPreview) {
            Log.w("NightPreview", "CameraScreen tracing maybe delayed rebind? surface null while normal photo – expected during 250ms delay window")
        }
    }
    val coordinateTransformer = remember { MutableCoordinateTransformer() }
    val availableZoomLevels by viewModel.availableZoomLevels.collectAsState()
    LaunchedEffect(availableZoomLevels) {
        Log.d("NightPreview", "CameraScreen availableZoomLevels changed=$availableZoomLevels currentZoom=${zoomRatio} useNightPreview=$useNightPreview – vendor NIGHT often reports only [1x], making zoom bar appear to 'disappear' except 1x")
    }
    // RuntimeShader and the runtime-shader RenderEffect are API 33+. On older
    // releases the portrait preview renders without the background blur.
    val bokehShader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { lazy { RuntimeShader(BOKEH_SHADER) } }
    } else {
        null
    }

    val panoSweeping by viewModel.panoramaEngine.isSweeping.collectAsState()
    val panoStitching by viewModel.panoramaEngine.isStitching.collectAsState()
    val panoDots by viewModel.panoramaEngine.guideDots.collectAsState()
    val panoCurrentAngle by viewModel.panoramaEngine.currentAngle.collectAsState()
    val panoDirection by viewModel.panoramaEngine.sweepDirection.collectAsState()
    val panoPitch by viewModel.panoramaEngine.currentPitch.collectAsState()

    // Orientation tracking
    var deviceRotation by remember { mutableIntStateOf(0) }
    val animatedRotation by animateFloatAsState(
        targetValue = deviceRotation.toFloat(),
        animationSpec = tween(durationMillis = 300)
    )

    // Tracks physical orientation purely to rotate the on-screen control icons. Capture output
    // rotation is handled by CameraX via SessionConfig.setAutoRotationEnabled(true).
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    orientation in 45..134 -> 270
                    orientation in 135..224 -> 180
                    orientation in 225..314 -> 90
                    else -> 0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // Gallery thumbnail (loaded off the main thread in the ViewModel)
    val galleryBitmap by viewModel.galleryThumbnail.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.updateLocation()
    }

    LaunchedEffect(flashMode) {
        viewModel.applyImageCaptureFlashMode()
    }

    LaunchedEffect(torchEnabled) {
        viewModel.enableTorch(torchEnabled)
    }

    LaunchedEffect(exposureComp) {
        viewModel.applyExposureCompensation(exposureComp)
    }

    // Close mode-specific settings when leaving the mode that offers them.
    LaunchedEffect(cameraMode) {
        val portraitOnly = activeSetting == CameraSetting.PORTRAIT_BLUR && cameraMode != CameraMode.PORTRAIT
        val photoOnly = activeSetting == CameraSetting.ISO && cameraMode != CameraMode.PHOTO
        if (portraitOnly || photoOnly) activeSetting = null
    }

    // Analyzer selection for the photo modes. Keyed on photoSessionActive so the analyzer is
    // re-applied to the freshly-bound ImageAnalysis after every (re)bind (mode switch / flip).
    LaunchedEffect(cameraMode, photoSessionActive) {
        when {
            cameraMode == CameraMode.SLOW_MO -> {
                maskBitmap = null
                viewModel.setImageAnalyzer(null)
                viewModel.setQrResult(null)
            }
            isPhotoType -> {
                when (cameraMode) {
                    CameraMode.PORTRAIT -> {
                        // Analyzer lifecycle (create/close) managed by DisposableEffect below.
                    }
                    CameraMode.PANORAMA, CameraMode.PHOTOSPHERE -> {
                        maskBitmap = null
                        viewModel.setImageAnalyzer(
                            ImageAnalysis.Analyzer { imageProxy ->
                                viewModel.panoramaEngine.latestFrame = imageProxy.toBitmap()
                                imageProxy.close()
                            }
                        )
                    }
                    else -> {
                        maskBitmap = null
                        viewModel.setImageAnalyzer(
                            PhotoAnalyzer(
                                onLuminance = { viewModel.onLuminance(it) },
                                onQrDetected = { viewModel.setQrResult(it) },
                                // Motion-Photo ring buffer only in plain PHOTO mode.
                                onMotionFrame = if (cameraMode == CameraMode.PHOTO) {
                                    { bmp, ts, rot -> viewModel.addMotionFrame(bmp, ts, rot) }
                                } else null
                            )
                        )
                    }
                }
            }
            else -> {
                maskBitmap = null
                viewModel.setQrResult(null)
            }
        }
    }

    // Owns the PORTRAIT bokeh segmenter so it is closed (and its mask recycled) on mode change.
    // Keyed on photoSessionActive (re-attach after a rebind) and lensFacing (front/back changes
    // the frame orientation/mirroring the analyzer must apply).
    // Runs on dedicated bokeh executor so main thread stays free for preview rendering.
    // Final capture ImageCapture is still max-res – only analysis is capped.
    // Mask callback comes from bokeh thread; post recycle+set to main to avoid racing with
    // the RenderEffect reading the previous Bitmap on the UI thread.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(cameraMode, photoSessionActive, lensFacing) {
        val analyzer = if (cameraMode == CameraMode.PORTRAIT) {
            BokehAnalyzer(
                context,
                isFrontFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
            ) { mask ->
                if (mask.isRecycled) return@BokehAnalyzer
                mainHandler.post {
                    // If mask was recycled while message queued, drop it
                    if (mask.isRecycled) return@post
                    val prev = maskBitmap
                    if (prev != null && prev !== mask && !prev.isRecycled) {
                        try { prev.recycle() } catch (_: Exception) {}
                    }
                    maskBitmap = mask
                }
            }.also {
                viewModel.setBokehAnalyzer(it)
            }
        } else null
        onDispose {
            viewModel.setImageAnalyzer(null)
            analyzer?.close()
            // Post recycle to avoid racing with graphicsLayer reading maskBitmap
            mainHandler.post {
                maskBitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        try { bmp.recycle() } catch (_: Exception) {}
                    }
                }
                maskBitmap = null
            }
        }
    }

    // Unified session binding, made lifecycle-aware so the camera is released when the app is
    // backgrounded and rebound on resume. Without this the ManualLifecycleOwner stays RESUMED,
    // the OS reclaims the camera while we're away, and the preview comes back frozen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lensFacing, sessionKind, useNightPreview, lifecycleOwner) {
        Log.d("NightPreview", "CameraScreen session LaunchedEffect START keys lensFacing=$lensFacing sessionKind=$sessionKind useNightPreview=$useNightPreview lifecycle=${lifecycleOwner.lifecycle.currentState} thread=${Thread.currentThread().name}")
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Log.d("NightPreview", "CameraScreen repeatOnLifecycle STARTED – calling teardownSession()")
            viewModel.teardownSession()
            // Brief yield so the previous session's surface detaches before the next binds; short
            // enough that a mode switch reads as instant, long enough to avoid a bind-over-teardown race.
            delay(40)
            val bindStart = System.currentTimeMillis()
            val bindSuccess = try {
                when (sessionKind) {
                    SessionKind.HIGH_SPEED -> viewModel.setupHighSpeedSession()
                    SessionKind.VIDEO -> viewModel.setupVideoSession()
                    SessionKind.PANORAMA -> viewModel.setupPanoramaSession()
                    SessionKind.PORTRAIT -> viewModel.setupPortraitSession()
                    SessionKind.PHOTO ->
                        if (useNightPreview) viewModel.setupNightPreviewSession()
                        else viewModel.setupPhotoSession()
                }
            } catch (e: Exception) {
                Log.e("NightPreview", "CameraScreen session binding THREW (was not logged before) kind=$sessionKind useNightPreview=$useNightPreview nightExtAvailable=$nightExtAvailable", e)
                false
            }
            Log.d("NightPreview", "CameraScreen session bind finished success=$bindSuccess took=${System.currentTimeMillis() - bindStart}ms surface after=${viewModel.surfaceRequest.value?.resolution} zoomLevels=${viewModel.availableZoomLevels.value} zoomRatio=${viewModel.zoomRatio.value}")
            if (!bindSuccess) {
                Log.e("NightPreview", "CameraScreen BIND FAILED – this produces solid black preview and zoom bar showing only 1x (fallback?). Check logcat for NightPreview tag – exception was previously swallowed as warning")
            }
            try {
                awaitCancellation()
            } finally {
                Log.d("NightPreview", "CameraScreen session coroutine cancelled/finished, calling teardownSession()")
                viewModel.teardownSession()
            }
        }
        Log.d("NightPreview", "CameraScreen repeatOnLifecycle block EXIT – lifecycle dropped below STARTED")
    }


    val previewAspectRatio = when (aspectRatio) {
        AspectRatioOption.RATIO_16_9 -> 9f / 16f
        AspectRatioOption.RATIO_4_3 -> 3f / 4f
        AspectRatioOption.RATIO_1_1 -> 1f
    }

    // The shutter action, shared by the on-screen button and the volume-key (hardware) shutter.
    val performCapture: () -> Unit = {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        if (longExposureProgress <= 0f) when {
            cameraMode == CameraMode.PHOTOSPHERE -> {
                if (panoSweeping) viewModel.stopPhotosphere() else viewModel.startPhotosphere()
            }
            cameraMode == CameraMode.PANORAMA -> {
                if (panoSweeping) viewModel.stopPanorama() else viewModel.startPanorama()
            }
            isSloMo && highSpeedActive -> viewModel.toggleHighSpeedRecording()
            isPhotoType -> {
                if (onCaptureResult != null) {
                    viewModel.capturePhotoForResult(
                        onSaved = { thumbnail -> onCaptureResult(thumbnail) },
                        onError = { /* stay in camera so the user can retry */ }
                    )
                } else {
                    viewModel.takePhoto()
                }
            }
            else -> viewModel.toggleRecording()
        }
    }
    val currentCapture by rememberUpdatedState(performCapture)
    LaunchedEffect(Unit) {
        viewModel.shutterEvents.collect { currentCapture() }
    }

    // RAW SCAFFOLD EXCEPTION: full-bleed viewfinder with a black surface and its own custom
    // top/bottom control bars (TopBar/ShutterRow/ModeSelector/BottomBar). It has no Material
    // top app bar or title, so AppScaffold would inject an unwanted bar, and TopAppBarOverlay
    // provides neither the black container surface nor the system-inset padding the preview
    // relies on. The bare Scaffold(containerColor) is the correct primitive here.
    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    flashMode = flashMode,
                    torchEnabled = torchEnabled,
                    gridEnabled = gridEnabled,
                    levelEnabled = levelEnabled,
                    aspectRatio = aspectRatio,
                    isPhotoType = isPhotoType,
                    isVideoType = isVideoType,
                    micMuted = micMuted,
                    timerDuration = timerDuration,
                    onFlashToggle = {
                        if (torchEnabled) {
                            viewModel.toggleTorch()
                        } else {
                            val next = when (flashMode) {
                                FlashMode.OFF -> FlashMode.ON
                                FlashMode.ON -> FlashMode.AUTO
                                FlashMode.AUTO -> FlashMode.OFF
                            }
                            viewModel.setFlashMode(next)
                        }
                    },
                    onTorchToggle = { viewModel.toggleTorch() },
                    onGridToggle = { viewModel.toggleGrid() },
                    onLevelToggle = { viewModel.toggleLevel() },
                    onAspectCycle = { viewModel.cycleAspectRatio() },
                    onMicToggle = { viewModel.toggleMicMuted() },
                    onTimerCycle = {
                        val next = when (timerDuration) {
                            TimerDuration.NONE -> TimerDuration.THREE
                            TimerDuration.THREE -> TimerDuration.FIVE
                            TimerDuration.FIVE -> TimerDuration.TEN
                            TimerDuration.TEN -> TimerDuration.NONE
                        }
                        viewModel.setTimerDuration(next)
                    },
                    iconRotation = animatedRotation
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    // Fit the preview inside the available area (letterboxed) so a tall ratio never
                    // overflows onto the top/bottom bars and steals their touch events.
                    val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                    val previewSize = if (previewAspectRatio > boxAspect) {
                        Modifier.fillMaxWidth().aspectRatio(previewAspectRatio)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(previewAspectRatio)
                    }
                    val previewModifier = previewSize
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            run {
                                val hasColorAdj = warmth != 0f || shadows != 0f
                                // Snapshot mask bitmap at this composition point – don't !! inside graphicsLayer
                                // or a race with DisposableEffect onDispose (null + recycle) causes NPE at 567.
                                val currentMask = maskBitmap?.takeIf {
                                    !it.isRecycled && it.width > 0 && it.height > 0
                                }
                                val hasBokeh = cameraMode == CameraMode.PORTRAIT && currentMask != null
                                if (hasBokeh || hasColorAdj) {
                                    Modifier.graphicsLayer {
                                        var effect: RenderEffect? = null

                                        if (hasBokeh &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                        ) {
                                            val localMask = currentMask
                                            val shader = bokehShader?.value
                                            if (shader != null && localMask != null &&
                                                !localMask.isRecycled &&
                                                size.width > 0 && size.height > 0
                                            ) {
                                                try {
                                                    val maskShader = BitmapShader(localMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                                                    val matrix = Matrix()
                                                    matrix.setScale(size.width / localMask.width, size.height / localMask.height)
                                                    maskShader.setLocalMatrix(matrix)
                                                    shader.setInputShader("alphaMask", maskShader)
                                                    shader.setFloatUniform("blurScale", bokehBlurScale(blurStrength))
                                                    effect = RenderEffect.createRuntimeShaderEffect(shader, "cameraInput")
                                                } catch (_: Exception) {
                                                    // Mask may be recycled between snapshot and render – skip bokeh this frame
                                                }
                                            }
                                        }

                                        if (hasColorAdj) {
                                            val colorEffect = RenderEffect.createColorFilterEffect(
                                                ColorMatrixColorFilter(
                                                    buildColorAdjustmentMatrix(warmth, shadows)
                                                )
                                            )
                                            effect = if (effect != null) {
                                                RenderEffect.createChainEffect(colorEffect, effect)
                                            } else {
                                                colorEffect
                                            }
                                        }

                                        renderEffect = effect?.asComposeRenderEffect()
                                    }
                                } else {
                                    Modifier.graphicsLayer { renderEffect = null }
                                }
                            }
                        )
                            // Tap-to-focus and pinch-to-zoom are handled by CameraXViewfinder's
                            // built-in gestures (below). Long-press to lock AE/AF isn't built in, so
                            // keep just that here.
                            .pointerInput(activeSetting) {
                                fun meteringPoint(tapOffset: Offset) = surfaceRequest?.let { request ->
                                    val transformed = with(coordinateTransformer) { tapOffset.transform() }
                                    val factory = SurfaceOrientedMeteringPointFactory(
                                        request.resolution.width.toFloat(),
                                        request.resolution.height.toFloat()
                                    )
                                    factory.createPoint(transformed.x, transformed.y)
                                }
                                detectTapGestures(
                                    onLongPress = { tapOffset ->
                                        val point = meteringPoint(tapOffset) ?: return@detectTapGestures
                                        viewModel.lockFocusAndMetering(
                                            FocusMeteringAction.Builder(point).disableAutoCancel().build()
                                        )
                                    }
                                )
                            }

                    surfaceRequest?.let { request ->
                        LaunchedEffect(request) {
                            Log.d("NightPreview", "CameraXViewfinder COMPOSED with request res=${request.resolution} dynamicRange=${request.dynamicRange} useNightPreview=$useNightPreview sessionKind=$sessionKind zoomRatio=${zoomRatio} levels=${availableZoomLevels} thread=${Thread.currentThread().name}")
                        }
                        DisposableEffect(request) {
                            Log.d("NightPreview", "CameraXViewfinder DisposableEffect ATTACH request res=${request.resolution} useNightPreview=$useNightPreview")
                            onDispose {
                                Log.d("NightPreview", "CameraXViewfinder DisposableEffect DETACH request res=${request.resolution} useNightPreview=$useNightPreview – surface will be invalidated, if next request fails to emit we go black")
                            }
                        }
                        CameraXViewfinder(
                            surfaceRequest = request,
                            modifier = previewModifier,
                            // HDR (HLG 10-bit) video previews must render through a SurfaceView
                            // (EXTERNAL): the EMBEDDED/TextureView path doesn't tone-map the BT.2020
                            // HLG buffer, which shows up as a reddish/pink cast in video mode. SDR
                            // (photo) stays EMBEDDED so graphicsLayer RenderEffects (warmth, bokeh)
                            // still composite onto the preview.
                            implementationMode = if (
                                request.dynamicRange.bitDepth != DynamicRange.BIT_DEPTH_8_BIT
                            ) {
                                ImplementationMode.EXTERNAL
                            } else {
                                ImplementationMode.EMBEDDED
                            },
                            coordinateTransformer = coordinateTransformer,
                            alignment = Alignment.Center,
                            // Crop (fill) so the camera feed fills the ratio-shaped box for the
                            // selected aspect ratio (1:1 / 16:9 / 4:3). Fit letterboxed the native
                            // 4:3 frame inside the box, making the preview shrink instead of reshape.
                            contentScale = ContentScale.Crop,
                            // Built-in tap-to-focus and pinch-to-zoom (1.7.0-alpha02). The viewfinder
                            // applies focus/zoom on the SurfaceRequest's camera; we just sync the
                            // displayed zoom ratio so the zoom bar stays in step.
                            isTapToFocusEnabled = true,
                            isPinchToZoomEnabled = true,
                            onZoomRatioChanged = { ratio -> viewModel.onViewfinderZoomRatio(ratio) },
                        )
                    }
                    if (surfaceRequest == null) {
                        // Brief gap between a mode's teardown and the next session's first frame.
                        // Show a plain black fill (no debug text) so the switch reads as instant.
                        Box(modifier = previewModifier.background(Color.Black))
                    }

                    if (gridEnabled) {
                        GridOverlay(
                            modifier = previewSize
                        )
                    }

                    if (levelEnabled && isPhotoType) {
                        LevelOverlay(
                            roll = roll,
                            modifier = previewSize
                        )
                    }

                    if (timerCountdown > 0) {
                        Text(
                            text = "$timerCountdown",
                            fontSize = 96.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (longExposureProgress > 0f) {
                        LongExposureOverlay(
                            progress = longExposureProgress,
                            remainingText = longExposureRemaining
                        )
                    }

                    // Auto night: shown when a dark scene is detected (getNightModeIndicator /
                    // luminance) and the extension is usable; tap toggles night off for this scene.
                    if (lowLightDetected && nightExtAvailable) {
                        NightModeButton(
                            active = nightModeActive,
                            onClick = { viewModel.toggleNightModeOverride() },
                            iconRotation = animatedRotation,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        )
                    }

                    // Live vendor night-processing strength while the NIGHT extension preview is bound.
                    extensionStrength?.let { strength ->
                        Text(
                            text = stringResource(R.string.night_2, strength),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp)
                                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (activeSetting) {
                            null -> ZoomBar(
                                currentZoom = zoomRatio,
                                zoomLevels = availableZoomLevels,
                                onZoomSelected = { viewModel.setZoomRatio(it) }
                            )
                            CameraSetting.BRIGHTNESS -> HorizontalSettingSlider(
                                value = exposureComp,
                                onValueChange = { viewModel.setExposureCompensation(it) },
                                icon = { m, c -> IconSunny(m, c) },
                                label = stringResource(com.vayunmathur.camera.R.string.brightness)
                            )
                            CameraSetting.SHADOWS -> HorizontalSettingSlider(
                                value = shadows,
                                onValueChange = { viewModel.setShadows(it) },
                                icon = { m, c -> IconContrast(m, c) },
                                label = stringResource(com.vayunmathur.camera.R.string.shadows)
                            )
                            CameraSetting.WARMTH -> HorizontalSettingSlider(
                                value = warmth,
                                onValueChange = { viewModel.setWarmth(it) },
                                icon = { m, c -> IconLightbulb(m, c) },
                                label = stringResource(com.vayunmathur.camera.R.string.warmth)
                            )
                            CameraSetting.EXPOSURE_TIME -> ExposureTimeBar(
                                selectedIndex = exposureTimeIndex,
                                onIndexChange = { viewModel.setExposureTimeIndex(it) }
                            )
                            CameraSetting.PORTRAIT_BLUR -> HorizontalSettingSlider(
                                value = blurStrength,
                                onValueChange = { viewModel.setBlurStrength(it) },
                                icon = { m, c -> IconBlur(m, c) },
                                label = stringResource(R.string.blur),
                                valueRange = 0f..1f,
                                displayValue = { "%.0f%%".format(it * 100) }
                            )
                            CameraSetting.ISO -> IsoBar(
                                selectedIndex = manualIsoIndex,
                                isoStops = isoStops,
                                onIndexChange = { viewModel.setManualIsoIndex(it) }
                            )
                        }

                        SettingsButtonRow(
                            activeSetting = activeSetting,
                            cameraMode = cameraMode,
                            onSelect = { activeSetting = it }
                        )
                    }

                    if ((cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE) && (panoSweeping || panoStitching)) {
                        PanoramaOverlay(
                            isSweeping = panoSweeping,
                            isStitching = panoStitching,
                            guideDots = panoDots,
                            currentAngle = panoCurrentAngle,
                            sweepDirection = panoDirection,
                            currentPitch = panoPitch,
                            modifier = previewSize
                        )
                    }
                }

                ShutterRow(
                    cameraMode = cameraMode,
                    isRecording = isRecording,
                    isCapturing = isCapturing,
                    recordingPaused = recordingPaused,
                    videoSnapshotSupported = videoSnapshotSupported,
                    galleryBitmap = galleryBitmap,
                    burstEnabled = cameraMode == CameraMode.PHOTO && onCaptureResult == null,
                    onBurstStart = { viewModel.startBurst() },
                    onBurstStop = { viewModel.stopBurst() },
                    onCapture = performCapture,
                    onPauseResume = { viewModel.togglePauseRecording() },
                    onSnapshot = { viewModel.captureVideoSnapshot() },
                    onFlipCamera = { viewModel.flipCamera() },
                    onGallery = {
                        // A last-capture URI can be stale (image deleted) or absent (nothing shot
                        // this session). Verify it still resolves, else open the gallery collection;
                        // wrap the whole launch so a missing image never crashes the app.
                        runCatching {
                            val uri = lastCaptureUri?.takeIf {
                                runCatching { context.contentResolver.getType(it) }.getOrNull() != null
                            }
                            val baseIntent = if (uri != null) {
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    clipData = ClipData.newRawUri(null, uri)
                                }
                            } else {
                                Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_APP_GALLERY)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                            try {
                                if (!ExternalIntents.launch(context, baseIntent)) {
                                    throw ActivityNotFoundException("System gallery not found")
                                }
                            } catch (_: ActivityNotFoundException) {
                                val fallback = if (uri != null) {
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        clipData = ClipData.newRawUri(null, uri)
                                    }
                                } else {
                                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                }
                                ExternalIntents.launch(context, Intent.createChooser(fallback, null))
                            }
                        }.onFailure { Log.w("CameraScreen", "Could not open image viewer", it) }
                    },
                    iconRotation = animatedRotation,
                    flipEnabled = !isSloMo
                )

                ModeSelector(
                    cameraMode = cameraMode,
                    isPhotoType = isPhotoType,
                    sloMoSupported = sloMoSupported,
                    onModeSelected = { viewModel.switchCameraMode(it) }
                )

                BottomBar(
                    cameraMode = cameraMode,
                    isPhotoType = isPhotoType,
                    iconRotation = animatedRotation,
                    onPickerChanged = { photo ->
                        if (photo) {
                            viewModel.switchCameraMode(CameraMode.PHOTO)
                        } else {
                            viewModel.switchCameraMode(CameraMode.VIDEO)
                        }
                    },
                    onSettingsClick = { backStack.add(Route.Settings) }
                )
            }

            qrResult?.let { qr ->
                QrResultOverlay(
                    text = qr,
                    onDismiss = { viewModel.setQrResult(null) },
                    context = context,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 280.dp)
                )
            }

            if (isRecording) {
                RecordingIndicator(
                    durationSec = recordingDuration,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                )
            }

            if (burstActive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.burst_count, burstCount),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (focusLocked && !isRecording) {
                Text(
                    text = stringResource(R.string.ae_af_lock),
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 100.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(
    flashMode: FlashMode,
    torchEnabled: Boolean,
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    aspectRatio: AspectRatioOption,
    isPhotoType: Boolean,
    isVideoType: Boolean,
    micMuted: Boolean,
    timerDuration: TimerDuration,
    onFlashToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onGridToggle: () -> Unit,
    onLevelToggle: () -> Unit,
    onAspectCycle: () -> Unit,
    onMicToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    iconRotation: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        // Centred rather than packed left: the control count changes with the mode (level in photo,
        // mic in video), so a left-aligned row shifts its contents around as you switch modes.
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val flashBg = if (torchEnabled || flashMode != FlashMode.OFF) Color(0xFF3C3C3C) else Color.Transparent
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(flashBg, CircleShape)
                .combinedClickable(
                    onClick = onFlashToggle,
                    onLongClick = onTorchToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            val flashIconModifier = Modifier.size(22.dp).rotate(iconRotation)
            if (torchEnabled) {
                IconFlashlight(flashIconModifier, Color.White)
            } else {
                when (flashMode) {
                    FlashMode.ON -> IconFlashOn(flashIconModifier, Color.White)
                    FlashMode.OFF -> IconFlashOff(flashIconModifier, Color.White)
                    FlashMode.AUTO -> IconFlashAuto(flashIconModifier, Color.White)
                }
            }
        }

        val gridBg = if (gridEnabled) Color(0xFF3C3C3C) else Color.Transparent
        IconButton(
            onClick = onGridToggle,
            modifier = Modifier
                .size(40.dp)
                .background(gridBg, CircleShape)
        ) {
            IconGrid(Modifier.size(22.dp).rotate(iconRotation), Color.White)
        }

        // The ratio as text: an icon of three nested rectangles does not tell you which one is
        // active, and the enum already carries "16:9" / "4:3" / "1:1".
        val aspectLabel = stringResource(R.string.settings_aspect_ratio)
        IconButton(
            onClick = onAspectCycle,
            modifier = Modifier.height(40.dp).widthIn(min = 40.dp)
        ) {
            Text(
                aspectRatio.label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .rotate(iconRotation)
                    .semantics { contentDescription = aspectLabel },
            )
        }

        if (isPhotoType) {
            val levelBg = if (levelEnabled) Color(0xFF3C3C3C) else Color.Transparent
            IconButton(
                onClick = onLevelToggle,
                modifier = Modifier
                    .size(40.dp)
                    .background(levelBg, CircleShape)
            ) {
                IconToolsLevel(
                    Modifier.size(22.dp).rotate(iconRotation),
                    Color.White
                )
            }
        }

        val timerBg = if (timerDuration != TimerDuration.NONE) Color(0xFF3C3C3C) else Color.Transparent
        IconButton(
            onClick = onTimerCycle,
            modifier = Modifier
                .size(40.dp)
                .background(timerBg, CircleShape)
        ) {
            if (timerDuration == TimerDuration.NONE) {
                IconTimer(Modifier.size(22.dp).rotate(iconRotation), Color.White)
            } else {
                val timerLabel = when (timerDuration) {
                    TimerDuration.NONE -> ""
                    TimerDuration.THREE -> "3"
                    TimerDuration.FIVE -> "5"
                    TimerDuration.TEN -> "10"
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTimer(Modifier.size(14.dp).rotate(iconRotation), Color.White)
                    Text(timerLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
                }
            }
        }

        if (isVideoType) {
            val micBg = if (micMuted) Color(0xFF3C3C3C) else Color.Transparent
            IconButton(
                onClick = onMicToggle,
                modifier = Modifier
                    .size(40.dp)
                    .background(micBg, CircleShape)
            ) {
                if (micMuted) IconMicOff(Modifier.size(22.dp).rotate(iconRotation), Color.White)
                else IconMic(Modifier.size(22.dp).rotate(iconRotation), Color.White)
            }
        }
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        for (i in 1..2) {
            drawLine(color, Offset(thirdW * i, 0f), Offset(thirdW * i, size.height), strokeWidth)
            drawLine(color, Offset(0f, thirdH * i), Offset(size.width, thirdH * i), strokeWidth)
        }
    }
}

/**
 * Horizon indicator: a fixed horizontal reference plus a line that rotates with the device roll.
 * Both turn green when the device is within ±1.5° of level.
 */
@Composable
private fun LevelOverlay(roll: Float, modifier: Modifier = Modifier) {
    val isLevel = kotlin.math.abs(roll) <= 1.5f
    val color = if (isLevel) Color(0xFF4CAF50) else Color.White
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.width * 0.18f
        val stroke = 2.dp.toPx()
        val gap = 10.dp.toPx()

        // Fixed reference ticks either side of centre.
        val refColor = Color.White.copy(alpha = 0.5f)
        drawLine(refColor, Offset(cx - half - gap, cy), Offset(cx - half, cy), stroke)
        drawLine(refColor, Offset(cx + half, cy), Offset(cx + half + gap, cy), stroke)

        // Rolling line rotated by -roll (screen rotates opposite to device tilt).
        val rad = Math.toRadians(-roll.toDouble())
        val dx = (half * kotlin.math.cos(rad)).toFloat()
        val dy = (half * kotlin.math.sin(rad)).toFloat()
        drawLine(color, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), stroke)
        drawCircle(color, 2.dp.toPx(), Offset(cx, cy))
    }
}

@Composable
private fun HorizontalSettingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: @Composable (Modifier, Color) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    activeWhen: (Float) -> Boolean = { it != 0f },
    displayValue: (Float) -> String = { if (it == 0f) "0" else "%+.1f".format(it) }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (activeWhen(value)) Color(0xFF3C3C3C) else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            icon(Modifier.size(20.dp), Color.White)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF666666)
            )
        )
        Text(
            text = displayValue(value),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun NightModeButton(
    active: Boolean,
    onClick: () -> Unit,
    iconRotation: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .selectedPill(active, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBedtime(Modifier.size(20.dp).rotate(iconRotation), if (active) Color.White else Color(0xFFBBBBBB))
        Text(
            text = stringResource(R.string.night),
            color = if (active) Color.White else Color(0xFFBBBBBB),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SettingsButtonRow(
    activeSetting: CameraSetting?,
    cameraMode: CameraMode,
    onSelect: (CameraSetting?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val settings = buildList<Pair<CameraSetting, @Composable (Modifier, Color) -> Unit>> {
            add(CameraSetting.BRIGHTNESS to { m, c -> IconSunny(m, c) })
            add(CameraSetting.SHADOWS to { m, c -> IconContrast(m, c) })
            add(CameraSetting.WARMTH to { m, c -> IconLightbulb(m, c) })
            add(CameraSetting.EXPOSURE_TIME to { m, c -> IconTimer(m, c) })
            if (cameraMode == CameraMode.PHOTO) {
                add(CameraSetting.ISO to { m, c -> IconIso(m, c) })
            }
            if (cameraMode == CameraMode.PORTRAIT) {
                add(CameraSetting.PORTRAIT_BLUR to { m, c -> IconBlur(m, c) })
            }
        }
        settings.forEach { (setting, icon) ->
            val isActive = activeSetting == setting
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isActive, CircleShape)
                    .clip(CircleShape)
                    .clickable { onSelect(if (isActive) null else setting) },
                contentAlignment = Alignment.Center
            ) {
                icon(Modifier.size(20.dp), if (isActive) Color.White else Color(0xFFBBBBBB))
            }
        }
    }
}

@Composable
private fun ZoomBar(
    currentZoom: Float,
    zoomLevels: List<Pair<String, Float>>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Insert the live zoom ratio between the two fixed levels it falls between,
    // unless it already matches one of the listed options.
    val displayLevels = remember(zoomLevels, currentZoom) {
        if (zoomLevels.any { kotlin.math.abs(it.second - currentZoom) < 0.05f }) {
            zoomLevels
        } else {
            val entry = formatZoomLabel(currentZoom) to currentZoom
            val insertAt = zoomLevels.indexOfFirst { it.second > currentZoom }
            if (insertAt < 0) zoomLevels + entry
            else zoomLevels.toMutableList().apply { add(insertAt, entry) }
        }
    }
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayLevels.forEach { (label, ratio) ->
            val isSelected = kotlin.math.abs(currentZoom - ratio) < 0.05f
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isSelected, CircleShape)
                    .clip(CircleShape)
                    .clickable { onZoomSelected(ratio) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ShutterRow(
    cameraMode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    recordingPaused: Boolean,
    videoSnapshotSupported: Boolean,
    galleryBitmap: android.graphics.Bitmap?,
    burstEnabled: Boolean,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
    onCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onSnapshot: () -> Unit,
    onFlipCamera: () -> Unit,
    onGallery: () -> Unit,
    iconRotation: Float,
    flipEnabled: Boolean = true
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 800f)
    )
    val recordingCornerRadius by animateFloatAsState(
        targetValue = if (isRecording) 8f else 38f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left slot: pause/resume while recording, otherwise the gallery thumbnail.
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF3C3C3C), CircleShape)
                    .clickable(onClick = onPauseResume),
                contentAlignment = Alignment.Center
            ) {
                if (recordingPaused) IconPlay(Modifier.size(24.dp).rotate(iconRotation), Color.White)
                else IconPause(Modifier.size(24.dp).rotate(iconRotation), Color.White)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3C3C3C))
                    .clickable(onClick = onGallery),
                contentAlignment = Alignment.Center
            ) {
                if (galleryBitmap != null) {
                    Image(
                        bitmap = galleryBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.open_gallery),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    IconPhotoLibrary(Modifier.size(24.dp), Color.White)
                }
            }
        }

        val animatedSize by animateFloatAsState(
            targetValue = if (isRecording) 36f else 66f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = shutterScale
                        scaleY = shutterScale
                    }
                    .size(animatedSize.dp)
                    .clip(RoundedCornerShape(recordingCornerRadius.dp))
                    .background(
                        if (cameraMode in listOf(CameraMode.VIDEO, CameraMode.SLOW_MO, CameraMode.TIMELAPSE, CameraMode.CINEMATIC)) Color.Red
                        else Color.White
                    )
                    .then(
                        if (burstEnabled) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onCapture() },
                                    onLongPress = { onBurstStart() },
                                    onPress = {
                                        tryAwaitRelease()
                                        // No-op if a burst was never started (short tap).
                                        onBurstStop()
                                    }
                                )
                            }
                        } else {
                            Modifier.clickable(onClick = onCapture)
                        }
                    )
            )
        }

        // Right slot: video snapshot while recording (if supported), otherwise flip camera.
        // No flip in Slo-Mo — back camera only.
        if (isRecording) {
            if (videoSnapshotSupported) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White, CircleShape)
                        .clickable(onClick = onSnapshot),
                    contentAlignment = Alignment.Center
                ) {
                    IconCamera(Modifier.size(24.dp).rotate(iconRotation), Color.Black)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        } else {
            if (flipEnabled) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3C3C3C), CircleShape)
                        .clickable(onClick = onFlipCamera),
                    contentAlignment = Alignment.Center
                ) {
                        IconFlipCamera(Modifier.size(24.dp).rotate(iconRotation), Color.White)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun ModeSelector(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    sloMoSupported: Boolean?,
    onModeSelected: (CameraMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modes = if (isPhotoType) {
            listOf(
                CameraMode.PORTRAIT to "Portrait",
                CameraMode.PHOTO to "Photo",
                CameraMode.PANORAMA to "Pano",
                CameraMode.PHOTOSPHERE to "Sphere"
            )
        } else {
            // Only include Slo-Mo when the device's back camera actually supports true HFR.
            buildList {
                if (sloMoSupported != false) {
                    add(CameraMode.SLOW_MO to "Slo-Mo")
                }
                add(CameraMode.VIDEO to "Video")
                add(CameraMode.CINEMATIC to "Cinematic")
                add(CameraMode.TIMELAPSE to "Timelapse")
            }
        }
        modes.forEach { (mode, label) ->
            val isSelected = cameraMode == mode
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .selectedPill(isSelected, RoundedCornerShape(20.dp))
                    .clickable { if (!isSelected) onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun BottomBar(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    iconRotation: Float,
    onPickerChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF3C3C3C), CircleShape)
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            IconSettings(
                tint = Color.White,
                modifier = Modifier.size(22.dp).rotate(iconRotation)
            )
        }

        Row(
            modifier = Modifier
                .background(Color(0xFF3C3C3C), RoundedCornerShape(20.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isPhotoType, CircleShape, Color(0xFF5C5C5C))
                    .clickable { if (!isPhotoType) onPickerChanged(true) },
                contentAlignment = Alignment.Center
            ) {
                IconCamera(Modifier.size(20.dp).rotate(iconRotation), Color.White)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(!isPhotoType, CircleShape, Color(0xFF5C5C5C))
                    .clickable { if (isPhotoType) onPickerChanged(false) },
                contentAlignment = Alignment.Center
            ) {
                IconVideoCamera(Modifier.size(20.dp).rotate(iconRotation), Color.White)
            }
        }

        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun QrResultOverlay(text: String, onDismiss: () -> Unit, context: Context, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.qr_result),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        // Passkey (FIDO hybrid/caBLE) QR codes decode to a "FIDO:/..." URI. Treat it as an
        // openable URL alongside normal web links. Intent-filter scheme matching is case-SENSITIVE,
        // so the "FIDO" scheme must be lowercased (normalizeScheme) or nothing will handle it.
        val isFidoUri = text.startsWith("FIDO:", ignoreCase = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isFidoUri || Patterns.WEB_URL.matcher(text).matches()) {
                Button(onClick = {
                    val url = if (!isFidoUri && !text.startsWith("http")) "https://$text" else text
                    val uri = url.toUri().normalizeScheme()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        AppMessages.show(context.getString(R.string.no_app_to_open_url))
                    }
                }) {
                    Text(stringResource(R.string.open_url))
                }
            }
            FilledTonalButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QR", text))
            }) {
                Text(stringResource(R.string.copy_text))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}

@Composable
private fun RecordingIndicator(durationSec: Long, modifier: Modifier = Modifier) {
    val dotAlpha by animateFloatAsState(
        targetValue = if ((durationSec % 2) == 0L) 1f else 0.3f,
        animationSpec = tween(500)
    )

    val minutes = durationSec / 60
    val seconds = durationSec % 60
    val timeText = "%d:%02d".format(minutes, seconds)

    Row(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(Color.Red, CircleShape)
        )
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LongExposureOverlay(
    progress: Float,
    remainingText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(80.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 4.dp
            )
            Text(
                text = remainingText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExposureTimeBar(
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val stops = CameraViewModel.EXPOSURE_TIME_STOPS
    val isManual = selectedIndex > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (isManual) Color(0xFF3C3C3C) else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            IconTimer(Modifier.size(20.dp), Color.White)
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { onIndexChange(it.roundToInt()) },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = stops.size - 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF666666)
            )
        )
        Text(
            text = stops[selectedIndex].label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End
        )
    }
}

/** Manual ISO: index 0 == Auto, 1..[isoStops].size select a sensitivity stop. */
@Composable
private fun IsoBar(
    selectedIndex: Int,
    isoStops: List<Int>,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isManual = selectedIndex > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (isManual) Color(0xFF3C3C3C) else Color.Transparent, CircleShape)
                .clip(CircleShape)
                .clickable { onIndexChange(0) },
            contentAlignment = Alignment.Center
        ) {
            IconIso(Modifier.size(20.dp), Color.White)
        }
        if (isoStops.isEmpty()) {
            Text(
                text = stringResource(R.string.not_available),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center
            )
        } else {
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { onIndexChange(it.roundToInt()) },
                valueRange = 0f..isoStops.size.toFloat(),
                steps = (isoStops.size - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF666666)
                )
            )
        }
        Text(
            text = if (selectedIndex == 0) stringResource(R.string.auto) else "${isoStops.getOrNull(selectedIndex - 1) ?: ""}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PanoramaOverlay(
    isSweeping: Boolean,
    isStitching: Boolean,
    guideDots: List<GuideDot>,
    currentAngle: Float,
    sweepDirection: Int,
    currentPitch: Float,
    modifier: Modifier = Modifier
) {
    if (isStitching) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(R.string.processing_panorama_u2026), color = Color.White, fontSize = 16.sp)
            }
        }
        return
    }

    if (!isSweeping) return

    val capturedCount = guideDots.count { it.state == GuideDotState.CAPTURED }
    val totalDots = guideDots.size
    val halfFOV = 30f

    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(capturedCount) {
        if (capturedCount > 1) {
            flashAlpha.snapTo(0.3f)
            flashAlpha.animateTo(0f, tween(250))
        }
    }

    Box(modifier.fillMaxSize()) {
        if (flashAlpha.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
        }

        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val halfVertFOV = 40f

            guideDots.forEach { dot ->
                val angleOffset = dot.targetAngle - currentAngle
                val pitchOffset = dot.targetPitch - currentPitch
                if (Math.abs(angleOffset) <= halfFOV * 1.2f && Math.abs(pitchOffset) <= halfVertFOV * 1.2f) {
                    val dotX = centerX + (angleOffset / halfFOV) * (size.width / 2)
                    val dotY = centerY - (pitchOffset / halfVertFOV) * (size.height / 2)
                    val baseRadius = 14.dp.toPx()

                    when (dot.state) {
                        GuideDotState.PENDING -> {
                            drawCircle(Color.White.copy(alpha = 0.7f), baseRadius, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                            drawCircle(Color.White.copy(alpha = 0.3f), 3.dp.toPx(), Offset(dotX, dotY))
                        }
                        GuideDotState.ALIGNING, GuideDotState.CAPTURING -> {
                            drawCircle(Color(0xFF4FC3F7), baseRadius * 1.2f, Offset(dotX, dotY))
                            drawCircle(Color.White, baseRadius * 1.2f, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                        }
                        GuideDotState.CAPTURED -> {
                            drawCircle(Color(0xFF4CAF50), baseRadius, Offset(dotX, dotY))
                            val checkPath = Path().apply {
                                moveTo(dotX - baseRadius * 0.35f, dotY)
                                lineTo(dotX - baseRadius * 0.05f, dotY + baseRadius * 0.3f)
                                lineTo(dotX + baseRadius * 0.4f, dotY - baseRadius * 0.3f)
                            }
                            drawPath(checkPath, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }

            val crossSize = 20.dp.toPx()
            val crossGap = 6.dp.toPx()
            val crossStroke = 2.dp.toPx()
            val crossColor = Color.White
            drawLine(crossColor, Offset(centerX - crossSize, centerY), Offset(centerX - crossGap, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX + crossGap, centerY), Offset(centerX + crossSize, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY - crossSize), Offset(centerX, centerY - crossGap), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY + crossGap), Offset(centerX, centerY + crossSize), crossStroke)
            drawCircle(crossColor, 2.dp.toPx(), Offset(centerX, centerY))
        }

        Text(
            "$capturedCount / $totalDots",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
