package com.vayunmathur.music.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Loads artwork the way the rest of the app does.
 *
 * Queue items carry `MediaStore.Audio.Albums` URIs, which are not openable streams - they
 * only resolve through [android.content.ContentResolver.loadThumbnail], so Media3's default
 * [androidx.media3.datasource.DataSourceBitmapLoader] fails on every one of them.
 */
@UnstableApi
class AlbumArtBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalArgumentException("Could not decode bitmap")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            getThumbnail(appContext, uri) ?: throw IllegalArgumentException("No artwork for $uri")
        }
}
