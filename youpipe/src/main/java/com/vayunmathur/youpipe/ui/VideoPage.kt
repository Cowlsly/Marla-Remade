package com.vayunmathur.youpipe.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.text.format.Formatter
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconThumbDown
import com.vayunmathur.library.ui.IconThumbUp
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.util.round
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.decodeHtml
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class VideoChapter(val time: Int, val title: String, val previewURL: String?)
data class AudioStream(
    val url: String,
    val bitrate: Int,
    val language: String,
    val codec: String,
    val size: Long,
    val audioTrackId: String? = null,
    val displayName: String? = null,
)
data class VideoStream(val url: String, val width: Int, val height: Int, val bitrate: Int, val fps: Int, val quality: String, val codec: String, val size: Long)
data class SubtitleTrack(
    val url: String,
    val languageTag: String,      // e.g. "en", "es"
    val displayName: String,      // e.g. "English", "Spanish (auto-generated)"
    val autoGenerated: Boolean,
    val mimeType: String,         // e.g. "application/ttml+xml" or "text/vtt"
)
data class VideoData(val title: String, val views: Long, val duration: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String, val authorURL: String, val authorThumbnail: String, val description: String)
data class Comment(val text: String, val author: String, val likes: Int, val dislikes: Int)

@Composable
fun LockScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation

        onDispose {
            // Restore original orientation when leaving the screen
            activity.requestedOrientation = originalOrientation
        }
    }
}

/**
 * Binder for the video screen: loads the video, owns the fullscreen/PiP window plumbing and
 * the player itself, and hands everything else to [VideoDetailScreen] as plain values.
 */
@Composable
fun VideoPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val videoState by ypvm.videoState.collectAsState()

    LaunchedEffect(videoID) {
        val downloaded = downloadedFlow.first()
        ypvm.loadVideo(videoID, downloaded)
    }
    LaunchedEffect(downloadedVideo) {
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }

    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val deArrowData = if (deArrowEnabled) deArrowCache[videoID] else null

    val videoData = videoState.data?.let { data ->
        data.copy(
            title = deArrowData?.title ?: data.title,
            thumbnailURL = deArrowData?.thumbnailUrl ?: data.thumbnailURL
        )
    }
    val videoStreams = videoState.videoStreams
    val audioStreams = videoState.audioStreams
    val subtitles = videoState.subtitles
    val segments = videoState.segments
    val comments = videoState.comments
    val relatedVideos = videoState.relatedVideos

    if (videoState.error) {
        Dialog({
            ypvm.clearVideoError()
            backStack.pop()
        }) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.video_load_error))
                    Spacer(Modifier.height(8.dp))
                    Button({
                        ypvm.clearVideoError()
                        backStack.pop()
                    }) {
                        Text(stringResource(R.string.action_go_back))
                    }
                }
            }
        }
    }

    var isFullscreen by remember { mutableStateOf(false) }
    // Whether fullscreen was entered by rotating rather than by the button. Rotation-entered
    // fullscreen must leave the orientation unlocked, otherwise rotating back to portrait is
    // impossible and the only way out is the exit button.
    var fullscreenFromRotation by remember { mutableStateOf(false) }
    // Set when the user leaves fullscreen while still holding the device in landscape, so that
    // auto-fullscreen doesn't immediately put them back. Cleared on the next portrait.
    var autoFullscreenSuppressed by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    // smallestScreenWidthDp is the shorter edge, so unlike a window size class it gives the same
    // answer in both orientations. 600dp is the sw600dp bucket Android itself calls a tablet.
    val isPhone = configuration.smallestScreenWidthDp < 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isInPipMode = rememberIsInPipMode()

    // A phone in landscape has no room for the description and comments, so fill the screen with
    // the video. Tablets are wide enough to keep the whole page, so they stay opt-in via the button.
    LaunchedEffect(isPhone, isLandscape, isInPipMode) {
        if (!isPhone || isInPipMode) return@LaunchedEffect
        if (isLandscape) {
            if (!isFullscreen && !autoFullscreenSuppressed) {
                isFullscreen = true
                fullscreenFromRotation = true
            }
        } else {
            autoFullscreenSuppressed = false
            if (fullscreenFromRotation) {
                isFullscreen = false
                fullscreenFromRotation = false
            }
        }
    }

    val view = LocalView.current
    LaunchedEffect(isFullscreen) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)

        if (isFullscreen) {
            // Hide both status bar and navigation bar
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // Make it so they only reappear with a swipe and don't resize the layout
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (isFullscreen && !fullscreenFromRotation) {
        LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
    }

    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }

    VideoDetailScreen(
        state = VideoDetailUiState(
            loaded = videoData != null,
            title = videoData?.title.orEmpty(),
            byline = videoData?.let {
                if (it.views < 0) {
                    resources.getString(
                        R.string.video_info_paid_format,
                        it.author,
                        uploadTimeAgo(context, it.uploadDate),
                    )
                } else {
                    resources.getString(
                        R.string.video_info_format,
                        it.author,
                        countString(context, it.views),
                        uploadTimeAgo(context, it.uploadDate),
                    )
                }
            }.orEmpty(),
            authorThumbnailURL = videoData?.authorThumbnail.orEmpty(),
            authorURL = videoData?.authorURL.orEmpty(),
            description = videoData?.description.orEmpty(),
            comments = comments,
            relatedVideos = relatedVideos.map { related ->
                val relatedDeArrow = if (deArrowEnabled) deArrowCache[related.videoID] else null
                val watched = progressById[related.videoID] ?: 0L
                videoRowState(
                    context = context,
                    videoInfo = related,
                    showAuthor = true,
                    percentWatched = if (related.duration > 0) (watched.toDouble() / related.duration).toFloat() else 0f,
                    deArrowTitle = relatedDeArrow?.title,
                    deArrowThumbnailURL = relatedDeArrow?.thumbnailUrl,
                )
            },
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            downloaded = downloadedVideo != null,
            downloadProgress = activeDownloads[videoID]?.progress?.toFloat(),
        ),
        actions = object : VideoDetailActions {
            override fun openChannel() {
                videoData?.let { backStack.add(Route.ChannelPage(it.authorURL)) }
            }

            override fun openVideo(videoID: Long) {
                backStack.add(Route.VideoPage(videoID))
            }

            override fun download(videoUrl: String, audioUrl: String?) {
                val data = videoData ?: return
                DownloadManager.enqueueDownload(
                    context,
                    VideoInfo(data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author),
                    videoUrl,
                    audioUrl,
                )
            }

            override fun cancelDownload() {
                DownloadManager.cancelDownload(context, videoID)
            }

            override fun deleteDownload() {
                downloadedVideo?.let { ypvm.deleteDownloadedVideo(it) }
            }

            override fun addToPlaylist() {
                backStack.add(Route.AddToPlaylist(videoID))
            }
        },
        fullscreen = isFullscreen,
    ) {
        videoData?.let { data ->
            if (videoStreams.isNotEmpty()) {
                VideoPlayer(ypvm, VideoInfo(data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author), videoStreams, audioStreams, subtitles, segments, isFullscreen) { fullscreen ->
                    isFullscreen = fullscreen
                    fullscreenFromRotation = false
                    autoFullscreenSuppressed = !fullscreen && isLandscape
                }
            }
        }
    }
}

/**
 * The video screen with the player supplied as a slot.
 *
 * Stateless, so the store listing preview can render it — which is also why the player is a
 * slot rather than a call: Layoutlib cannot draw an ExoPlayer surface, so a preview simply
 * leaves it out.
 */
@Composable
fun VideoDetailScreen(
    state: VideoDetailUiState,
    actions: VideoDetailActions,
    fullscreen: Boolean = false,
    /** Which of comments / related / description opens first. A seam for the previews. */
    initialTab: Int = 0,
    player: @Composable () -> Unit = {},
) {
    Scaffold { paddingValues ->
        val modifier = if(fullscreen) Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()) else Modifier.padding(paddingValues)
        Column(modifier) {
            if (state.loaded) {
                player()
                VideoDetails(state, actions)

                if(!fullscreen) {
                    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 3 })
                    val coroutineScope = rememberCoroutineScope()

                    val tabLabels = listOf(R.string.label_comments, R.string.label_related_videos, R.string.label_description)
                    Column {
                        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                            tabLabels.forEachIndexed { index, labelRes ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                                ) {
                                    Text(stringResource(labelRes), modifier = Modifier.padding(16.dp))
                                }
                            }
                        }

                        // 3. The Pager that enables swiping
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.Top // Ensures content starts at top
                        ) { page ->
                            when (page) {
                                0 -> CommentsSection(state.comments)
                                1 -> RelatedVideosSection(state.relatedVideos, actions::openVideo)
                                2 -> DescriptionSection(state.description)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetails(
    state: VideoDetailUiState,
    actions: VideoDetailActions,
) {
    var isDownloadDialogVisible by remember { mutableStateOf(false) }

    if (isDownloadDialogVisible) {
        val context = LocalContext.current
        val videoStreams = state.videoStreams
        val audioStreams = state.audioStreams
        var selectedVideoStream by remember { mutableStateOf(videoStreams.maxByOrNull { it.height } ?: videoStreams.first()) }
        // Always highest quality opus for selected language in download as well
        var selectedAudioStream by remember { mutableStateOf(audioStreams.maxByOrNull { it.bitrate }) }

        val languageEntriesDownload = remember(audioStreams) {
            audioStreams.map { it.language to (it.displayName ?: it.language) }
                .distinctBy { it.first }
                .sortedBy { it.first }
        }
        val languages = languageEntriesDownload.map { it.first }
        var selectedLanguage by remember { mutableStateOf(selectedAudioStream?.language ?: languages.firstOrNull() ?: "Default") }

        var videoExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { isDownloadDialogVisible = false }) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.download_options), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    
                    Text(stringResource(R.string.resolution), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = videoExpanded,
                        onExpandedChange = { videoExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${selectedVideoStream.quality} - ${Formatter.formatShortFileSize(context, selectedVideoStream.size)}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = videoExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = videoExpanded,
                            onDismissRequest = { videoExpanded = false }
                        ) {
                            videoStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.quality} (${getVideoCodecName(stream.codec)}) - ${Formatter.formatShortFileSize(context, stream.size)}") },
                                    onClick = {
                                        selectedVideoStream = stream
                                        videoExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (languages.size > 1) {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = languageExpanded,
                            onExpandedChange = { languageExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = languageEntriesDownload.find { it.first == selectedLanguage }?.second ?: selectedLanguage,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false }
                            ) {
                                languageEntriesDownload.forEach { (code, display) ->
                                    DropdownMenuItem(
                                        text = { Text(display) },
                                        onClick = {
                                            selectedLanguage = code
                                            selectedAudioStream = audioStreams.filter { it.language == code }.maxByOrNull { it.bitrate }
                                            languageExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { isDownloadDialogVisible = false }) { Text(stringResource(UiR.string.cancel)) }
                        TextButton(onClick = {
                            isDownloadDialogVisible = false
                            actions.download(selectedVideoStream.url, selectedAudioStream?.url)
                        }) { Text(stringResource(R.string.download)) }
                    }
                }
            }
        }
    }

    Column {
        ListItem(modifier = Modifier, overlineContent = {}, supportingContent = {
            Text(state.byline)
        }, leadingContent = {
            // Block behind the avatar: the placeholder while it loads, and the whole of it
            // in a preview, where there is no network to load it from.
            Box(
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { actions.openChannel() }
            ) {
                if (state.authorThumbnailURL.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(state.authorThumbnailURL)
                            .memoryCacheKey("author-thumb-${state.authorURL}")
                            .build(),
                        contentDescription = null,
                        Modifier.fillMaxSize()
                    )
                }
            }
        }, trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.addToPlaylist() }) {
                    IconSave()
                }
                val downloadProgress = state.downloadProgress
                if (downloadProgress != null) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    IconButton(onClick = { actions.cancelDownload() }) {
                        IconClose()
                    }
                } else if (!state.downloaded) {
                    IconButton(onClick = {
                        isDownloadDialogVisible = true
                    }) {
                        IconDownload()
                    }
                } else {
                    IconButton(onClick = { actions.deleteDownload() }) {
                        IconDelete(tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }) {
            Text(state.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun DescriptionSection(description: String) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            LinkifiedText(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RelatedVideosSection(relatedVideos: List<VideoRowState>, onOpenVideo: (Long) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(relatedVideos, { it.videoID }) { row ->
            VideoRow(row, Modifier.invisibleClickable { onOpenVideo(row.videoID) })
        }
    }
}

@Composable
fun CommentsSection(comments: List<Comment>) {
    LazyColumn {
        items(comments, key = { "${it.author}|${it.text.hashCode()}" }) {
            CommentItem(it)
        }
    }
}

@Composable
fun CommentItem(c: Comment) {
    ListItem(modifier = Modifier, overlineContent = {
        Text(c.author)
    }, supportingContent = {
        Column {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconThumbUp(Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.likes.toString())
                Spacer(Modifier.width(16.dp))
                IconThumbDown(Modifier.size(16.dp))
            }
        }
    }) {
        LinkifiedText(c.text)
    }
}

fun uploadTimeAgo(context: android.content.Context, date: Instant): String {
    val now = Clock.System.now()
    val duration = now - date
    if (duration.isNegative()) return context.getString(R.string.time_ago_just_now)
    return when(duration) {
        in 0.minutes..5.minutes -> context.getString(R.string.time_ago_just_now)
        in 5.minutes..1.hours -> context.getString(R.string.time_ago_minutes, duration.inWholeMinutes.toInt())
        in 1.hours..24.hours -> context.getString(R.string.time_ago_hours, duration.inWholeHours.toInt())
        else -> uploadTimeAgo(context, date.toLocalDateTime(TimeZone.currentSystemDefault()).date)
    }
}

fun uploadTimeAgo(context: android.content.Context, date: LocalDate): String {
    val period = date.periodUntil(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    return when {
        period.years > 0 -> context.getString(R.string.time_ago_years, period.years)
        period.months > 0 -> context.getString(R.string.time_ago_months, period.months)
        period.days > 0 -> context.getString(R.string.time_ago_days, period.days)
        else -> context.getString(R.string.time_ago_just_now)
    }
}

fun countString(context: android.content.Context, count: Long): String {
    val digits = count.toString().length
    return when(digits) {
        in 0..3 -> count.toString()
        4 -> context.getString(R.string.count_k_format, (count / 1000.0).round(2).toString())
        5 -> context.getString(R.string.count_k_format, (count / 1000.0).round(1).toString())
        6 -> context.getString(R.string.count_k_format, (count / 1000).toString())
        7 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(2).toString())
        8 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(1).toString())
        9 -> context.getString(R.string.count_m_format, (count / 1000000).toString())
        10 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(2).toString())
        11 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(1).toString())
        12 -> context.getString(R.string.count_b_format, (count / 1000000000).toString())
        else -> count.toString()
    }
}

fun String.fromHTML(): String {
    return this.replace("<br>", "\n").decodeHtml()
}

fun getVideoCodecName(codec: String): String {
    return when {
        codec.contains("av01", ignoreCase = true) -> "av1"
        codec.contains("vp9", ignoreCase = true) || codec.contains("vp09", ignoreCase = true) -> "vp9"
        codec.contains("avc", ignoreCase = true) || codec.contains("h264", ignoreCase = true) -> "avc"
        else -> codec
    }
}

fun getAudioCodecName(codec: String): String {
    return when {
        codec.contains("opus", ignoreCase = true) -> "opus"
        codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true) -> "aac"
        else -> codec
    }
}
