package com.vayunmathur.music.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.MiniController
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.music.platform.AlbumArtBitmapLoader

/** The mini player docked above the tab bar. Tapping anywhere opens the full player. */
@OptIn(UnstableApi::class)
@Composable
fun NowPlayingBar(
    player: Player,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    MiniController(
        player = player,
        modifier = modifier.fillMaxWidth(),
        bitmapLoader = remember(context) { AlbumArtBitmapLoader(context) },
        defaultArtwork = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
        onClick = onOpen,
    )
}
