package com.vayunmathur.photos.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.parseOcrLayout
import com.vayunmathur.photos.data.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val OCR_DECODE_MAX = 1280
internal const val MIN_OCR_DIM = 64

/** Decode a downscaled software bitmap for OCR (long side capped at [OCR_DECODE_MAX]). */
internal fun decodeForOcr(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > OCR_DECODE_MAX) {
                val scale = OCR_DECODE_MAX.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode $uri for OCR", e)
        null
    }
}

/**
 * Supplies the viewer with a photo's OCR geometry, reading [Photo.ocrBoxes] and
 * falling back to running OCR on the spot for rows written before that column
 * existed — writing the result back so the next open is instant.
 *
 * Runs on its own [OcrEngine] rather than sharing the indexing worker's, so an
 * in-progress library scan can't make the viewer wait on that engine's mutex.
 * The engine is created on first use and released once nothing is waiting.
 * In-flight work is memoised per photo id so fast-swiping (or a composition
 * being cancelled and restarted) can't run the same photo twice, and nothing
 * here throws: a missing model or an undecodable image just yields null.
 */
object OcrBoxStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = mutableMapOf<Long, Deferred<OcrLayout?>>()
    private var engine: OcrEngine? = null

    suspend fun layoutFor(context: Context, photo: Photo): OcrLayout? {
        if (photo.videoData != null) return null
        // Only the application context is retained: the engine outlives the
        // composable that asked for it (see the write-back note below).
        val appContext = context.applicationContext
        val repository = PhotosRepository.get(appContext)
        parseOcrLayout(repository.getOcrBoxes(photo.id))?.let { return it }

        // Tiny images (icons/thumbnails) are skipped by the indexer too.
        val largestDim = maxOf(photo.width, photo.height)
        if (largestDim in 1 until MIN_OCR_DIM) return null

        // Runs in [scope], not the caller's, so a swipe that cancels the caller
        // still finishes the write-back — and the next open reads it from the DB.
        val job = lock.withLock {
            inFlight.getOrPut(photo.id) {
                scope.async {
                    try {
                        recognizeAndStore(appContext, photo, repository)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "On-demand OCR failed for photo ${photo.id}", e)
                        null
                    } finally {
                        release(photo.id)
                    }
                }
            }
        }
        return job.await()
    }

    /** Forget a finished job and, once nothing else is queued, free the models. */
    private suspend fun release(photoId: Long) = lock.withLock {
        inFlight -= photoId
        if (inFlight.isEmpty()) {
            engine?.close()
            engine = null
        }
    }

    private suspend fun recognizeAndStore(
        context: Context,
        photo: Photo,
        repository: PhotosRepository,
    ): OcrLayout? {
        val ocr = lock.withLock { engine ?: OcrEngine(context).also { engine = it } }
        if (!ocr.isAvailable()) return null

        val bitmap = decodeForOcr(context, photo.uri.toUri()) ?: return null
        // The stored geometry is relative to this bitmap, so its dimensions have
        // to be read before it's recycled.
        val width = bitmap.width
        val height = bitmap.height
        val result = try {
            ocr.recognizeDetailed(bitmap)
        } finally {
            bitmap.recycle()
        }
        // An empty result is stored too, so a photo with no text in it isn't
        // re-OCR'd every time it's opened.
        val layout = result.toLayout(width, height)
        repository.setOcrBoxes(photo.id, layout.toJson())
        return layout
    }
}

/** Adapt an [OcrEngine.OcrResult] into the persisted [OcrLayout] shape. */
internal fun OcrEngine.OcrResult.toLayout(width: Int, height: Int) = OcrLayout(
    w = width,
    h = height,
    boxes = boxes.map { OcrBox(it.text, it.left, it.top, it.right, it.bottom) },
)

private const val TAG = "PhotoOcr"
