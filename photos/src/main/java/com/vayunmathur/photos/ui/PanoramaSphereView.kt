package com.vayunmathur.photos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.library.ui.PanoramaCrop
import com.vayunmathur.library.ui.PanoramaSphere
import com.vayunmathur.photos.data.Photo

/**
 * Interactive 360 viewer for a photo: hands the shared [PanoramaSphere] renderer
 * the photo's XMP `GPano` rectangle and a decoder for its bytes.
 *
 * A partial pano maps only its covered band; the rest of the sphere stays black.
 */
@Composable
fun PanoramaSphereView(
    photo: Photo,
    modifier: Modifier = Modifier,
) {
    val pano = photo.panoData ?: return
    val context = LocalContext.current
    val uri = photo.uri.toUri()

    PanoramaSphere(
        crop = PanoramaCrop(
            fullWidth = pano.fullWidth,
            fullHeight = pano.fullHeight,
            croppedWidth = pano.croppedWidth,
            croppedHeight = pano.croppedHeight,
            croppedLeft = pano.croppedLeft,
            croppedTop = pano.croppedTop,
        ),
        textureKey = photo.uri,
        modifier = modifier,
    ) { maxTextureSize -> decodeWithin(context, uri, maxTextureSize) }
}

/** Decode [uri] halved until both dimensions fit [maxTextureSize]. */
private fun decodeWithin(context: Context, uri: Uri, maxTextureSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / sample > maxTextureSize || bounds.outHeight / sample > maxTextureSize) {
        sample *= 2
    }

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}
