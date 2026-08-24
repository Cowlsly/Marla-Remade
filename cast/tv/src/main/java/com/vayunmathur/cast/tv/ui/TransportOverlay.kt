package com.vayunmathur.cast.tv.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot

/**
 * Owns the one per-frame clock a served session needs, and hands it down as a lambda.
 *
 * [TransportOverlay] used to own its own `withFrameMillis` ticker, which was right while it was the
 * only thing on screen that moved. The now-playing screen's lyrics need the same interpolated
 * position and the same scrub-preview override, and two tickers would be two answers to one question.
 *
 * **Hoisted as a lambda, not as a value, and the distinction is the whole point.** Passing the number
 * down would put every reader of it in the recomposition scope that invalidates sixty times a second -
 * which on TV-class hardware means redrawing a bitmap and a full lyrics column per frame. Passing a
 * lambda leaves the invalidation here, where the state is read, and lets each reader decide how much
 * of itself depends on it.
 *
 * Keyed on the snapshot so every fresh one re-anchors, and the ticker stops while playback is paused:
 * a frame callback per frame for a still bar would keep the panel awake for nothing.
 */
@Composable
fun PlaybackClock(
    snapshot: PlaybackSnapshot,
    /** A scrub the user is composing, which overrides the clock entirely. Null when not scrubbing. */
    scrubPreviewMs: Long?,
    content: @Composable (positionMs: () -> Long) -> Unit,
) {
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot) {
        frameNowMs = System.currentTimeMillis()
        if (!snapshot.state.playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameNowMs = System.currentTimeMillis() }
        }
    }
    content { scrubPreviewMs ?: snapshot.positionAt(frameNowMs) }
}

/**
 * The transport controls: a bar at the bottom of whatever it is given.
 *
 * **Nothing here is focusable, and that is deliberate rather than an omission.** A media overlay is
 * driven by the remote's dedicated keys and its D-pad as *gestures* - left seeks, up is volume - not by
 * moving a focus ring between buttons. `MirrorActivity.dispatchKeyEvent` is therefore the whole input
 * path, and this file is a read-only view of state. It is the one shape of TV UI where `androidx.tv`'s
 * focus machinery has nothing to contribute, which is also why it is safe to draw over a decoder
 * surface: no focus means no chance of the compositor being asked to redraw the video layer.
 *
 * Only mounted while the phone is reporting playback, so it cannot appear over a mirrored phone screen
 * - there would be no transport to control.
 *
 * **It sizes itself to its content and lets the caller place it, rather than filling the window.** Over
 * video it is aligned to the bottom of a `Box` and overlays the picture, which is the whole point of a
 * scrim. For audio there is no picture, so `MirrorActivity` stacks it under the now-playing screen in a
 * `Column` instead - and then the lyrics get exactly the height that is left, rather than the height
 * left over from a constant somebody guessed at. Filling the window made that impossible to express.
 *
 * **The clock is not its own any more.** [positionMs] is read per frame and supplied by
 * [PlaybackClock], which owns the ticker so that this and the now-playing screen's lyrics move against
 * one number. A lambda rather than a `Long` for the reason [PlaybackClock] gives.
 */
@Composable
fun TransportOverlay(
    snapshot: PlaybackSnapshot,
    /** Who is casting, shown until the phone says what is playing. Usually the app's name. */
    sourceName: String,
    /** What is playing, or null when the phone has not said - an old build, or the first moment. */
    nowPlaying: NowPlaying?,
    /** Where playback is right now, including any scrub preview. Read per frame. */
    positionMs: () -> Long,
    /** Whether a scrub is in progress, which thickens the bar. */
    scrubbing: Boolean,
    /**
     * Whether to darken behind itself.
     *
     * True over a picture, where the gradient is what keeps the frame visible through the controls and
     * the numbers legible against whatever happens to be behind them. **False for audio**, where there
     * is no picture to see through and the scrim would be a black band across the bottom of an
     * otherwise continuous surface - the caller has already painted the window, and the bar belongs to
     * the same sheet of colour as the cover and the lyrics above it.
     */
    scrim: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state = snapshot.state
    val position = positionMs()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (scrim) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Headline(
                nowPlaying = nowPlaying,
                sourceName = sourceName,
                modifier = Modifier.weight(1f),
            )
            if (state.speed != 1f) {
                Text(
                    text = stringResource(R.string.tv_transport_speed, formatSpeed(state.speed)),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        SeekBar(
            positionMs = position,
            durationMs = state.durationMs,
            scrubbing = scrubbing,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsed(position),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            // **The action the centre key will take, not a description of the state.** Read as a
            // status it would be the other way round, and both readings are defensible - which is
            // exactly why it has to match the button it sits next to. Buffering is its own case:
            // a stall and a pause look identical on a frozen picture, and only one of them is the
            // user's own doing.
            Text(
                text = when {
                    state.buffering -> "…"
                    state.playing -> "❚❚"
                    else -> "▶"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = if (state.durationMs > 0) elapsed(state.durationMs) else "",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }

        Text(
            text = stringResource(R.string.tv_transport_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * What is playing, or who is casting when that is all the television knows.
 *
 * **The title replaces the app's name rather than sitting beside it.** "Receiving from YouPipe" was
 * the most the receiver could say when nothing crossed the protocol but pixels; with a title in hand
 * it is the less useful of the two, and a user looking at a video already knows which app they
 * started. The fallback keeps it for an old phone, an app that sends no metadata, and the moment
 * before the first snapshot lands.
 *
 * **Every field it has, and no field it does not**, which is why there is no video-shaped branch here:
 * a track fills all three lines and a video fills two, because `album` is what a video has nothing to
 * put in. Stacked rather than joined onto one line - a video title is long and a channel name is
 * short, and one line holding both would truncate the title to fit something that is not the title.
 */
@Composable
private fun Headline(nowPlaying: NowPlaying?, sourceName: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = nowPlaying?.title.orEmpty().ifBlank { sourceName },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Subtitle(nowPlaying?.author.orEmpty(), alpha = 0.7f)
        Subtitle(nowPlaying?.album.orEmpty(), alpha = 0.5f)
    }
}

/** One line beneath the title, or nothing at all when there is nothing to put on it. */
@Composable
private fun Subtitle(text: String, alpha: Float) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = alpha),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The bar, drawn as nested boxes rather than as a `Slider`.
 *
 * A `Slider` is a touch control: it wants a drag gesture and a focusable thumb, and on a television it
 * would offer both to a device that has neither. What is actually needed is a progress indicator that
 * happens to be movable by the remote, and the remote handling lives in the Activity.
 *
 * A stream with no known duration gets no bar at all, rather than an empty one that looks broken.
 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, scrubbing: Boolean) {
    if (durationMs <= 0) return
    val progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (scrubbing) 10.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary),
        )
        // The thumb rides the end of the filled track, which needs no measured width to place.
        Box(
            modifier = Modifier.fillMaxWidth(progress),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(if (scrubbing) 20.dp else 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** `H:MM:SS` past an hour, `M:SS` below it - which is what every other player on the panel does. */
private fun elapsed(positionMs: Long): String =
    DateUtils.formatElapsedTime((positionMs / 1_000).coerceAtLeast(0))

/** "2" rather than "2.0", "1.5" rather than "1.50". Matches YouPipe's own speed readout. */
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    return if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
