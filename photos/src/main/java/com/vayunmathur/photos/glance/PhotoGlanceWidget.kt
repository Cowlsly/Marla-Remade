package com.vayunmathur.photos.glance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vayunmathur.library.widgets.DynamicThemeGlance
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.PhotosRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {

        val uris = PhotosRepository.get(context.applicationContext).getStillPhotoUris()

        provideContent {
            var uri by remember(uris) { mutableStateOf(uris.randomOrNull()) }
            val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
                value = withContext(Dispatchers.IO) {
                    uri?.let { getResizedBitmap(context, it.toUri(), 600) }
                }
            }
            DynamicThemeGlance(context) {
                bitmap?.let {
                    Content(it) { uri = uris.randomOrNull() }
                }
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    Box(
                        modifier = GlanceModifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.widget_preview_photo),
                            contentDescription = null,
                            modifier = GlanceModifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("PhotoWidget", "providePreview failed", e)
            // Fallback: avoid system_accent colors that may not resolve in preview host on API 37
            try {
                provideContent {
                    DynamicThemeGlance(context) {
                        Box(
                            modifier = GlanceModifier.fillMaxSize()
                                .background(GlanceTheme.colors.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Photos",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // last resort – don't crash setWidgetPreviews
            }
        }
    }
}

@Composable
fun Content(bitmap: Bitmap, newPhoto: () -> Unit) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable { newPhoto() },
            contentScale = ContentScale.Crop
        )
    }
}

fun getResizedBitmap(context: Context, uri: Uri, maxSize: Int = 600): Bitmap? {
    return try {
        val contentResolver = context.contentResolver

        // 1. Get dimensions only (no memory used for pixels)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        // 2. Calculate the sample size (power of 2)
        options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
        options.inJustDecodeBounds = false

        // 3. Decode the downsampled bitmap
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}