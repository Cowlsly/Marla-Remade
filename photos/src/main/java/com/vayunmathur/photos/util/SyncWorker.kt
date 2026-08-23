package com.vayunmathur.photos.util
import com.vayunmathur.photos.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.ExifResult
import com.vayunmathur.photos.data.OcrResult
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.toJson
import com.vayunmathur.photos.domain.MIN_OCR_DIM
import com.vayunmathur.photos.domain.decodeForOcr
import com.vayunmathur.photos.domain.toLayout
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo())
        val repository = PhotosRepository.get(applicationContext)
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        
        val triggeredUris = triggeredContentUris
        val lastGeneration = dataStore.getLong("last_photos_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        if (triggeredUris.isNotEmpty()) {
            syncPhotos(applicationContext, repository, triggeredUris.toList())
        } else {
            // Rows predating the mimeType column are invisible to an incremental
            // scan (their GENERATION_MODIFIED hasn't moved), so scan everything
            // until they're all backfilled.
            val needsMimeBackfill = repository.countMissingMimeType() > 0
            syncPhotos(applicationContext, repository, null, if (needsMimeBackfill) 0L else lastGeneration)
        }
        
        setExifData(repository, applicationContext)
        
        // OCR and face grouping are both always on (no opt-in). Each worker is
        // inert if its data/model assets are missing.
        OCRWorker.enqueue(applicationContext)
        FaceWorker.enqueue(applicationContext)
        ClipWorker.enqueue(applicationContext)
        
        dataStore.setLong("last_photos_generation", currentGeneration)
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 101,
        channelId = "sync_worker",
        channelName = "Photo Sync",
        title = "Syncing Photos",
        text = "Indexing photos and extracting text...",
    )

    companion object {
        const val WORK_NAME = "SyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(500, TimeUnit.MILLISECONDS)
                .setTriggerContentMaxDelay(2, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class OCRWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        ocrMutex.withLock {
            setForeground(createForegroundInfo())
            val repository = PhotosRepository.get(applicationContext)
            runOCR(repository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 102,
        channelId = "ocr_worker",
        channelName = "Photo Indexing",
        title = "Analyzing Photos",
        text = "Reading text in your photos...",
    )

    companion object {
        private const val WORK_NAME = "OCRWorker"
        private val ocrMutex = Mutex() // Shared across all instances of OCRWorker


        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OCRWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

suspend fun syncPhotos(context: Context, repository: PhotosRepository, uris: List<Uri>? = null, lastGeneration: Long = 0L) {
    // Single read of the local DB reused for both deletion detection and update diffing.
    val existing = repository.getAll()
    // 1. Get all IDs currently in MediaStore to detect deletions
    val allMediaStoreIds = mutableSetOf<Long>()
    fun collectIds(baseUri: Uri) {
        try {
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    try {
                        allMediaStoreIds.add(cursor.getLong(idCol))
                    } catch (e: Exception) {
                        Log.e("SyncWorker", "Error reading ID from MediaStore cursor", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error querying MediaStore for IDs: $baseUri", e)
        }
    }
    collectIds(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    collectIds(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    // 2. Handle deletions
    val localIds = existing.map { it.id }.toSet()
    val toDelete = if (uris != null) {
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            repository.deleteByIds(chunk)
        }
    }

    // 3. Process additions/updates
    val selection = when {
        uris != null -> {
            val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (ids.isEmpty()) null else "_id IN (${ids.joinToString(",")})"
        }
        lastGeneration > 0 -> {
            "${MediaStore.MediaColumns.GENERATION_MODIFIED} > $lastGeneration"
        }
        else -> null
    }

    val existingPhotos = existing.associateBy { it.id }
    val newOrUpdatedPhotos = mutableListOf<Photo>()

    fun processCursor(cursor: android.database.Cursor, isVideo: Boolean) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val isTrashedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val durationColumn = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        while (cursor.moveToNext()) {
            try {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateTaken = cursor.getLongOrNull(dateTakenColumn)
                val date = if (dateTaken != null && dateTaken > 0) dateTaken else (cursor.getLong(dateAddedColumn) * 1000)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val isTrashed = cursor.getInt(isTrashedColumn) == 1
                val mimeType = cursor.getString(mimeTypeColumn)
                val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
                val videoData = if (isVideo) VideoData(cursor.getLong(durationColumn)) else null

                val existing = existingPhotos[id]
                if (existing == null || existing.date != date || existing.uri != contentUri || existing.videoData != videoData || existing.width != width || existing.height != height || existing.dateModified != dateModified || existing.isTrashed != isTrashed || existing.mimeType != mimeType) {
                    newOrUpdatedPhotos += Photo(id, name, contentUri, date, width, height, dateModified, existing?.exifSet ?: false, existing?.lat, existing?.long, videoData, existing?.panoData, isTrashed, faceScanned = existing?.faceScanned ?: false, ocrText = existing?.ocrText, ocrScanned = existing?.ocrScanned ?: false, mimeType = mimeType)
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Error processing photo/video from cursor", e)
            }
        }
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.IS_TRASHED, MediaStore.Images.Media.MIME_TYPE),
            bundle, null
        )?.use { processCursor(it, false) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for images", e)
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.IS_TRASHED, MediaStore.Video.Media.MIME_TYPE),
            bundle, null
        )?.use { processCursor(it, true) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for videos", e)
    }

    if (newOrUpdatedPhotos.isNotEmpty()) {
        repository.upsertAll(newOrUpdatedPhotos)
    }
}

private fun Context.syncForegroundInfo(
    notificationId: Int,
    channelId: String,
    channelName: String,
    title: String,
    text: String,
): ForegroundInfo {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    )
    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()
    return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

/**
 * Parse EXIF/XMP for every photo that hasn't been through it yet and write the
 * geo/panorama columns back.
 *
 * Driven off a projection of the un-parsed rows rather than the whole library:
 * this used to be handed `getAll()` (a `SELECT *` over every photo, embeddings
 * included) and wrote those same blobs straight back via `upsertAll`. The write
 * is column-targeted for the same reason — reconstructing a [Photo] from the
 * projection and upserting it would write NULL over every clipEmbedding.
 */
suspend fun setExifData(repository: PhotosRepository, context: Context) = coroutineScope {
    val targets = repository.getUnscannedForExif()
    targets.chunked(EXIF_CHUNK).forEach { chunk ->
        val results = chunk.map { target ->
            async(Dispatchers.IO) {
                try {
                    val (latLong, panoData) = context.contentResolver.openInputStream(
                        MediaStore.setRequireOriginal(
                            target.uri.toUri()
                        )
                    )?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val ll = exif.latLong
                        val pano = PanoXmpParser.parse(exif.getAttribute(ExifInterface.TAG_XMP))
                        Pair(ll, pano)
                    } ?: Pair(null, null)
                    ExifResult(
                        id = target.id,
                        lat = latLong?.getOrNull(0),
                        long = latLong?.getOrNull(1),
                        pano = panoData,
                    )
                } catch (_: Exception) {
                    // Mark as set even on error to avoid retry every time.
                    ExifResult(id = target.id, lat = null, long = null, pano = null)
                }
            }
        }.awaitAll()
        repository.setExifResults(results)
    }
}

private const val EXIF_CHUNK = 50

/**
 * Sustained on-device AI indexing (OCR, CLIP, face models) heats the phone.
 * On top of the short per-item pauses, each indexing loop takes a longer
 * "cooling break" every [BATCH_COOLDOWN_EVERY] items it actually ran inference
 * on, idling for [BATCH_COOLDOWN_MS] so the device can shed heat before the next
 * batch. [processed] is the running count of AI-processed items in this run.
 */
private suspend fun coolDownBetweenBatches(processed: Int, tag: String) {
    if (processed > 0 && processed % BATCH_COOLDOWN_EVERY == 0) {
        Log.i(tag, "Cooling break after $processed items: pausing ${BATCH_COOLDOWN_MS}ms to let the device cool")
        delay(BATCH_COOLDOWN_MS)
    }
}

// Number of AI-processed items between cooling breaks, and how long each break
// lasts. Kept modest so indexing still makes steady progress in the background.
private const val BATCH_COOLDOWN_EVERY = 20
private const val BATCH_COOLDOWN_MS = 5_000L

suspend fun runOCR(repository: PhotosRepository, context: Context) = coroutineScope {
    val photos = repository.getUnscannedForOCR()
    if (photos.isEmpty()) return@coroutineScope

    val ocrEngine = OcrEngine(context)
    // Inert (but no crash) if the OCR model assets can't be loaded.
    if (!ocrEngine.isAvailable()) {
        Log.w("OCRWorker", "OCR models unavailable; skipping OCR")
        return@coroutineScope
    }

    // Accumulated so a scan invalidates the Photo table once per flush rather
    // than once per photo. Flushed in a `finally` so cancellation and errors
    // still persist the work already done.
    val pendingResults = mutableListOf<OcrResult>()
    val pendingSkipped = mutableListOf<Long>()
    suspend fun flush() {
        if (pendingResults.isNotEmpty()) {
            repository.setOcrResults(pendingResults.toList())
            pendingResults.clear()
        }
        if (pendingSkipped.isNotEmpty()) {
            repository.setOcrScanned(pendingSkipped.toList())
            pendingSkipped.clear()
        }
    }

    var processed = 0
    try {
        for (photo in photos) {
            ensureActive()

            // Skip tiny images (icons/thumbnails); mark scanned so we don't retry.
            val largestDim = maxOf(photo.width, photo.height)
            if (largestDim in 1 until MIN_OCR_DIM) {
                pendingSkipped += photo.id
                if (pendingSkipped.size >= INDEX_FLUSH_EVERY) flush()
                continue
            }

            val result = try {
                val bitmap = decodeForOcr(context, photo.uri.toUri())
                if (bitmap != null) {
                    // Boxes are stored relative to this bitmap, so read its size
                    // before recycling it.
                    val width = bitmap.width
                    val height = bitmap.height
                    try {
                        ocrEngine.recognizeDetailed(bitmap).toLayout(width, height)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("OCRWorker", "Error running OCR for photo ${photo.id}", e)
                null
            }

            // Store result and mark scanned regardless of outcome (mirrors faces).
            val text = result?.text
            pendingResults += OcrResult(
                id = photo.id,
                text = text,
                boxes = result?.takeIf { it.boxes.isNotEmpty() }?.toJson(),
            )
            if (pendingResults.size >= INDEX_FLUSH_EVERY) flush()
            Log.i("OCRWorker", "OCR for ${photo.id}: ${text?.take(50)?.replace("\n", " ")}")

            // Short pause between images keeps sustained CPU/battery use low.
            delay(OCR_INTER_ITEM_DELAY_MS)
            // Longer cooling break every batch so the device can shed heat.
            processed++
            coolDownBetweenBatches(processed, "OCRWorker")
        }
    } finally {
        ocrEngine.close()
        // runCatching so a failed flush cannot replace the exception that unwound us
        // (a CancellationException in particular). Unflushed photos simply stay
        // unscanned and are picked up by the next run.
        withContext(NonCancellable) { runCatching { flush() } }
    }
}

/**
 * How many indexed photos accumulate before their rows are written. Each flush
 * is one transaction and therefore one Photo-table invalidation, which every
 * gallery flow reacts to — at one write per photo the UI spent a scan
 * recomputing rather than drawing.
 */
private const val INDEX_FLUSH_EVERY = 25

private const val OCR_INTER_ITEM_DELAY_MS = 250L

class ClipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        clipMutex.withLock {
            setForeground(createForegroundInfo())
            val repository = PhotosRepository.get(applicationContext)
            runClipIndexing(repository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 104,
        channelId = "clip_worker",
        channelName = "Photo Search Indexing",
        title = "Understanding Photos",
        text = "Indexing photos for visual search on-device...",
    )

    companion object {
        const val WORK_NAME = "ClipWorker"
        private val clipMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClipWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Embed any not-yet-embedded library photos into a SigLIP2 vector — served by
 * the bundled **TinyCLIP** model (see [ClipEmbedder]) — and store the L2-normalised
 * vector on the [Photo] row so semantic search can cosine-compare them against
 * the query's text embedding.
 *
 * Mirrors [runOCR]: incremental (only un-embedded, non-video photos), one image
 * at a time, throttled between items to keep battery/CPU low, and marks each
 * photo scanned regardless of per-image outcome so we never retry it forever.
 *
 * Gated on the bundled model loading ([ClipEmbedder.embeddingSupport]): if it cannot be opened
 * (corrupt install), skip indexing and leave `clipScanned=0` so OCR/filename search still works.
 */
suspend fun runClipIndexing(repository: PhotosRepository, context: Context) = coroutineScope {
    val dataStore = DataStoreUtils.getInstance(context)

    // The model ships in the APK, so this only fails on a broken install.
    if (ClipEmbedder.embeddingSupport(context) != ClipEmbedder.Support.READY) {
        Log.w("ClipWorker", "TinyCLIP embedder unavailable; skipping semantic indexing")
        return@coroutineScope
    }

    // If the embedder version OR the model id changed, old embeddings are incompatible (e.g.
    // OpenAssistant's 768-d SigLIP2 space -> TinyCLIP's 512-d): clear them and re-embed every
    // photo. Photo rows themselves (OCR text, faces) are untouched.
    val modelId = ClipEmbedder.embeddingInfo(context).modelId
    val storedVersion = dataStore.getLong("clip_embedder_version") ?: 0L
    val storedModelId = dataStore.getString("clip_model_id")
    if (storedVersion != ClipEmbedder.EMBEDDER_VERSION.toLong() || storedModelId != modelId) {
        repository.resetClipScanned()
        dataStore.setLong("clip_embedder_version", ClipEmbedder.EMBEDDER_VERSION.toLong())
        dataStore.setString("clip_model_id", modelId)
    }

    val photos = repository.getUnscannedForClip()
    if (photos.isEmpty()) return@coroutineScope

    // See INDEX_FLUSH_EVERY: batched so a scan doesn't invalidate the Photo
    // table once per embedded photo.
    val pending = mutableListOf<ClipResult>()
    suspend fun flush() {
        if (pending.isEmpty()) return
        repository.setClipResults(pending.toList())
        pending.clear()
    }

    var processed = 0
    try {
        for (photo in photos) {
            ensureActive()

            val t0 = System.currentTimeMillis()
            val embedding = try {
                ClipEmbedder.imageEmbedding(context, photo.uri.toUri())
            } catch (e: ClipEmbedder.ImageFailedException) {
                // The embedder is healthy but this one image couldn't be decoded: mark it scanned
                // with no vector so we skip it rather than blocking the queue on it forever.
                Log.w("ClipWorker", "Skipping un-embeddable photo ${photo.id}: ${e.message}")
                pending += ClipResult(id = photo.id, embedding = null)
                if (pending.size >= INDEX_FLUSH_EVERY) flush()
                delay(CLIP_INTER_ITEM_DELAY_MS)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else is systemic (model failed to load): stop the run WITHOUT marking
                // scanned so these photos retry on the next pass.
                Log.w("ClipWorker", "Embedding unavailable; pausing indexing", e)
                return@coroutineScope
            }

            Log.d("ClipWorker", "Embedded photo ${photo.id} (${embedding.size}d) in ${System.currentTimeMillis() - t0}ms")
            pending += ClipResult(id = photo.id, embedding = ClipEmbedder.floatsToBytes(embedding))
            if (pending.size >= INDEX_FLUSH_EVERY) flush()

            // Short pause between images keeps sustained CPU/battery use low.
            delay(CLIP_INTER_ITEM_DELAY_MS)
            // Longer cooling break every batch so the device can shed heat.
            processed++
            coolDownBetweenBatches(processed, "ClipWorker")
        }
    } finally {
        // Covers the systemic-failure return above as well as cancellation, so
        // photos already embedded in this run are never re-embedded.
        withContext(NonCancellable) { runCatching { flush() } }
    }
}

private const val CLIP_INTER_ITEM_DELAY_MS = 250L

class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        faceMutex.withLock {
            setForeground(createForegroundInfo())
            val repository = PhotosRepository.get(applicationContext)
            runFaceIndexing(repository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 103,
        channelId = "face_worker",
        channelName = "People Indexing",
        title = "Finding People",
        text = "Grouping photos of the same person on-device...",
    )

    companion object {
        const val WORK_NAME = "FaceWorker"
        private val faceMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FaceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Scan any not-yet-scanned library photos for faces, then group each detected
 * face into a [Person] cluster by cosine similarity of its embedding — all
 * on-device and unsupervised.
 *
 * Clustering is greedy and incremental: each face joins the nearest existing
 * cluster if similarity >= [FaceRecognizer.CLUSTER_THRESHOLD], otherwise it
 * starts a new cluster. Each cluster keeps a running-mean [Person.centroid] that
 * is updated as faces are added, so we never have to re-scan old photos.
 */
suspend fun runFaceIndexing(repository: PhotosRepository, context: Context) {
    val dataStore = DataStoreUtils.getInstance(context)

    // Feature is inert without the on-device models (see FaceRecognizer docs).
    // Return WITHOUT marking photos scanned so they get processed once the
    // model assets are present.
    if (!FaceRecognizer.modelsAvailable(context)) {
        Log.w("FaceWorker", "Face models missing; skipping face indexing")
        return
    }

    // If the embedder model/version changed, the old embeddings are
    // incompatible: drop all clusters + faces and re-scan every photo so they
    // get re-grouped with the new model. Photo rows themselves are untouched.
    val storedVersion = dataStore.getLong("face_embedder_version") ?: 0L
    if (storedVersion != FaceRecognizer.EMBEDDER_VERSION.toLong()) {
        repository.clearPersons()
        repository.clearPhotoFaces()
        repository.resetFaceScanned()
        dataStore.setLong("face_embedder_version", FaceRecognizer.EMBEDDER_VERSION.toLong())
    }

    // Load existing clusters into memory once; centroids are cached as floats and
    // updated in place so we avoid re-reading them for every face.
    val clusters = repository.getPersons()
        .map { Cluster(it, FaceRecognizer.bytesToFloats(it.centroid)) }
        .toMutableList()

    val photos = repository.getUnscannedForFaces()
    Log.i("FaceWorker", "Face indexing start: ${photos.size} photos to scan, ${clusters.size} existing clusters")
    var facesTotal = 0
    var photosWithFaces = 0
    var decoded = 0

    // See INDEX_FLUSH_EVERY. Face rows are accumulated alongside the scanned ids and
    // committed with them in one transaction, so a batch is all-or-nothing: a worker
    // killed mid-batch cannot leave rows behind that a re-scan would duplicate.
    val pendingScanned = mutableListOf<Long>()
    val pendingFaces = mutableListOf<PhotoFace>()
    suspend fun flush() {
        if (pendingScanned.isEmpty()) return
        repository.commitFaceScan(pendingScanned.toList(), pendingFaces.toList())
        pendingScanned.clear()
        pendingFaces.clear()
    }

    try {
        for (photo in photos) {
            currentCoroutineContext().ensureActive()

            var didInference = false
            try {
                val bitmap = loadBitmapForFaces(context, photo.uri.toUri())
                if (bitmap != null) {
                    decoded++
                    didInference = true
                    // Read before recycle: the box columns are normalised against
                    // this bitmap, so its dimensions have to be stored with them.
                    val srcWidth = bitmap.width
                    val srcHeight = bitmap.height
                    val faces = FaceRecognizer.detectAndEmbed(context, bitmap)
                    bitmap.recycle()
                    if (faces.isNotEmpty()) {
                        photosWithFaces++; facesTotal += faces.size
                        Log.i("FaceWorker", "photo ${photo.id}: ${faces.size} face(s) (running total: $facesTotal)")
                    }
                    pendingFaces += faces.map { face ->
                        val clusterId = assignToCluster(face, clusters, repository)
                        PhotoFace(
                            photoId = photo.id,
                            clusterId = clusterId,
                            embedding = FaceRecognizer.floatsToBytes(face.embedding),
                            left = face.left,
                            top = face.top,
                            right = face.right,
                            bottom = face.bottom,
                            srcWidth = srcWidth,
                            srcHeight = srcHeight,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
            }

            // Mark scanned regardless of outcome so we don't retry forever.
            pendingScanned += photo.id
            if (pendingScanned.size >= INDEX_FLUSH_EVERY) flush()

            // Face indexing runs two ONNX models per photo (detector + embedder), so
            // pace it like OCR/CLIP: a short pause after each photo we actually ran
            // inference on, plus a longer cooling break every batch.
            if (didInference) {
                delay(FACE_INTER_ITEM_DELAY_MS)
                coolDownBetweenBatches(decoded, "FaceWorker")
            }
        }
    } finally {
        withContext(NonCancellable) { runCatching { flush() } }
    }
    Log.i("FaceWorker", "Face indexing done: decoded=$decoded/${photos.size}, $facesTotal faces in $photosWithFaces photos, ${repository.getPersons().size} clusters")

    // Second pass: fold together clusters whose centroids ended up very close,
    // which trims duplicate person-groups created early in the scan.
    mergeSimilarClusters(repository)
}

/** A cluster held in memory during a scan: its [Person] row plus cached centroid. */
private class Cluster(var person: Person, var centroid: FloatArray)

private const val FACE_INTER_ITEM_DELAY_MS = 250L

/**
 * Put [face] in the nearest cluster above [FaceRecognizer.CLUSTER_THRESHOLD],
 * updating that cluster's running-mean centroid, or start a new cluster. Returns
 * the cluster (person) id the face was assigned to.
 */
private suspend fun assignToCluster(
    face: FaceRecognizer.DetectedFace,
    clusters: MutableList<Cluster>,
    repository: PhotosRepository,
): Long {
    var best: Cluster? = null
    var bestSim = FaceRecognizer.CLUSTER_THRESHOLD
    for (cluster in clusters) {
        val sim = FaceRecognizer.similarity(face.embedding, cluster.centroid)
        if (sim >= bestSim) {
            bestSim = sim
            best = cluster
        }
    }

    if (best != null) {
        val n = best.person.faceCount
        val mean = FloatArray(best.centroid.size) { i ->
            (best.centroid[i] * n + face.embedding[i]) / (n + 1)
        }
        // Centroid is a running mean kept L2-normalised so cosine stays well-behaved.
        best.centroid = FaceRecognizer.l2Normalize(mean)
        best.person = best.person.copy(
            centroid = FaceRecognizer.floatsToBytes(best.centroid),
            faceCount = n + 1,
        )
        // Column-targeted, so a name the user enters mid-scan is not overwritten
        // by the null carried in this snapshot (loaded once at scan start).
        repository.updateClusterCentroid(
            id = best.person.id,
            centroid = best.person.centroid,
            faceCount = best.person.faceCount,
        )
        return best.person.id
    }

    val person = Person(
        centroid = FaceRecognizer.floatsToBytes(face.embedding),
        faceCount = 1,
        name = null,
    )
    val id = repository.insertPerson(person)
    clusters += Cluster(person.copy(id = id), face.embedding.copyOf())
    return id
}

/**
 * Greedily merge clusters whose centroids are within [FaceRecognizer.MERGE_THRESHOLD]
 * cosine of each other. Faces from the merged cluster are moved over and the
 * centroid becomes the face-count-weighted, L2-normalised mean. O(n^2) over the
 * (small) number of person-clusters.
 */
private suspend fun mergeSimilarClusters(repository: PhotosRepository) {
    val persons = repository.getPersons().toMutableList()
    var i = 0
    while (i < persons.size) {
        var j = i + 1
        while (j < persons.size) {
            val a = persons[i]
            val b = persons[j]
            val sim = FaceRecognizer.similarity(
                FaceRecognizer.bytesToFloats(a.centroid),
                FaceRecognizer.bytesToFloats(b.centroid),
            )
            if (sim >= FaceRecognizer.MERGE_THRESHOLD) {
                val na = a.faceCount
                val nb = b.faceCount
                val ca = FaceRecognizer.bytesToFloats(a.centroid)
                val cb = FaceRecognizer.bytesToFloats(b.centroid)
                val mean = FloatArray(ca.size) { (ca[it] * na + cb[it] * nb) / (na + nb) }
                val merged = a.copy(
                    centroid = FaceRecognizer.floatsToBytes(FaceRecognizer.l2Normalize(mean)),
                    faceCount = na + nb,
                    // A name is the one thing here the user typed, so the survivor
                    // inherits it from whichever side has one rather than losing it
                    // with the discarded row.
                    name = a.name ?: b.name,
                )
                repository.reassignCluster(b.id, a.id)
                // The name is resolved in SQL against the row's current value, so a
                // name entered since `persons` was read still wins over this snapshot.
                repository.mergeClusterInto(
                    id = a.id,
                    centroid = merged.centroid,
                    faceCount = merged.faceCount,
                    fallbackName = b.name,
                )
                repository.deletePerson(b.id)
                persons[i] = merged
                persons.removeAt(j)
            } else {
                j++
            }
        }
        i++
    }
}

private fun loadBitmapForFaces(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            val target = 720
            if (maxDim > target) {
                val scale = target.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        Log.e("FaceWorker", "Failed to decode $uri for faces", e)
        null
    }
}
