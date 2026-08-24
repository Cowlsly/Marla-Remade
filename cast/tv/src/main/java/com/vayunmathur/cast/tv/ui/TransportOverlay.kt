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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot

/**
 * The transport controls, over the picture.
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
 */
@Composable
fun TransportOverlay(
    snapshot: PlaybackSnapshot,
    sourceName: String,
    /** A scrub in progress, which has not been committed to the phone yet. Null when not scrubbing. */
    scrubPreviewMs: Long?,
    modifier: Modifier = Modifier,
) {
    val state = snapshot.state

    // Redrawn with the panel rather than with the phone's reports. A bar plotted only when a snapshot
    // landed would step twice a second; anchoring on the snapshot and advancing with the frame clock is
    // what makes it move like a seek bar. Keyed on the snapshot so every fresh one re-anchors.
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot) {
        frameNowMs = System.currentTimeMillis()
        // Nothing to advance while paused, and a frame callback per frame for a still bar would keep
        // the panel awake for no reason.
        if (!state.playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameNowMs = System.currentTimeMillis() }
        }
    }
    val positionMs = scrubPreviewMs ?: snapshot.positionAt(frameNowMs)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // A scrim rather than a panel: the picture stays visible behind the controls, which is
                // what makes scrubbing usable at all.
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                )
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
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
                positionMs = positionMs,
                durationMs = state.durationMs,
                scrubbing = scrubPreviewMs != null,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = elapsed(positionMs),
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
