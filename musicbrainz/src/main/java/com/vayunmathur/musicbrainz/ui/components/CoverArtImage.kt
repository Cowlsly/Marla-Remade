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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
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
 *
 * Two things keep this cheap enough to scroll. The request carries the display size, so a
 * 500 px archive JPEG is downsampled while decoding instead of landing on the heap at ~1 MB
 * a row. And a URL that recently failed is remembered in [CoverArtFailures]: the loader
 * caches successes but not failures, and a row leaving the viewport disposes its load, so
 * without that memory every scroll pass re-ran the whole redirect chain for art that is not
 * there.
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
                val context = LocalContext.current
                val sizePx = with(LocalDensity.current) { size.dp.roundToPx() }
                var model by remember(url, fallbackUrl) {
                    mutableStateOf(CoverArtFailures.firstUntried(url, fallbackUrl))
                }
                val request = remember(model, sizePx, context) {
                    model?.let { ImageRequest.Builder(context).data(it).size(sizePx).build() }
                }
                if (request != null) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.size(size.dp),
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImageState.Error) {
                                val attempted = model
                                if (attempted != null) CoverArtFailures.record(attempted)
                                model = if (attempted == url) {
                                    fallbackUrl?.takeIf {
                                        it != url && !CoverArtFailures.hasFailed(it)
                                    }
                                } else {
                                    null
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The cover-art URLs recently found to have no image behind them.
 *
 * The Cover Art Archive 404s for a large share of releases, and the loader stores only
 * successes, so a failed load is free to repeat itself forever.
 *
 * Entries expire because the loader discards the HTTP status - a 404 and a dropped
 * connection both surface as a plain fetch failure - so this cannot tell "there is no art"
 * from "the network is down right now". A TTL keeps a real miss suppressed long enough to
 * make scrolling cheap while letting a spell offline heal itself. Bounded and LRU because
 * the miss set grows with everything the user browses.
 */
private object CoverArtFailures {

    private const val MAX_ENTRIES = 512
    private const val TTL_MS = 10 * 60 * 1000L

    private val failedAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean =
            size > MAX_ENTRIES
    }

    fun hasFailed(url: String): Boolean = synchronized(failedAt) { isFresh(url) }

    fun record(url: String) {
        synchronized(failedAt) { failedAt[url] = System.currentTimeMillis() }
    }

    /** The first of [url] then [fallbackUrl] not known to fail, or null when both do. */
    fun firstUntried(url: String, fallbackUrl: String?): String? = synchronized(failedAt) {
        listOfNotNull(url, fallbackUrl).firstOrNull { !isFresh(it) }
    }

    private fun isFresh(url: String): Boolean {
        val at = failedAt[url] ?: return false
        if (System.currentTimeMillis() - at < TTL_MS) return true
        failedAt.remove(url)
        return false
    }
}
