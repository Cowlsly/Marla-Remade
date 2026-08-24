package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.ArtworkFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover and the lyrics, for a session with no picture.
 *
 * **Not part of `ReceiverContent`.** For an audio-only session `MirrorActivity` adds no `SurfaceView`
 * at all and fills its window with a `ComposeView`, so `ReceiverContent`'s mirroring arm is on screen
 * for a few milliseconds and then never again. This is drawn by `MirrorActivity.overlayView()`, above
 * the transport overlay that is already pinned permanently visible for audio.
 *
 * **The title, author and album are deliberately not here.** They live in [TransportOverlay]'s
 * headline, where they serve a video session too and where the space is already reserved for a line of
 * text. Drawing them in both places spent this screen's whole vertical budget on saying twice what the
 * bar underneath says once - which is what pushed the cover off the top of the panel.
 *
 * **Nothing on it is focusable, and the reason is not `TransportOverlay`'s.** That overlay avoids
 * focus so the compositor is never asked to redraw a video layer underneath it; an audio-only session
 * has no video layer, so that argument does not apply here. The reason here is simply that there is
 * no input model on this screen - the lyrics scroll from the clock, so a scrollable focus target would
 * be a thing the remote could move that changes nothing and takes the D-pad away from seeking.
 *
 * It does **not** paint a background. `MirrorActivity` paints the window once, behind both this and
 * the transport bar beneath it, so that the two are one continuous surface rather than a sheet of
 * colour with a differently-coloured strip under it. Painting here as well would be a second owner of
 * the same pixels and would put the seam back the moment either colour changed.
 *
 * [positionMs] is a lambda rather than a `Long` on purpose. It is read sixty times a second, and
 * passing the value would recompose the artwork and the entire lyrics column every frame on hardware
 * with no headroom to spare. Only the small subtree that derives the lyric index from it recomposes,
 * and that a few dozen times a song.
 */
@Composable
fun NowPlayingScreen(
    nowPlaying: NowPlaying,
    /** Where playback is right now, read per frame. See the note above about why it is a lambda. */
    positionMs: () -> Long,
    artwork: ArtworkFetcher?,
    modifier: Modifier = Modifier,
) {
    val cover = rememberArtwork(nowPlaying.artworkResourceId, artwork)
    val hasLyrics = nowPlaying.lyrics.isNotEmpty() || nowPlaying.plainLyrics.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        if (hasLyrics) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                // **Sized from the height it is given, never from a constant.** This panel is 540dp
                // tall, so a cover with a fixed edge overflows and - centred - runs off the top of the
                // screen rather than being clipped at the bottom where it would at least be obvious.
                // Squaring a filled height cannot overflow whatever the window turns out to be.
                Cover(cover, Modifier.fillMaxHeight())
                Lyrics(nowPlaying, positionMs, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // The common case, not the exception - every MP3 reaches here, because the phone's lyrics
            // reader handles Vorbis and MP4 tags and not ID3. A centred cover with the bar naming it
            // looks deliberate; the split layout with one half empty looks unfinished.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Cover(cover, Modifier.fillMaxHeight())
            }
        }
    }
}

/**
 * The cover, or a drawn stand-in for one.
 *
 * **Never a broken image.** No artwork is by far the most likely outcome for a library of loose
 * files, and it is also what a failed fetch and a fetch still in flight look like - so all three land
 * on a tonal surface that says so in words rather than on anything that reads as an error.
 */
@Composable
private fun Cover(cover: ImageBitmap?, modifier: Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = stringResource(R.string.tv_now_playing_artwork),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = stringResource(R.string.tv_now_playing_no_artwork),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Timed lyrics that follow the clock, or untimed ones as a block.
 *
 * `userScrollEnabled = false` and no focus: this list is driven entirely from [positionMs].
 *
 * The index comes from a `derivedStateOf` over that lambda, which is what keeps the cost of a per-frame
 * clock off this column - it recomputes every frame but only *invalidates* when the answer changes,
 * which is a few dozen times a song rather than sixty times a second.
 *
 * **The current line is scrolled to the top, and what is below it fades out.** Upcoming lyrics read
 * downward, so the gradient falls in the direction the eye is already travelling and the line being
 * sung is the most legible thing on the panel. It is a mask over the whole column rather than a
 * per-line alpha, because a line half in and half out of the fade should fade by the same amount as
 * the pixels around it.
 */
@Composable
private fun Lyrics(nowPlaying: NowPlaying, positionMs: () -> Long, modifier: Modifier) {
    if (nowPlaying.lyrics.isEmpty()) {
        // Nothing to highlight and nothing to scroll to, so it is a block the user reads at their own
        // pace. Scrollable would be a lie: there is no input for it.
        LazyColumn(modifier = modifier.fadingEdge(), userScrollEnabled = false) {
            item {
                Text(
                    text = nowPlaying.plainLyrics,
                    fontSize = LYRIC_SIZE_SP.sp,
                    lineHeight = LYRIC_LINE_HEIGHT_EM.em,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
        return
    }

    val lines = nowPlaying.lyrics
    // `rememberUpdatedState`, and it is load-bearing. `PlaybackClock` builds a fresh lambda per
    // recomposition that closes over *that* composition's snapshot; a `remember` keyed only on the
    // lines would pin the first one for the whole track, so the highlight would keep extrapolating
    // from the first anchor and never see a pause, a seek or a scrub. Only `frameNowMs` is live
    // inside that lambda - everything else in it is captured by value.
    val clock by rememberUpdatedState(positionMs)
    val current by remember(lines) {
        derivedStateOf { currentLyricIndex(lines, clock()) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current, lines) {
        if (current >= 0) runCatching { listState.animateScrollToItem(current) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fadingEdge(),
        userScrollEnabled = false,
        // **Between items, and [LYRIC_LINE_HEIGHT_EM] within one.** A long lyric wraps, and spacing
        // alone leaves the wrapped halves of one line closer together than the default leading allows
        // - so two lines of one lyric collide while the gap to the next lyric looks generous.
        verticalArrangement = Arrangement.spacedBy(LYRIC_GAP_DP.dp),
        // Room to scroll the last lyric up to where the current one is read, instead of it stopping
        // part-way and leaving the highlight pinned at the bottom for the outro.
        contentPadding = PaddingValues(bottom = 240.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == current
            Text(
                text = line.text,
                fontSize = if (isCurrent) LYRIC_CURRENT_SIZE_SP.sp else LYRIC_SIZE_SP.sp,
                lineHeight = LYRIC_LINE_HEIGHT_EM.em,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
        }
    }
}

/**
 * Fades the bottom of a column out to nothing.
 *
 * `DstIn` over an offscreen layer, which is the one way to make a *mask* rather than a scrim: a
 * gradient drawn in the background colour would be opaque over the cover beside it, and one drawn in
 * black would be a grey wash on any theme but this one. Offscreen compositing is required for the
 * blend to have the column's own alpha to work against.
 */
private fun Modifier.fadingEdge(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                FADE_START to Color.White,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Which line is current at [positionMs], or -1 before the first one.
 *
 * The same rule the phone applies, deliberately written out rather than shared: `:music` and this
 * module have no common dependency, and one `indexOfLast` is a smaller thing to duplicate than a
 * module boundary is to move. The boundary is inclusive - a line whose timestamp is exactly the
 * position is the line being sung.
 */
private fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.atMs <= positionMs }

/**
 * The decoded cover for [resourceId], or null while it is being fetched and if it never arrives.
 *
 * Keyed on the resource id, so a track change starts a fresh fetch and - more importantly - drops the
 * previous cover the instant the id changes rather than leaving it on screen over the next track.
 */
@Composable
private fun rememberArtwork(resourceId: String, artwork: ArtworkFetcher?): ImageBitmap? {
    if (resourceId.isEmpty() || artwork == null) return null
    val bitmap by produceState<ImageBitmap?>(initialValue = null, resourceId, artwork) {
        value = withContext(Dispatchers.IO) { artwork.fetch(resourceId)?.asImageBitmap() }
    }
    return bitmap
}

/**
 * Lyric sizes, for a panel that reports 960x540dp.
 *
 * Legible across a room and small enough that half a dozen lines fit beside the cover, which is what
 * makes the highlight look like it is moving through a song rather than replacing the screen.
 */
private const val LYRIC_CURRENT_SIZE_SP = 28
private const val LYRIC_SIZE_SP = 22
private const val LYRIC_GAP_DP = 20

/** Leading within one wrapped lyric, as a multiple of its own size. */
private const val LYRIC_LINE_HEIGHT_EM = 1.35f

/** Where the fade begins, as a fraction of the column's height. */
private const val FADE_START = 0.55f
