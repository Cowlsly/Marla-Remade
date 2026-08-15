package com.vayunmathur.musicbrainz.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface

/**
 * Cover art with a placeholder behind it.
 *
 * The Cover Art Archive has no image for a good share of releases, and the request that
 * finds that out is a redirect chain to archive.org, so the placeholder is what most
 * lists actually show while scrolling.
 *
 * A release often has no artwork of its own even when its release group does, so on a
 * failed load the [fallbackUrl] - the release-group cover - is tried before giving up.
 * This mirrors the ordered list [com.vayunmathur.musicbrainz.platform.download.DownloadWorker]
 * embeds from, so the in-app image matches what a download would carry.
 */
@Composable
fun CoverArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 56,
    fallbackUrl: String? = null,
) {
    Surface(
        modifier = modifier.size(size.dp).clip(RoundedCornerShape(6.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconAlbum(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Previews render without a network, and the loader would just log failures.
            if (url != null && !LocalInspectionMode.current) {
                var model by remember(url, fallbackUrl) { mutableStateOf(url) }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(size.dp),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImageState.Error &&
                            model == url &&
                            fallbackUrl != null &&
                            fallbackUrl != url
                        ) {
                            model = fallbackUrl
                        }
                    },
                )
            }
        }
    }
}
