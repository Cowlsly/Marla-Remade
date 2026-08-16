package com.vayunmathur.photos.ui

import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.DateString
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.vayunmathur.library.image.compose.AnimatedImage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconWallpaper
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.LiveWallpaperLauncher
import com.vayunmathur.photos.util.PhotoMapViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.absoluteValue
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged

// Helper class to store zoom information
data class ZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero)

@Composable
fun PhotoPage(galleryViewModel: GalleryViewModel, photoMapViewModel: PhotoMapViewModel, id: Long, overridePhotosList: List<Photo>?, pendingUri: String? = null, backStack: NavBackStack<Route>? = null) {
    val photosAll by galleryViewModel.photos.collectAsState()
    val photos = overridePhotosList ?: photosAll.filter { !it.isTrashed }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Photos deleted from within this viewer, hidden immediately so the pager
    // advances to the next photo without waiting for the MediaStore resync.
    val locallyDeleted = remember { mutableStateListOf<Long>() }
    val photosSorted = remember(photos, locallyDeleted.toList()) {
        photos.asSequence()
            .filter { it.id !in locallyDeleted }
            .sortedByDescending { it.date }
            .toList()
    }
    val matchedCounts by galleryViewModel.faceCountByPhoto.collectAsState()

    // In-viewer delete: move the current photo to the system trash via the same
    // MediaStore IntentSender flow the grid uses. MANAGE_MEDIA (enforced at app
    // start) means no per-item confirmation popup. On success we hide it locally
    // (the pager falls through to the next photo) and trash it in the DB.
    var pendingDelete by remember { mutableStateOf<Photo?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDelete?.let { p ->
                locallyDeleted.add(p.id)
                galleryViewModel.trashPhotoLocally(p)
            }
            galleryViewModel.runSync()
        }
        pendingDelete = null
    }
    val onDeletePhoto: (Photo) -> Unit = { p ->
        pendingDelete = p
        val pendingIntent = MediaStore.createTrashRequest(
            context.contentResolver, listOf(p.uri.toUri()), true
        )
        deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    // Resolve the page to open by id, falling back to the incoming view-intent
    // URI. A freshly received photo may not be indexed into the DB yet, so it
    // won't be in the list until the background sync writes its row.
    val initialIndex =
            remember(photosSorted, id, pendingUri) {
                var index = photosSorted.indexOfFirst { it.id == id }
                if (index == -1 && pendingUri != null) {
                    index = photosSorted.indexOfFirst { it.uri == pendingUri }
                }
                index
            }

    // Once the pager is showing, stay in it even if the originally-opened photo
    // leaves the list (e.g. the user just deleted it) — otherwise deleting the
    // current photo would blank the screen instead of advancing to the next.
    var hasEntered by remember { mutableStateOf(false) }
    if (initialIndex != -1) hasEntered = true

    // Not in the library yet: show the incoming image directly so the viewer
    // opens instantly. Once indexing adds the row, this recomposes into the
    // swipeable pager below (initialIndex becomes valid).
    if (!hasEntered) {
        if (pendingUri != null) {
            PendingPhotoView(uri = pendingUri, context = context)
        }
        return
    }

    // Deleting the last remaining photo empties the list — leave the viewer.
    LaunchedEffect(photosSorted.isEmpty()) {
        if (photosSorted.isEmpty()) backStack?.pop()
    }

    var isMetadataVisible by remember { mutableStateOf(true) }

    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Persist zoom states in a map that survives as long as this screen is active
    val zoomStates = remember { mutableStateMapOf<Long, ZoomState>() }

    if (photosSorted.isNotEmpty()) {
        val pagerState =
                rememberPagerState(initialPage = initialIndex.coerceAtLeast(0), pageCount = { photosSorted.size })

        // RAW SCAFFOLD EXCEPTION: bar-less full-screen black media viewer (no top/bottom
        // bar); controls are a bottom overlay inside the pager. AppScaffold always renders a
        // top app bar, which would break the immersive viewer.
        Scaffold(containerColor = Color.Black) { paddingValues ->
            HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = true
            ) { pageIndex ->
                val photo = photosSorted[pageIndex]
                val zoomState = zoomStates[photo.id] ?: ZoomState()

                PhotoDetailView(
                        photo = photo,
                        context = context,
                        photoMapViewModel = photoMapViewModel,
                        pagerState = pagerState,
                        pageIndex = pageIndex,
                        isSettled = pagerState.settledPage == pageIndex,
                        isMetadataVisible = isMetadataVisible,
                        currentZoom = zoomState,
                        peopleCount = matchedCounts[photo.id] ?: 0,
                        onZoomUpdate = { newState -> zoomStates[photo.id] = newState },
                        onToggleMetadata = { isMetadataVisible = !isMetadataVisible },
                        refreshKey = refreshKey,
                        onEditPhoto = {
                            val activityClass =
                                    if (photo.videoData != null) VideoEditActivity::class.java
                                    else EditActivity::class.java
                            val intent =
                                    Intent(context, activityClass).apply {
                                        putExtra("photo_id", photo.id)
                                    }
                            context.startActivity(intent)
                        },
                        onSetWallpaper = { p ->
                            if (p.videoData != null || p.isGif) {
                                coroutineScope.launch {
                                    LiveWallpaperLauncher.apply(context, p.uri, p.videoData != null)
                                }
                            } else {
                                backStack?.add(Route.Wallpaper(p.id, p.uri))
                            }
                        },
                        onDelete = onDeletePhoto
                )
            }
        }
    }
}

/**
 * Minimal full-screen viewer for a photo that just arrived via ACTION_VIEW but
 * isn't in the gallery DB yet. Renders the URI directly so the app opens on the
 * image with no delay; [PhotoPage] swaps to the full swipeable pager as soon as
 * the background index writes the row.
 */
@Composable
private fun PendingPhotoView(uri: String, context: Context) {
    // RAW SCAFFOLD EXCEPTION: bar-less full-screen black viewer for a not-yet-indexed image;
    // AppScaffold always renders a top app bar, which would break the immersive viewer.
    Scaffold(containerColor = Color.Black) { paddingValues ->
        AsyncImage(
                model = ImageRequest.Builder(context).data(uri.toUri()).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun PhotoDetailView(
        photo: Photo,
        context: Context,
        photoMapViewModel: PhotoMapViewModel,
        pagerState: PagerState,
        pageIndex: Int,
        isSettled: Boolean,
        isMetadataVisible: Boolean,
        currentZoom: ZoomState,
        peopleCount: Int = 0,
        onZoomUpdate: (ZoomState) -> Unit,
        onToggleMetadata: () -> Unit,
        refreshKey: Int = 0,
        onEditPhoto: () -> Unit,
        onSetWallpaper: (Photo) -> Unit = {},
        onDelete: (Photo) -> Unit = {}
) {
    val countryNames by photoMapViewModel.countryNames.collectAsState()
    val countryName = countryNames[photo.id]

    // File size for the metadata bar, read lazily from MediaStore off the UI
    // thread (no schema change needed; mirrors how countryName is fetched).
    var fileSize by remember(photo.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(photo.id) {
        fileSize = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    photo.uri.toUri(),
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }.getOrNull()
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val updatedZoomState by rememberUpdatedState(currentZoom)
    val updatedOnZoomUpdate by rememberUpdatedState(onZoomUpdate)
    val updatedOnToggleMetadata by rememberUpdatedState(onToggleMetadata)

    // Derived state for how far this specific page is from the center
    val pageOffset by remember {
        derivedStateOf {
            ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
        }
    }

    // Reset zoom only when the page is fully scrolled out of view (offset >= 1.0)
    // This allows the "fadeOut" to happen while the image is still zoomed.
    LaunchedEffect(Unit) {
        snapshotFlow { pageOffset }.filter { it >= 0.99f }.distinctUntilChanged().collect {
            if (updatedZoomState.scale > 1f) {
                updatedOnZoomUpdate(ZoomState())
            }
        }
    }

    LaunchedEffect(photo.id) {
        if (photo.lat != null && photo.long != null) {
            photoMapViewModel.requestCountryName(photo.id, photo.lat, photo.long)
        }
    }

    // Panorama photos show as a flat image by default; a button opens an
    // immersive viewer — a drag-to-look sphere for 360s, or a wide
    // horizontally-scrolling rectangle for flat (cylindrical) panoramas.
    val isPanorama = photo.videoData == null && photo.panoData != null
    val isSphere = photo.panoData?.isSphere == true
    var showImmersive by remember(photo.id) { mutableStateOf(false) }

    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                        onTap = { updatedOnToggleMetadata() },
                                        onDoubleTap = {
                                            val newScale =
                                                    if (updatedZoomState.scale > 1f) 1f else 2.5f
                                            updatedOnZoomUpdate(
                                                    ZoomState(
                                                            scale = newScale,
                                                            offset = Offset.Zero
                                                    )
                                            )
                                        }
                                )
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        val isPinching = zoomChange != 1f
                                        val isZoomed = updatedZoomState.scale > 1.01f

                                        if (isZoomed || isPinching) {
                                            val newScale =
                                                    (updatedZoomState.scale * zoomChange).coerceIn(
                                                            1f,
                                                            5f
                                                    )

                                            if (newScale > 1f) {
                                                val maxX = (size.width * (newScale - 1) / 2)
                                                val maxY = (size.height * (newScale - 1) / 2)

                                                val newOffset = updatedZoomState.offset + panChange

                                                val isAtLeftEdge =
                                                        newOffset.x >= maxX && panChange.x > 0
                                                val isAtRightEdge =
                                                        newOffset.x <= -maxX && panChange.x < 0

                                                val boundedOffset =
                                                        Offset(
                                                                newOffset.x.coerceIn(-maxX, maxX),
                                                                newOffset.y.coerceIn(-maxY, maxY)
                                                        )

                                                updatedOnZoomUpdate(
                                                        ZoomState(
                                                                scale = newScale,
                                                                offset = boundedOffset
                                                        )
                                                )

                                                if (isPinching || (!isAtLeftEdge && !isAtRightEdge)
                                                ) {
                                                    event.changes.forEach { it.consume() }
                                                }
                                            } else {
                                                updatedOnZoomUpdate(
                                                        ZoomState(scale = 1f, offset = Offset.Zero)
                                                )
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
    ) {
        if (photo.videoData == null) {
            val imageModifier =
                    Modifier.fillMaxSize()
                            .onGloballyPositioned { layoutCoordinates ->
                                size = layoutCoordinates.size
                            }
                            .graphicsLayer {
                                scaleX = currentZoom.scale
                                scaleY = currentZoom.scale
                                translationX = currentZoom.offset.x
                                translationY = currentZoom.offset.y
                            }
            if (photo.isGif) {
                AnimatedImage(
                        uri = photo.uri.toUri(),
                        contentDescription = null,
                        modifier = imageModifier
                )
            } else {
                AsyncImage(
                        model =
                                ImageRequest.Builder(context)
                                        .data(photo.uri.toUri())
                                        .diskCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                                        .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                                        .build(),
                        contentDescription = null,
                        modifier = imageModifier,
                        contentScale = ContentScale.Fit
                )
            }
        } else {
            VideoPlayer(
                    modifier =
                            Modifier.fillMaxSize()
                                    .onGloballyPositioned { size = it.size }
                                    .graphicsLayer {
                                        scaleX = currentZoom.scale
                                        scaleY = currentZoom.scale
                                        translationX = currentZoom.offset.x
                                        translationY = currentZoom.offset.y
                                    },
                    uri = photo.uri.toUri(),
                    isMetadataVisible = isMetadataVisible,
                    isSettledPage = isSettled
            )
        }

        AnimatedVisibility(
                visible = isMetadataVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Column(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .graphicsLayer {
                                        // Keep the metadata fade-out tied to the swiping distance
                                        alpha = 1f - pageOffset.coerceIn(0f, 1f)
                                    }
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(16.dp)
            ) {
                Text(
                        text = photo.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                )

                val dateFormatted =
                        remember(photo.date) {
                            DateString.dateLong(Instant.fromEpochMilliseconds(photo.date))
                        }

                Text(
                        text = stringResource(R.string.taken_on, dateFormatted),
                        color = Color.LightGray
                )
                if (photo.exifSet) {
                    Text(
                            text =
                                    stringResource(
                                            R.string.location,
                                            countryName ?: stringResource(R.string.detecting)
                                    ),
                            color = Color.LightGray
                    )
                }
                Text(
                        text = stringResource(R.string.resolution, photo.width, photo.height),
                        color = Color.LightGray
                )
                fileSize?.takeIf { it > 0 }?.let { bytes ->
                    Text(
                            text = stringResource(
                                    R.string.file_size,
                                    Formatter.formatShortFileSize(context, bytes)
                            ),
                            color = Color.LightGray
                    )
                }
                if (photo.panoData != null) {
                    Text(text = if (isSphere) "360°" else stringResource(R.string.panorama), color = Color.LightGray)
                }
                if (peopleCount > 0) {
                    Text(
                            text = pluralStringResource(R.plurals.people_in_photo, peopleCount, peopleCount),
                            color = Color.LightGray
                    )
                }

                Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onSetWallpaper(photo) }) {
                        IconWallpaper(tint = Color.White)
                    }
                    IconButton(onClick = onEditPhoto) { IconEdit(tint = Color.White) }
                    IconButton(
                            onClick = {
                                val intent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type =
                                                    if (photo.videoData != null) "video/*"
                                                    else "image/*"
                                            putExtra(Intent.EXTRA_STREAM, photo.uri.toUri())
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                ExternalIntents.launch(context, Intent.createChooser(intent, context.getString(UiR.string.share)))
                            }
                    ) { IconShare(tint = Color.White) }
                    IconButton(onClick = { onDelete(photo) }) {
                        IconDelete(tint = Color.White)
                    }
                }
            }
        }

        if (isPanorama) {
            AnimatedVisibility(
                    visible = isMetadataVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                FilledTonalButton(onClick = { showImmersive = true }) {
                    Text(stringResource(if (isSphere) R.string.view_360 else R.string.view_panorama))
                }
            }
        }
    }

    if (showImmersive && photo.panoData != null) {
        Dialog(
                onDismissRequest = { showImmersive = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (isSphere) {
                    PanoramaSphereView(photo = photo, modifier = Modifier.fillMaxSize())
                } else {
                    PanoramaFlatView(photo = photo, modifier = Modifier.fillMaxSize())
                }
                FilledTonalButton(
                        onClick = { showImmersive = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    Text(stringResource(UiR.string.close))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
        modifier: Modifier,
        uri: Uri,
        isMetadataVisible: Boolean,
        isSettledPage: Boolean,
) {
    val context = LocalContext.current

    // Default to a sane ratio until the player loads the real one
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = isSettledPage
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    var isPlaying by remember { mutableStateOf(isSettledPage) }
    LaunchedEffect(isSettledPage) {
        if (isSettledPage) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    // Seek bar state. durationMs is 0 until the player is ready (duration is C.TIME_UNSET,
    // i.e. negative, before then). While the user drags, positionMs is frozen and scrubPos
    // drives the slider so polling can't fight the thumb.
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var isScrubbing by remember(uri) { mutableStateOf(false) }
    var scrubPos by remember(uri) { mutableFloatStateOf(0f) }

    LaunchedEffect(exoPlayer, isScrubbing) {
        while (isActive && !isScrubbing) {
            val d = exoPlayer.duration
            durationMs = if (d > 0) d else 0L
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(200)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener =
                object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        // Calculate ratio from the actual video stream
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoAspectRatio =
                                    (videoSize.width * videoSize.pixelWidthHeightRatio) /
                                            videoSize.height
                        }
                    }
                }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // The container ensures the surface stays centered and "fitted"
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSurface(
                    player = exoPlayer,
                    modifier =
                            Modifier.fillMaxWidth() // Try to fill width
                                    .aspectRatio(videoAspectRatio),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW
            )
        }

        // Play/Pause Overlay
        AnimatedVisibility(visible = isMetadataVisible, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                if (isPlaying) IconPause(
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.8f)
                ) else IconPlayCircle(
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Seek bar overlay, shares the metadata-visible gate with the play/pause button.
        // Anchored to the top so it never sits under the centered play/pause control or
        // the bottom metadata/button card, both of which are drawn over the video.
        AnimatedVisibility(
                visible = isMetadataVisible && durationMs > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val sliderValue =
                    (if (isScrubbing) scrubPos else positionMs.toFloat())
                            .coerceIn(0f, durationMs.toFloat())
            Column(
                    modifier = Modifier.fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Slider(
                        value = sliderValue,
                        onValueChange = {
                            isScrubbing = true
                            scrubPos = it
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(scrubPos.toLong())
                            positionMs = scrubPos.toLong()
                            isScrubbing = false
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                )
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                            formatVideoTime(sliderValue.toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                            formatVideoTime(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
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
