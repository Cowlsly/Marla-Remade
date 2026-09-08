package com.vayunmathur.music.ui.components
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.platform.AlbumArt

/**
 * The mini player docked above the tab bar. Tapping anywhere opens the full player.
 *
 * Hand-rolled rather than media3's `MiniController`, which draws its own artwork, title and artist
 * with no way to put a `Modifier` on any of them. All three have to carry shared-element keys so
 * they can travel to and from the now-playing screen, and that is only possible if we lay them out
 * ourselves.
 *
 * [owsSharedKeys] decides whether this bar is the counterpart of the now-playing screen for the
 * current transition. It is false when the songs list is the origin instead - see
 * `MusicTabsScreen`, which owns that decision.
 */
@Composable
fun NowPlayingBar(
    songId: Long?,
    title: String,
    artist: String,
    artworkUri: Uri?,
    isPlaying: Boolean,
    owsSharedKeys: Boolean,
    onOpen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun keyed(slot: String): Modifier =
        if (!owsSharedKeys || songId == null) Modifier else Modifier.sharedText("music-song-$slot-$songId")

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(
                artworkUri,
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (!owsSharedKeys || songId == null) Modifier
                        else Modifier.sharedContainer("music-song-art-$songId")
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = keyed("title"),
                )
                Text(
                    artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = keyed("artist"),
                )
            }
            IconButton(onClick = onTogglePlayPause) {
                if (isPlaying) IconPause() else IconPlay()
            }
            IconButton(onClick = onSkipNext) {
                IconSkipNext()
            }
        }
    }
}
