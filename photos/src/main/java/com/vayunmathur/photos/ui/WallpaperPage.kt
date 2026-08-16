package com.vayunmathur.photos.ui

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Rect
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.WallpaperLoadState
import com.vayunmathur.photos.util.WallpaperUtil
import com.vayunmathur.photos.util.WallpaperViewModel
import com.vayunmathur.photos.util.WallpaperViewModelFactory
import kotlinx.coroutines.launch

private data class PreviewSnapshot(
    val containerW: Float,
    val containerH: Float,
    val baseW: Float,
    val baseH: Float,
    val viewportW: Float,
    val viewportH: Float,
)

private val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) },
)

@Composable
fun WallpaperPage(
    backStack: NavBackStack<Route>,
    id: Long,
    uri: String?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val wallpaperSetSuccessMsg = stringResource(R.string.wallpaper_set_success)
    val app = context.applicationContext as Application
    val viewModel: WallpaperViewModel = viewModel(factory = WallpaperViewModelFactory(app))

    val bitmap by viewModel.bitmap.collectAsState()
    val loadState by viewModel.loadState.collectAsState()

    // Accurate window size on minSdk 31+ via WindowMetrics — recomputes on config change.
    val localConfig = LocalConfiguration.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    val (screenW, screenH) = remember(localConfig, windowManager) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    }

    val wm = remember(context) { WallpaperManager.getInstance(context) }
    // Unified scrollable width: max(desiredMinimumWidth, screenW*2) — single source of truth.
    val (scrollableW, scrollableH) = remember(wm, screenW, screenH) {
        val baseW = wm.desiredMinimumWidth
        val baseH = wm.desiredMinimumHeight
        val sW = maxOf(baseW.takeIf { it > 0 } ?: 0, screenW * 2).coerceAtLeast(screenW)
        val sH = (baseH.takeIf { it > 0 } ?: screenH).coerceAtLeast(screenH)
        sW to sH
    }

    var isScrollable by rememberSaveable { mutableStateOf(false) }
    var which by rememberSaveable { mutableStateOf(WallpaperManager.FLAG_SYSTEM) }
    var isSetting by rememberSaveable { mutableStateOf(false) }
    var zoomScale by rememberSaveable { mutableFloatStateOf(1f) }
    var zoomOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    // Keep latest offset/scale fresh for gesture lambda without capturing stale values.
    val zoomScaleUpdated by rememberUpdatedState(zoomScale)
    val zoomOffsetUpdated by rememberUpdatedState(zoomOffset)

    val targetW = if (isScrollable) scrollableW else screenW
    val targetH = if (isScrollable) scrollableH else screenH

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Composition-measured snapshot — updated via SideEffect so Set button always reads current layout
    // without LaunchedEffect lag (fixes rotation stale bug). SideEffect runs after commit.
    var latestPreview by remember { mutableStateOf<PreviewSnapshot?>(null) }
    val latestPreviewRef by rememberUpdatedState(latestPreview)

    LaunchedEffect(uri) {
        val uriToLoad = uri ?: return@LaunchedEffect
        viewModel.load(uriToLoad)
    }

    // RAW SCAFFOLD EXCEPTION: immersive black wallpaper preview with a transparent,
    // white-tinted TopAppBar (title + back). AppScaffold exposes no top-bar color slot, so
    // the transparent/white immersive bar cannot be preserved.
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_as_wallpaper), color = Color.White) },
                navigationIcon = { IconNavigation(backStack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ----- preview: Lavender-style dim outside viewport ----------------
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val containerW = constraints.maxWidth.toFloat()
                val containerH = constraints.maxHeight.toFloat()

                when {
                    loadState == WallpaperLoadState.Loading ||
                        (bitmap == null && loadState == WallpaperLoadState.Idle) -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                        )
                    }
                    loadState == WallpaperLoadState.Failed || bitmap == null -> {
                        Text(
                            stringResource(R.string.wallpaper_load_failed),
                            color = Color.White,
                        )
                    }
                    else -> {
                        val bmp = bitmap!!

                        val fitScale = minOf(
                            containerW / bmp.width.toFloat(),
                            containerH / bmp.height.toFloat(),
                        ).coerceAtLeast(0.01f)
                        val baseDisplayW = bmp.width * fitScale
                        val baseDisplayH = bmp.height * fitScale

                        val targetAspect = if (targetW > 0 && targetH > 0) {
                            targetW.toFloat() / targetH.toFloat()
                        } else 9f / 16f

                        val (viewportW, viewportH) = run {
                            val maxW = containerW * 0.90f
                            val maxH = containerH * 0.90f
                            var vw = maxH * targetAspect
                            var vh = maxH
                            if (vw > maxW) {
                                vw = maxW
                                vh = maxW / targetAspect
                            }
                            vw to vh
                        }

                        val coverMin = WallpaperUtil.coverMinScale(
                            baseDisplayW = baseDisplayW,
                            baseDisplayH = baseDisplayH,
                            viewportW = viewportW,
                            viewportH = viewportH,
                        )

                        // Push snapshot synchronously after composition committed (SideEffect) so rotation is not stale.
                        SideEffect {
                            latestPreview = PreviewSnapshot(
                                containerW = containerW,
                                containerH = containerH,
                                baseW = baseDisplayW,
                                baseH = baseDisplayH,
                                viewportW = viewportW,
                                viewportH = viewportH,
                            )
                        }

                        LaunchedEffect(coverMin) {
                            if (zoomScale < coverMin) {
                                zoomScale = coverMin
                                zoomOffset = Offset.Zero
                            }
                        }

                        fun clampOffset(off: Offset, scale: Float): Offset {
                            val effW = baseDisplayW * scale
                            val effH = baseDisplayH * scale
                            val maxX = kotlin.math.max(0f, (effW - viewportW) / 2f)
                            val maxY = kotlin.math.max(0f, (effH - viewportH) / 2f)
                            return Offset(
                                x = off.x.coerceIn(-maxX, maxX),
                                y = off.y.coerceIn(-maxY, maxY),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(baseDisplayW, baseDisplayH, viewportW, viewportH, coverMin) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent()
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()

                                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                                val newScale = (zoomScaleUpdated * zoomChange)
                                                    .coerceIn(coverMin, 5f)
                                                val newOffset = clampOffset(
                                                    zoomOffsetUpdated + panChange,
                                                    newScale,
                                                )
                                                zoomScale = newScale
                                                zoomOffset = newOffset
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(
                                        with(density) { baseDisplayW.toDp() },
                                        with(density) { baseDisplayH.toDp() },
                                    )
                                    .graphicsLayer {
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                        translationX = zoomOffset.x
                                        translationY = zoomOffset.y
                                    },
                                contentScale = ContentScale.FillBounds,
                            )

                            // Lavender-style: dim scrim outside viewport (4-rect hole)
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cw = size.width
                                val ch = size.height
                                val vpLeft = (cw - viewportW) / 2f
                                val vpTop = (ch - viewportH) / 2f
                                val dim = Color.Black.copy(alpha = 0.60f)

                                // Top
                                drawRect(dim, topLeft = Offset(0f, 0f), size = ComposeSize(cw, vpTop))
                                // Bottom
                                drawRect(
                                    dim,
                                    topLeft = Offset(0f, vpTop + viewportH),
                                    size = ComposeSize(cw, ch - (vpTop + viewportH)),
                                )
                                // Left
                                drawRect(dim, topLeft = Offset(0f, vpTop), size = ComposeSize(vpLeft, viewportH))
                                // Right
                                drawRect(
                                    dim,
                                    topLeft = Offset(vpLeft + viewportW, vpTop),
                                    size = ComposeSize(cw - (vpLeft + viewportW), viewportH),
                                )
                            }

                            // Phone-shaped viewport border — rounded 28dp, white 60% (Lavender polish)
                            Box(
                                modifier = Modifier
                                    .size(
                                        with(density) { viewportW.toDp() },
                                        with(density) { viewportH.toDp() },
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(28.dp),
                                    ),
                            )
                        }
                    }
                }
            }

            // ----- bottom controls -----------------------------------------
            Surface(
                color = Color(0xFF121212),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // minSdk 31 — always show Home/Lock/Both
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SegmentedButton(
                            selected = which == WallpaperManager.FLAG_SYSTEM,
                            onClick = { which = WallpaperManager.FLAG_SYSTEM },
                            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                            label = { Text(stringResource(R.string.wallpaper_home)) },
                        )
                        SegmentedButton(
                            selected = which == WallpaperManager.FLAG_LOCK,
                            onClick = { which = WallpaperManager.FLAG_LOCK },
                            shape = RoundedCornerShape(0.dp),
                            label = { Text(stringResource(R.string.wallpaper_lock)) },
                        )
                        SegmentedButton(
                            selected = which ==
                                (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                            onClick = {
                                which = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                            },
                            shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                            label = { Text(stringResource(R.string.wallpaper_both)) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.wallpaper_scrollable),
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (isScrollable) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.wallpaper_scrollable_desc),
                                    color = Color.White.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = isScrollable,
                            onCheckedChange = { checked ->
                                isScrollable = checked
                                zoomOffset = Offset.Zero
                            },
                        )
                    }

                    val currentBmp = bitmap
                    FilledTonalButton(
                        onClick = {
                            val bmpForSet = currentBmp ?: return@FilledTonalButton
                            if (isSetting) return@FilledTonalButton
                            val snapshot = latestPreviewRef ?: return@FilledTonalButton
                            if (snapshot.viewportW <= 0f) return@FilledTonalButton
                            isSetting = true

                            val zoomAtClick = zoomScale
                            val offsetAtClick = zoomOffset

                            scope.launch {
                                try {
                                    val cropRect: Rect = WallpaperUtil.computeCropRect(
                                        srcW = bmpForSet.width,
                                        srcH = bmpForSet.height,
                                        baseDisplayW = snapshot.baseW,
                                        baseDisplayH = snapshot.baseH,
                                        zoom = zoomAtClick,
                                        offsetX = offsetAtClick.x,
                                        offsetY = offsetAtClick.y,
                                        viewportW = snapshot.viewportW,
                                        viewportH = snapshot.viewportH,
                                        containerW = snapshot.containerW,
                                        containerH = snapshot.containerH,
                                    )

                                    val result = WallpaperUtil.setWallpaper(
                                        context = context,
                                        source = bmpForSet,
                                        viewport = cropRect,
                                        targetWidth = targetW,
                                        targetHeight = targetH,
                                        which = which,
                                        isScrollable = isScrollable,
                                        screenW = screenW,
                                        screenH = screenH,
                                    )

                                    when (result) {
                                        is WallpaperUtil.SetResult.Success -> {
                                            snackbarHostState.showSnackbar(
                                                wallpaperSetSuccessMsg,
                                            )
                                            backStack.pop()
                                        }
                                        is WallpaperUtil.SetResult.Failure -> {
                                            val msg = resources.getString(
                                                R.string.wallpaper_set_failed,
                                                result.exception.message ?: "Unknown",
                                            )
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    }
                                } finally {
                                    isSetting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = currentBmp != null &&
                            !isSetting &&
                            loadState == WallpaperLoadState.Loaded &&
                            latestPreview != null,
                    ) {
                        if (isSetting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.setting_wallpaper))
                        } else {
                            Text(stringResource(R.string.set_wallpaper))
                        }
                    }
                }
            }
        }
    }
}
