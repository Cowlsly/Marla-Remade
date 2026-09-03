@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.translate.R
import kotlin.concurrent.atomics.*
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlashOff
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.ui.Text
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.TranslateViewModel
import com.vayunmathur.translate.ui.components.LanguagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Minimum gap between the *end* of one OCR pass and the start of the next (ms). */
private const val ANALYSIS_INTERVAL_MS = 400L

/**
 * Resolution requested for the analysis stream, in sensor (landscape) orientation.
 * CameraX defaults ImageAnalysis to 640×480, which is far too coarse for PP-OCRv5 to
 * detect anything but very large text — this is the single biggest reason the camera
 * used to look like it "saw" nothing. 4:3 matches the preview stream's aspect ratio,
 * which the overlay mapping below relies on.
 */
private val ANALYSIS_SIZE = Size(1280, 960)

/** Upper bound on the translation memo; plenty for a screenful of lines. */
private const val TRANSLATION_CACHE_MAX = 128

/** Idle poll while every visible line already has a translation (ms). */
private const val TRANSLATE_IDLE_POLL_MS = 100L

/**
 * One detected line, as its oriented corners in the (upright) analysed-bitmap's
 * pixel space, in reading order (corner 0 -> 1 runs along the text, 0 -> 3 spans
 * its height). Holds the *source* text.
 */
private data class OverlayBox(
    val corners: List<Offset>,
    val text: String,
)

@Composable
fun CameraTranslateScreen(viewModel: TranslateViewModel, onBack: () -> Unit) {
    PermissionsChecker(
        permissions = arrayOf(Manifest.permission.CAMERA),
        text = stringResource(R.string.grant_camera_access),
    ) {
        CameraContent(viewModel, onBack)
    }
}

@Composable
private fun CameraContent(viewModel: TranslateViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    val previewView = remember {
        // The overlay maths below assumes a centre-crop preview; make that explicit
        // rather than relying on PreviewView's default.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // OCR results, in analysed-bitmap pixel coords, plus the translations for the
    // lines in them. Keeping the two apart lets OCR keep running at full speed while
    // the (much slower) translator fills results in behind it.
    var overlays by remember { mutableStateOf<List<OverlayBox>>(emptyList()) }
    var translations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }

    // Size of the preview area and the rotation it is being shown at. Both go into the
    // ViewPort that ties the two camera streams to one field of view, so a change to
    // either has to rebind rather than just retarget.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var boundRotation by remember { mutableStateOf(Surface.ROTATION_0) }

    // Read by the analysis-thread callback; kept fresh via rememberUpdatedState.
    val pausedState = rememberUpdatedState(paused)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val inFlight = remember { AtomicBoolean(false) }
    val lastMs = remember { AtomicLong(0L) }

    // Built once so the display-rotation listener and the dispose block can reach it.
    // Target rotation is pinned to ROTATION_0 here purely so [ANALYSIS_SIZE] is read in
    // sensor coords; the real rotation is applied after binding (see below).
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()
    }

    // `Context.getDisplay()` throws on a non-visual context, hence the guard.
    fun displayRotation(): Int {
        val display = previewView.display ?: runCatching { context.display }.getOrNull()
        return display?.rotation ?: Surface.ROTATION_0
    }

    // The activity handles `orientation` itself (see AndroidManifest configChanges), so
    // nothing recreates on rotation. Without this the analyser keeps reporting the
    // rotation from bind time and every box lands 90° out of place.
    DisposableEffect(analysis) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) {
                    // Rebind rather than just retarget the analyser: the ViewPort is
                    // built for a rotation, so retargeting alone would leave the two
                    // streams disagreeing about what they are looking at again.
                    boundRotation = displayRotation()
                }
            }
        }
        displayManager?.registerDisplayListener(listener, null)
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    // Release the camera when the screen goes away. Without this the torch stays lit,
    // the camera indicator stays up, and frames keep being handed to a dead executor.
    DisposableEffect(Unit) {
        onDispose {
            camera?.cameraControl?.enableTorch(false)
            analysis.clearAnalyzer()
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    // Keyed on `camera` too: the first pass runs before binding completes, when
    // there is no CameraControl to talk to yet.
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(viewSize, boundRotation) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        provider = cameraProvider
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (pausedState.value || inFlight.load() || now - lastMs.load() < ANALYSIS_INTERVAL_MS) {
                proxy.close()
                return@setAnalyzer
            }
            inFlight.store(true)
            val bmp = try {
                proxy.toBitmap()
            } catch (t: Throwable) {
                Log.e(TAG, "toBitmap failed", t)
                null
            }
            val rotation = proxy.imageInfo.rotationDegrees
            // The ViewPort's share of this buffer: exactly what the preview is showing.
            // Copied because the proxy's own Rect is recycled with it.
            val crop = Rect(proxy.cropRect)
            proxy.close()
            if (bmp == null) {
                inFlight.store(false)
                lastMs.store(System.currentTimeMillis())
                return@setAnalyzer
            }
            // Only the state writes belong on the main thread; the rotate is a
            // full-frame copy and OCR suspends onto Dispatchers.Default itself.
            scope.launch {
                try {
                    // Cropping to the viewport before OCR means the boxes come back in
                    // the coordinates of the region the user can actually see, and OCR
                    // stops spending its time on the strips either side that the preview
                    // never shows.
                    val upright = withContext(Dispatchers.Default) {
                        cropAndRotate(bmp, crop, rotation).also { if (it !== bmp) bmp.recycle() }
                    }
                    val result = viewModel.ocr.recognizeDetailed(upright)
                    frameW = upright.width
                    frameH = upright.height
                    overlays = result.boxes.map { box ->
                        OverlayBox(box.corners.map { Offset(it.x, it.y) }, box.text)
                    }
                    frozenFrame = upright
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame analysis failed", t)
                } finally {
                    // Measure the gap from the *end* of the pass, so a slow OCR run
                    // doesn't immediately trigger the next one.
                    lastMs.store(System.currentTimeMillis())
                    inFlight.store(false)
                }
            }
        }

        try {
            cameraProvider.unbindAll()
            // A ViewPort is what makes the preview and the analysis stream agree on a
            // field of view. Bound as two loose use cases they each got their own crop
            // rect off a different resolution (1600×1200 vs 1280×960) — which is why the
            // frozen still was framed differently from the live preview, and why every
            // OCR box landed somewhere the text wasn't. With one, CameraX crops both to
            // the same region and hands us that region as `ImageProxy.cropRect`.
            val viewPort = ViewPort
                .Builder(Rational(viewSize.width, viewSize.height), boundRotation)
                .setScaleType(ViewPort.FILL_CENTER) // matches PreviewView's scale type
                .build()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build(),
            )
            // Safe (and required) only after binding — setting it on the builder would
            // change the frame [ANALYSIS_SIZE] is resolved in.
            analysis.targetRotation = boundRotation
        } catch (t: Throwable) {
            Log.e(TAG, "Camera bind failed", t)
        }
    }

    // One background translator, decoupled from the OCR loop. NLLB takes on the
    // order of a second per line, so translating every line of every frame inline (as
    // this screen used to) meant the overlay updated once every several seconds, always
    // against a frame the camera had long since moved off. Instead we memo per source
    // line: repeat lines are free, and OCR keeps redrawing boxes at full rate.
    LaunchedEffect(targetLang, translationAvailable) {
        translations = emptyMap()
        if (!translationAvailable) return@LaunchedEffect
        while (isActive) {
            val next = overlays.firstOrNull { it.text !in translations }
            if (next == null) {
                delay(TRANSLATE_IDLE_POLL_MS)
                continue
            }
            // Memo failures as the source text so a line that can't be translated
            // isn't retried on every frame forever.
            val translated = viewModel.translate(next.text) ?: next.text
            translations = (translations + (next.text to translated)).let { updated ->
                if (updated.size <= TRANSLATION_CACHE_MAX) {
                    updated
                } else {
                    updated.entries.drop(updated.size - TRANSLATION_CACHE_MAX)
                        .associate { it.key to it.value }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewSize = it
                    boundRotation = displayRotation()
                },
        ) {
            // The preview stays composed even while paused — swapping it out tears the
            // camera surface down and back up, which flashes black on every toggle.
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            val frozen = frozenFrame
            if (paused && frozen != null && !frozen.isRecycled) {
                Image(
                    bitmap = frozen.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // Already cropped to the viewport, so it covers the preview area
                    // exactly — there is no second crop left to get wrong.
                    contentScale = ContentScale.FillBounds,
                )
            }

            // The analysed frame *is* the visible region now, so placing a box is a
            // straight stretch — no centre-crop arithmetic that can disagree with what
            // PreviewView chose to show.
            if (frameW > 0 && frameH > 0 && viewSize.width > 0 && viewSize.height > 0) {
                val sx = viewSize.width.toFloat() / frameW
                val sy = viewSize.height.toFloat() / frameH
                overlays.forEach { ob ->
                    // Until a line's translation lands, leave the original text visible
                    // rather than covering it with a blank plate — a screenful of black
                    // boxes over text nobody can read was most of why this looked broken.
                    // With no model installed none will ever land, so show what was read.
                    val label = translations[ob.text]
                        ?: if (translationAvailable) return@forEach else ob.text
                    // sx and sy are independent, so the on-screen angle has to be
                    // re-derived from the *mapped* top edge; a bitmap-space angle
                    // would be wrong under an anisotropic stretch.
                    val p = ob.corners.map { Offset(it.x * sx, it.y * sy) }
                    val runX = p[1].x - p[0].x
                    val runY = p[1].y - p[0].y
                    val w = hypot(runX, runY)
                    val h = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
                    if (w <= 0f || h <= 0f) return@forEach
                    val angle = (atan2(runY, runX) * 180f / PI).toFloat()
                    Box(
                        modifier = Modifier
                            // Anchored at the start of the run and turned onto it,
                            // so a slanted line gets a slanted plate instead of an
                            // opaque one the size of its bounding box covering its
                            // neighbours.
                            .offset { IntOffset(p[0].x.roundToInt(), p[0].y.roundToInt()) }
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                rotationZ = angle
                            }
                            .size(
                                width = with(density) { w.toDp() },
                                height = with(density) { h.toDp() },
                            )
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xF2000000))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A translation is usually longer than the source line it has to
                        // sit on top of, so a fixed font size ellipsised nearly every
                        // label down to "…". Shrink to fit instead. One line only: the
                        // plate is exactly as tall as the line it covers, so allowing two
                        // just halved the font and spilled out of the box.
                        BasicText(
                            text = label,
                            style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = 24.sp,
                                stepSize = 0.5.sp,
                            ),
                        )
                    }
                }
            }
        }

        // Back button (top-left). The preview runs edge-to-edge behind the system bars,
        // so every control on top of it has to inset itself out from under them.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(24.dp)),
        ) {
            IconBack(tint = Color.White)
        }

        if (!translationAvailable) {
            Text(
                text = stringResource(R.string.translation_model_not_installed_showing),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Bottom controls: language pickers + pause/resume + torch. Inset above the
        // gesture bar — sitting in it meant a tap on pause was liable to be swallowed by
        // system navigation instead.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color(0xB3000000), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LanguagePicker(
                selectedCode = sourceLang,
                options = Languages.SOURCES,
                onSelected = viewModel::setSource,
                modifier = Modifier.width(120.dp),
            )
            IconButton(onClick = { paused = !paused }) {
                if (paused) IconPlay(tint = Color.White) else IconPause(tint = Color.White)
            }
            IconButton(onClick = { torchOn = !torchOn }) {
                if (torchOn) IconFlashOn(tint = Color.White) else IconFlashOff(tint = Color.White)
            }
            LanguagePicker(
                selectedCode = targetLang,
                options = Languages.TARGETS,
                onSelected = viewModel::setTarget,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

/**
 * Take [crop] out of [src] and rotate it by [degrees], so OCR sees an upright image of
 * just the region the preview is showing. May return [src] itself when there is nothing
 * to do, so callers must compare identities before recycling.
 */
private fun cropAndRotate(src: Bitmap, crop: Rect, degrees: Int): Bitmap {
    var left = crop.left.coerceIn(0, src.width)
    var top = crop.top.coerceIn(0, src.height)
    var width = crop.width().coerceIn(0, src.width - left)
    var height = crop.height().coerceIn(0, src.height - top)
    // An unresolved viewport gives an empty rect; fall back to the whole frame.
    if (width <= 0 || height <= 0) {
        left = 0
        top = 0
        width = src.width
        height = src.height
    }
    if (width <= 0 || height <= 0) return src
    val matrix = if (degrees % 360 == 0) null else Matrix().apply { postRotate(degrees.toFloat()) }
    val whole = left == 0 && top == 0 && width == src.width && height == src.height
    if (matrix == null && whole) return src
    return Bitmap.createBitmap(src, left, top, width, height, matrix, true)
}

private const val TAG = "CameraTranslate"
