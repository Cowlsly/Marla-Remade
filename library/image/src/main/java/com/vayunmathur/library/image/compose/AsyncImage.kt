package com.vayunmathur.library.image.compose

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageResult
import com.vayunmathur.library.image.Size
import com.vayunmathur.library.image.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil-like AsyncImage backed by our ImageLoader (HttpURLConnection + custom caches + SVG + Video).
 *
 * Supported model types (Any?): Uri, String URL, java.io.File, ByteArray, Bitmap, ImageRequest.
 * Mirrors signature used across codebase: `AsyncImage(model, contentDescription, modifier, contentScale, colorFilter, onState)`
 */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    alpha: Float = 1f,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current
    val loader = imageLoader ?: remember(context) { ImageLoader.get(context) }

    // Normalise model into ImageRequest
    val request: ImageRequest? = remember(model) {
        when (model) {
            null -> null
            is ImageRequest -> model
            else -> ImageRequest.Builder(context).data(model).build()
        }
    }

    // Synchronous memory-cache probe. On a hit the bitmap is available before the
    // first frame, so a scrolled-in item paints immediately instead of showing an
    // empty box for a frame and re-running its crossfade over an image that was
    // already in memory.
    val seeded = remember(request) { request?.let { loader.peekMemoryCache(it) } }

    val bitmapState = produceState<Bitmap?>(initialValue = seeded, key1 = request) {
        if (request == null) {
            value = null
            onState?.invoke(AsyncImageState.Empty)
            return@produceState
        }
        if (seeded != null) {
            // Served by the probe. `value` is assigned explicitly because
            // produceState only applies initialValue on first composition — on a
            // key change it still holds the previous request's bitmap.
            value = seeded
            onState?.invoke(AsyncImageState.Success(BitmapPainter(seeded.asImageBitmap())))
            return@produceState
        }
        onState?.invoke(AsyncImageState.Loading)
        try {
            val result = loader.execute(request)
            when (result) {
                is ImageResult.Success -> {
                    value = result.bitmap
                    val painter = BitmapPainter(result.bitmap.asImageBitmap())
                    onState?.invoke(AsyncImageState.Success(painter))
                }
                is ImageResult.Error -> {
                    value = null
                    onState?.invoke(AsyncImageState.Error(result.throwable))
                }
            }
        } catch (e: Exception) {
            value = null
            onState?.invoke(AsyncImageState.Error(e))
        }
    }

    val bitmap = bitmapState.value

    // Crossfade alpha if requested
    val shouldCrossfade = request?.crossfade == true
    val targetAlpha = if (bitmap != null) alpha else 0f
    val animatedAlpha by animateFloatAsState(
        targetValue = if (shouldCrossfade) targetAlpha else alpha,
        label = "asyncImageCrossfade"
    )

    Box(modifier = modifier, contentAlignment = alignment) {
        if (bitmap != null) {
            // Use Android-like scaling; honor colorFilter and contentScale
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize().let {
                    if (shouldCrossfade) it.alpha(animatedAlpha) else it
                },
                contentScale = contentScale,
                colorFilter = colorFilter,
                alignment = alignment,
            )
        }
    }
}

/**
 * Overload matching `coil.compose.AsyncImage(model: ImageRequest, ...)` convenience.
 */
@Composable
fun AsyncImage(
    model: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
) {
    AsyncImage(
        model = model as Any?,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
        imageLoader = imageLoader,
        onState = onState,
    )
}
