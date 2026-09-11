package com.vayunmathur.health.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One attached vaccination card, as a tappable chip showing a thumbnail and the file name.
 *
 * Thumbnails are decoded here with [BitmapFactory] rather than through `:library:image`'s
 * `AsyncImage`. That module declares `INTERNET` in its own manifest, which would merge into the
 * health app and make the `100% offline` line in `metadata_data/health.md` untrue — a large price
 * for loading a file that is already on local disk.
 *
 * PDFs get an icon rather than a rendered first page: the only PDF rasteriser in the repo is the
 * `pdf` app's native renderer, which is not in a library module.
 */
@Composable
fun AttachmentChip(
    displayName: String,
    mimeType: String,
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail by produceState<ImageBitmap?>(null, file, mimeType) {
        value = if (mimeType.startsWith("image/")) decodeThumbnail(file) else null
    }

    OutlinedCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = thumbnail
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                IconDescription(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

/** Target edge length for the decoded thumbnail, in pixels. */
private const val THUMBNAIL_PX = 128

/**
 * Decodes [file] down to roughly [THUMBNAIL_PX] on its short edge.
 *
 * The two-pass bounds-then-decode is what keeps a 12 megapixel phone photo from being inflated to
 * 48 MB of bitmap just to draw it at 32dp.
 */
private suspend fun decodeThumbnail(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        if (shortest <= 0) return@withContext null

        var sample = 1
        while (shortest / (sample * 2) >= THUMBNAIL_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
