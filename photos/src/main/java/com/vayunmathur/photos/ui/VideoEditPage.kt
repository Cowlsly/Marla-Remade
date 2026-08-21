package com.vayunmathur.photos.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContentCut
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconFlip
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconTune
import com.vayunmathur.library.ui.IconVolumeOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.RangeSlider
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.data.VideoTool
import com.vayunmathur.photos.util.VideoEditViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
fun VideoEditPage(vm: VideoEditViewModel, id: Long, uri: String?) {
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(id, uri) { vm.loadVideo(id, uri) }

    val photo by vm.photo.collectAsState()
    val state by vm.state.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val progress by vm.progress.collectAsState()

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    val writePermissionRequest by vm.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let {
            writePermissionLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    var selectedTool by remember { mutableStateOf(VideoTool.Trim) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val currentPhoto = photo
    if (currentPhoto == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    AppScaffold(
        title = { Text(stringResource(R.string.title_edit_video), maxLines = 1) },
        onNavigateBack = { activity?.finish() },
        actions = {
            IconButton(onClick = { showSaveDialog = true }, enabled = !exporting) { IconSave() }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                VideoEditPreview(
                    vm = vm,
                    state = state,
                    uri = currentPhoto.uri.toUri(),
                    showCropOverlay = selectedTool == VideoTool.CropRotate,
                )
            }

            ToolPanel(vm = vm, state = state, tool = selectedTool)

            ToolTabs(selected = selectedTool, onSelect = { selectedTool = it })
        }
    }

    if (showSaveDialog) {
        SaveDialog(
            onDismiss = { showSaveDialog = false },
            onSaveCopy = {
                showSaveDialog = false
                vm.export(context, currentPhoto, asCopy = true) { activity?.finish() }
            },
            onOverwrite = {
                showSaveDialog = false
                vm.export(context, currentPhoto, asCopy = false) { activity?.finish() }
            },
        )
    }

    if (exporting) {
        ExportProgressDialog(progress = progress, onCancel = { vm.cancelExport() })
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditPreview(
    vm: VideoEditViewModel,
    state: VideoEditState,
    uri: android.net.Uri,
    showCropOverlay: Boolean,
) {
    val context = LocalContext.current
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember { mutableStateOf(true) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Report duration up to the view model once known.
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            val d = exoPlayer.duration
            if (d > 0) {
                vm.setDuration(d)
                break
            }
            delay(150)
        }
    }

    // Live crop/rotate/filter effects.
    LaunchedEffect(
        state.rotationDegrees, state.flipHorizontal,
        state.cropLeft, state.cropTop, state.cropRight, state.cropBottom,
        state.brightness, state.contrast, state.saturation, state.filterPreset,
    ) {
        exoPlayer.setVideoEffects(vm.buildVideoEffects(state))
    }

    // Trim preview: re-clip the source when the committed range changes.
    LaunchedEffect(state.trimStartMs, state.trimEndMs, state.durationMs) {
        if (state.durationMs <= 0L) return@LaunchedEffect
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.trimStartMs)
                    .setEndPositionMs(if (state.trimEndMs > 0L) state.trimEndMs else state.durationMs)
                    .build()
            )
            .build()
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    // Mute.
    LaunchedEffect(state.muted) {
        exoPlayer.volume = if (state.muted) 0f else 1f
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(videoAspectRatio),
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(
                player = exoPlayer,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
            if (showCropOverlay) {
                CropOverlay(
                    state = state,
                    onCrop = { r ->
                        if (r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f) {
                            vm.clearCrop()
                        } else {
                            vm.setCrop(r.left, r.top, r.right, r.bottom)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
            if (isPlaying) {
                IconPause(modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.85f))
            } else {
                IconPlayCircle(modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun CropOverlay(
    state: VideoEditState,
    onCrop: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var rect by remember {
        mutableStateOf(
            Rect(
                state.cropLeft ?: 0f, state.cropTop ?: 0f,
                state.cropRight ?: 1f, state.cropBottom ?: 1f,
            )
        )
    }
    var active by remember { mutableIntStateOf(-1) }
    val minSize = 0.15f

    Box(
        modifier = modifier
            .onGloballyPositioned { boxSize = it.size }
            .pointerInput(boxSize) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val corners = listOf(
                            Offset(rect.left * w, rect.top * h),
                            Offset(rect.right * w, rect.top * h),
                            Offset(rect.left * w, rect.bottom * h),
                            Offset(rect.right * w, rect.bottom * h),
                        )
                        active = corners.indexOfFirst { (it - pos).getDistance() < 100f }
                    },
                    onDrag = { change, drag ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val dx = drag.x / w
                        val dy = drag.y / h
                        rect = when (active) {
                            0 -> Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - minSize),
                                (rect.top + dy).coerceIn(0f, rect.bottom - minSize),
                                rect.right, rect.bottom,
                            )
                            1 -> Rect(
                                rect.left,
                                (rect.top + dy).coerceIn(0f, rect.bottom - minSize),
                                (rect.right + dx).coerceIn(rect.left + minSize, 1f),
                                rect.bottom,
                            )
                            2 -> Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - minSize),
                                rect.top,
                                rect.right,
                                (rect.bottom + dy).coerceIn(rect.top + minSize, 1f),
                            )
                            else -> Rect(
                                rect.left,
                                rect.top,
                                (rect.right + dx).coerceIn(rect.left + minSize, 1f),
                                (rect.bottom + dy).coerceIn(rect.top + minSize, 1f),
                            )
                        }
                    },
                    onDragEnd = { active = -1; onCrop(rect) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val l = rect.left * w
            val t = rect.top * h
            val r = rect.right * w
            val b = rect.bottom * h
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                style = Stroke(width = 3.dp.toPx()),
            )
            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
                drawCircle(Color.White, radius = 8.dp.toPx(), center = it)
            }
        }
    }
}

@Composable
private fun ToolPanel(vm: VideoEditViewModel, state: VideoEditState, tool: VideoTool) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF121212))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when (tool) {
            VideoTool.Trim -> TrimPanel(vm, state)
            VideoTool.CropRotate -> CropRotatePanel(vm, state)
            VideoTool.Audio -> AudioPanel(vm, state)
            VideoTool.Filters -> FiltersPanel(vm, state)
        }
    }
}

@Composable
private fun TrimPanel(vm: VideoEditViewModel, state: VideoEditState) {
    val duration = state.durationMs.coerceAtLeast(1L)
    var range by remember(state.durationMs) {
        mutableStateOf(
            state.trimStartMs.toFloat()..(if (state.trimEndMs > 0L) state.trimEndMs else duration).toFloat()
        )
    }
    Text(stringResource(R.string.video_trim), color = Color.White, style = MaterialTheme.typography.titleSmall)
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        onValueChangeFinished = { vm.setTrim(range.start.toLong(), range.endInclusive.toLong()) },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatVideoTime(range.start.toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(formatVideoTime(range.endInclusive.toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CropRotatePanel(vm: VideoEditViewModel, state: VideoEditState) {
    Text(stringResource(R.string.video_crop_rotate), color = Color.White, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.rotateLeft() }) { IconRotateLeft(tint = Color.White) }
        IconButton(onClick = { vm.rotateRight() }) { IconRotateRight(tint = Color.White) }
        IconButton(onClick = { vm.toggleFlip() }) {
            IconFlip(tint = if (state.flipHorizontal) MaterialTheme.colorScheme.primary else Color.White)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.clearCrop() }) { Text(stringResource(R.string.reset)) }
    }
    Text(
        stringResource(R.string.video_crop_hint),
        color = Color.LightGray,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun AudioPanel(vm: VideoEditViewModel, state: VideoEditState) {
    Text(stringResource(R.string.video_audio), color = Color.White, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = { vm.setMuted(!state.muted) }) {
        if (state.muted) IconVolumeOff() else IconVolumeUp()
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (state.muted) R.string.video_muted else R.string.video_audio_on))
    }
}

@Composable
private fun FiltersPanel(vm: VideoEditViewModel, state: VideoEditState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoFilterPreset.entries.forEach { preset ->
            val selected = state.filterPreset == preset
            if (selected) {
                Button(onClick = { vm.setFilterPreset(preset) }) { Text(presetLabel(preset)) }
            } else {
                OutlinedButton(onClick = { vm.setFilterPreset(preset) }) { Text(presetLabel(preset)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    LabeledSlider(stringResource(R.string.video_brightness), state.brightness) { vm.setBrightness(it) }
    LabeledSlider(stringResource(R.string.video_contrast), state.contrast) { vm.setContrast(it) }
    LabeledSlider(stringResource(R.string.video_saturation), state.saturation) { vm.setSaturation(it) }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun presetLabel(preset: VideoFilterPreset): String = when (preset) {
    VideoFilterPreset.None -> stringResource(R.string.video_filter_none)
    VideoFilterPreset.Mono -> stringResource(R.string.video_filter_mono)
    VideoFilterPreset.Warm -> stringResource(R.string.video_filter_warm)
    VideoFilterPreset.Cool -> stringResource(R.string.video_filter_cool)
    VideoFilterPreset.Vivid -> stringResource(R.string.video_filter_vivid)
}

@Composable
private fun ToolTabs(selected: VideoTool, onSelect: (VideoTool) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolTab(VideoTool.Trim, selected, onSelect, stringResource(R.string.video_trim)) {
            IconContentCut(tint = it)
        }
        ToolTab(VideoTool.CropRotate, selected, onSelect, stringResource(R.string.video_crop_rotate)) {
            IconCrop(tint = it)
        }
        ToolTab(VideoTool.Audio, selected, onSelect, stringResource(R.string.video_audio)) {
            IconVolumeUp(tint = it)
        }
        ToolTab(VideoTool.Filters, selected, onSelect, stringResource(R.string.video_filters)) {
            IconTune(tint = it)
        }
    }
}

@Composable
private fun ToolTab(
    tool: VideoTool,
    selected: VideoTool,
    onSelect: (VideoTool) -> Unit,
    label: String,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (tool == selected) MaterialTheme.colorScheme.primary else Color.White
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = { onSelect(tool) }) { icon(tint) }
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SaveDialog(
    onDismiss: () -> Unit,
    onSaveCopy: () -> Unit,
    onOverwrite: () -> Unit,
) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_save)) },
        text = { Text(stringResource(R.string.video_save_prompt)) },
        confirmButton = {
            TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.video_save_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onOverwrite) { Text(stringResource(R.string.video_overwrite)) }
        },
    )
}

@Composable
private fun ExportProgressDialog(progress: Float, onCancel: () -> Unit) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.video_exporting)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress > 0f) {
                    LoadingIndicator(progress = { progress })
                } else {
                    LoadingIndicator()
                }
                Spacer(Modifier.width(16.dp))
                Text("${(progress * 100).toInt()}%")
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) }
        },
    )
}

/** Formats a video position/duration in milliseconds as m:ss (or h:mm:ss past an hour). */
private fun formatVideoTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
