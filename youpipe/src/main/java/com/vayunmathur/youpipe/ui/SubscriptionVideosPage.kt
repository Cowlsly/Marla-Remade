package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.SubscriptionFeedActions
import com.vayunmathur.youpipe.util.SubscriptionFeedUiState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * Binder for the subscription feed: newest uploads across every channel the user follows,
 * or across one category of them.
 */
@Composable
fun SubscriptionVideosPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    category: String?,
) {
    val videos by remember(category) { youPipeViewModel.subscriptionVideosFor(category) }
        .collectAsState(initial = emptyList())
    val fetchProgress by youPipeViewModel.fetchProgress.collectAsState()
    val hasLoaded by youPipeViewModel.hasLoadedSubscriptionVideos.collectAsState()

    val history by youPipeViewModel.historyVideos.collectAsState()
    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current

    // Map the raw feed to render-ready rows only when an input actually changes,
    // rather than on every recomposition.
    val rows = remember(videos, deArrowEnabled, deArrowCache, progressById) {
        videos.map { video ->
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watched = progressById[video.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = true,
                percentWatched = if (video.duration > 0) (watched.toDouble() / video.duration).toFloat() else 0f,
                deArrowTitle = deArrow?.title,
                deArrowThumbnailURL = deArrow?.thumbnailUrl,
            )
        }
    }

    SubscriptionVideosScreen(
        backStack = backStack,
        state = SubscriptionFeedUiState(
            videos = rows,
            fetchProgress = fetchProgress,
            isLoading = !hasLoaded,
        ),
        actions = object : SubscriptionFeedActions {
            override fun openVideo(videoID: Long) {
                backStack.add(Route.VideoPage(videoID))
            }
        },
    )
}

/**
 * Stateless subscription feed. [backStack] is here only for preview tooling; taps on the
 * list itself go through [actions].
 */
@Composable
fun SubscriptionVideosScreen(
    backStack: NavBackStack<Route>,
    state: SubscriptionFeedUiState,
    actions: SubscriptionFeedActions,
) {
    Scaffold { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            if (state.fetchProgress in 0f..1f) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator({ state.fetchProgress })
                    }
                }
            }
            if (state.isLoading) {
                // Skeleton placeholders shown before the first DB emission arrives,
                // so the screen never looks frozen while the feed is loading.
                items(SKELETON_ROW_COUNT) {
                    VideoRowSkeleton()
                }
            } else {
                items(state.videos, key = { it.videoID }) { row ->
                    VideoRow(
                        row = row,
                        modifier = Modifier.invisibleClickable { actions.openVideo(row.videoID) },
                    )
                }
            }
        }
    }
}

private const val SKELETON_ROW_COUNT = 8

/**
 * A single placeholder row matching [VideoRow]'s layout: a 16:9 block on the left
 * and a few stacked gray bars on the right. Uses the same static `surfaceVariant`
 * gray-box idiom the real rows use for image placeholders — no shimmer library.
 */
@Composable
fun VideoRowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier) {
        Box(Modifier.weight(1f)) {
            Box(
                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Box(Modifier.weight(1.5f)) {
            Column(Modifier.padding(16.dp)) {
                SkeletonBar(Modifier.fillMaxWidth().height(16.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBar(Modifier.fillMaxWidth(0.6f).height(12.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBar(Modifier.fillMaxWidth(0.4f).height(12.dp))
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
