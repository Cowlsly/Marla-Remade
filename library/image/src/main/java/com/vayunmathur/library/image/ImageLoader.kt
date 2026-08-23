package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.image.decoders.BitmapDecoder
import com.vayunmathur.library.image.decoders.SvgDecoder
import com.vayunmathur.library.image.decoders.VideoFrameDecoder
import com.vayunmathur.library.image.fetchers.AssetFetcher
import com.vayunmathur.library.image.fetchers.BitmapFetcher
import com.vayunmathur.library.image.fetchers.ByteArrayFetcher
import com.vayunmathur.library.image.fetchers.ContentResolverFetcher
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.FileFetcher
import com.vayunmathur.library.image.fetchers.Fetcher
import com.vayunmathur.library.image.fetchers.HttpFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Replacement for `coil.ImageLoader`. Uses:
 * - [MemoryCache] (LruCache<String, Bitmap>)
 * - [DiskCache] (file LRU raw bytes)
 * - Fetcher registry (http/content/file/asset/bytearray/bitmap)
 * - Decoders: SVG via internal Android stdlib renderer (Canvas/Path), Video via MediaMetadataRetriever, Bitmap via BitmapFactory/ImageDecoder
 */
class ImageLoader private constructor(
    private val appContext: Context,
    val memoryCache: MemoryCache?,
    val diskCache: DiskCache?,
    private val fetchers: List<Fetcher>,
    private val respectCacheHeaders: Boolean = false,
) {

    /** Owns in-flight work so it outlives any one caller's cancellation. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Requests currently being fetched/decoded, keyed by cache key.
     *
     * A grid scrolling past several tiles of the same image used to fetch and decode
     * it once per tile; now the first request does the work and the rest await it.
     */
    private val inFlight = mutableMapOf<String, Deferred<ImageResult>>()

    companion object {
        @Volatile
        private var singleton: ImageLoader? = null

        fun get(context: Context): ImageLoader {
            return singleton ?: synchronized(this) {
                singleton ?: Builder(context.applicationContext).build().also { singleton = it }
            }
        }

        internal fun setDefault(loader: ImageLoader) {
            singleton = loader
        }
    }

    private fun dataKey(request: ImageRequest): String = when (val d = request.data) {
        null -> "null"
        is String -> d
        is ByteArray -> "bytes_${d.size}_${d.take(16).hashCode()}"
        is Bitmap -> "bitmap_${d.width}x${d.height}_${d.hashCode()}"
        else -> d.toString()
    }

    private fun computeCacheKey(request: ImageRequest): String {
        val sizeKey = request.size?.let { "${it.width}x${it.height}" } ?: "orig"
        val transKey = request.transformations.joinToString("|") { it.cacheKey }
        val videoKey = request.videoFrameMillis?.let { "vf_$it" } ?: ""
        // An explicit key names the *data*; size and transformations still have to be
        // folded in. Returning a caller's key verbatim meant every caller was
        // responsible for encoding its own size, and a caller that didn't (a 256 px
        // thumbnail key that says nothing about 256) collided by name with a request
        // for the same image at a different size and was served the wrong bitmap.
        val base = request.memoryCacheKey ?: request.diskCacheKey ?: dataKey(request)
        return "$base|$sizeKey|$transKey|$videoKey"
    }

    /**
     * True for data already sitting on this device's storage.
     *
     * The disk cache exists to avoid re-downloading; for a local file it only
     * duplicated the user's own photo library into `cacheDir` (at up to 5% of device
     * storage) to avoid reopening a file that is already there and cheap to reopen.
     */
    private fun isLocalData(data: Any?): Boolean = when (data) {
        is File -> true
        is Uri -> data.scheme.let { it == "content" || it == "file" || it == null }
        is String -> data.startsWith("content://") || data.startsWith("file://") || data.startsWith("/")
        else -> false
    }

    /**
     * Memory-cache lookup with no dispatch and no IO.
     *
     * [execute] is a suspend function that hops to [Dispatchers.IO] before it can
     * even check the memory cache, so a cached bitmap was never available in time
     * for the first frame. Callers on the main thread can use this to paint
     * immediately on a hit (see `AsyncImage`).
     */
    fun peekMemoryCache(request: ImageRequest): Bitmap? {
        if (request.data is Bitmap) return null
        return try {
            memoryCache?.get(computeCacheKey(request))?.takeIf { !it.isRecycled }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun execute(request: ImageRequest): ImageResult {
        // A Bitmap request has nothing to fetch or cache; transform and return it.
        // Still dispatched, because a transformation allocates and draws a bitmap and
        // the caller is usually on the main thread.
        if (request.data is Bitmap) {
            val bmp = request.data
            return withContext(Dispatchers.IO) {
                val transformed = applyTransformations(bmp, request)
                ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
            }
        }

        val cacheKey = computeCacheKey(request)
        peekMemoryCache(request)?.let {
            return ImageResult.Success(it, isFromMemory = true, dataSource = ImageResult.DataSource.MEMORY)
        }

        val deferred = synchronized(inFlight) {
            // `isCompleted`, not `isActive`: a CoroutineStart.LAZY Deferred sits in the
            // NEW state where isActive is false, so testing isActive would reject the
            // entry during exactly the window between registration and start() and let
            // a second caller duplicate the work.
            val existing = inFlight[cacheKey]?.takeIf { !it.isCompleted }
            if (existing != null) {
                existing
            } else {
                // LAZY so the entry is registered (and its cleanup hooked up) before
                // the work can start and try to remove itself.
                val started = scope.async(start = CoroutineStart.LAZY) { load(request, cacheKey) }
                inFlight[cacheKey] = started
                started.invokeOnCompletion {
                    synchronized(inFlight) {
                        // Only if still ours; a later request may have replaced it.
                        if (inFlight[cacheKey] === started) inFlight.remove(cacheKey)
                    }
                }
                started
            }
        }
        deferred.start()
        return deferred.await()
    }

    private suspend fun load(request: ImageRequest, cacheKey: String): ImageResult = withContext(Dispatchers.IO) {
        val context = request.context ?: appContext
        val diskKey = request.diskCacheKey ?: cacheKey
        val isLocal = isLocalData(request.data)

        // Another coalesced request may have filled the cache while this one waited.
        try {
            memoryCache?.get(cacheKey)?.let { cached ->
                return@withContext ImageResult.Success(cached, isFromMemory = true, dataSource = ImageResult.DataSource.MEMORY)
            }
        } catch (_: Exception) {}

        var diskBytes: ByteArray? = null
        if (diskCache != null && !isLocal) {
            try {
                diskBytes = diskCache.get(diskKey)
                if (diskBytes != null) {
                    val decodedFromDisk = decodeBytes(diskBytes, request, context)
                    if (decodedFromDisk != null) {
                        val transformed = applyTransformations(decodedFromDisk, request)
                        try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                        return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.DISK)
                    }
                }
            } catch (_: Exception) {}
        }

        if (request.videoFrameMillis != null) {
            try {
                val videoBmp = VideoFrameDecoder.decode(request, context)
                if (videoBmp != null) {
                    val transformed = applyTransformations(videoBmp, request)
                    try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                    return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
                }
            } catch (_: Exception) {}
        }

        var fetchedBytes: ByteArray? = null
        try {
            for (fetcher in fetchers) {
                val result = try { fetcher.fetch(request.data, context) } catch (_: Exception) { null }
                if (result != null) {
                    when (result) {
                        is FetchResult.BitmapResult -> {
                            val transformed = applyTransformations(result.bitmap, request)
                            try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                            return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.MEMORY)
                        }
                        is FetchResult.Bytes -> {
                            fetchedBytes = result.bytes
                            break
                        }
                        is FetchResult.Source -> {
                            // Fast path: decode (and downsample) straight from the file.
                            val decodedFromSource =
                                BitmapDecoder.decode(result.decoderSource, request, request.allowHardware)
                            if (decodedFromSource != null) {
                                val transformed = applyTransformations(decodedFromSource, request)
                                try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
                                return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.DISK)
                            }
                            // ImageDecoder can't read it (an SVG, or a video whose frame
                            // decoder needs the bytes): fall back to the byte path.
                            fetchedBytes = try {
                                result.openStream()?.use { it.readBytes() }
                            } catch (_: Exception) {
                                null
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ImageResult.Error(e)
        }

        if (fetchedBytes == null) {
            fetchedBytes = diskBytes
            if (fetchedBytes == null) {
                return@withContext ImageResult.Error(IllegalArgumentException("Unable to fetch data: ${request.data}"))
            }
        }

        val decoded = try {
            decodeBytes(fetchedBytes, request, context) ?: if (request.videoFrameMillis != null) {
                VideoFrameDecoder.decode(request, context)
            } else null
        } catch (e: Exception) {
            return@withContext ImageResult.Error(e)
        }

        if (decoded == null) {
            return@withContext ImageResult.Error(IllegalArgumentException("Failed to decode image bytes (${fetchedBytes.size} bytes)"))
        }

        val transformed = applyTransformations(decoded, request)

        try { memoryCache?.put(cacheKey, transformed) } catch (_: Exception) {}
        try {
            if (diskCache != null && !isLocal) {
                diskCache.put(diskKey, fetchedBytes)
            }
        } catch (_: Exception) {}

        return@withContext ImageResult.Success(transformed, isFromMemory = false, dataSource = ImageResult.DataSource.NETWORK)
    }

    private suspend fun decodeBytes(bytes: ByteArray, request: ImageRequest, context: Context): Bitmap? {
        if (SvgDecoder.canDecode(bytes, request.data)) {
            val svgBmp = SvgDecoder.decode(bytes, request)
            if (svgBmp != null) return svgBmp
        }
        return BitmapDecoder.decode(bytes, request, request.allowHardware)
    }

    private suspend fun applyTransformations(bitmap: Bitmap, request: ImageRequest): Bitmap {
        var current = bitmap
        for (t in request.transformations) {
            try {
                current = t.transform(current, request.size ?: Size.Original)
            } catch (_: Exception) {}
        }
        return current
    }

    class Builder(private val context: Context) {
        private var memoryCacheInstance: MemoryCache? = null
        private var diskCacheInstance: DiskCache? = null
        private var respectCacheHeaders: Boolean = true
        private val extraFetchers: MutableList<Fetcher> = mutableListOf()

        inner class ComponentsBuilder {
            fun add(factory: Any): ComponentsBuilder = this
        }

        fun components(block: ComponentsBuilder.() -> Unit): Builder {
            val cb = ComponentsBuilder()
            cb.block()
            return this
        }

        fun memoryCache(cache: MemoryCache): Builder {
            memoryCacheInstance = cache
            return this
        }

        fun memoryCache(block: () -> MemoryCache): Builder {
            memoryCacheInstance = block()
            return this
        }

        fun diskCache(cache: DiskCache): Builder {
            diskCacheInstance = cache
            return this
        }

        fun diskCache(block: () -> DiskCache): Builder {
            diskCacheInstance = block()
            return this
        }

        fun respectCacheHeaders(respect: Boolean): Builder {
            respectCacheHeaders = respect
            return this
        }

        fun addFetcher(fetcher: Fetcher): Builder {
            extraFetchers += fetcher
            return this
        }

        fun build(): ImageLoader {
            val mem = memoryCacheInstance ?: MemoryCache.Builder(context.applicationContext).build()
            val disk = diskCacheInstance ?: try {
                val dir = context.applicationContext.cacheDir.resolve("image_cache")
                DiskCache.Builder().directory(dir).maxSizePercent(0.05).build()
            } catch (_: Exception) { null }

            val defaultFetchers = listOf(
                BitmapFetcher(),
                ByteArrayFetcher(),
                FileFetcher(),
                AssetFetcher(),
                ContentResolverFetcher(),
                HttpFetcher(),
            )
            val allFetchers = extraFetchers + defaultFetchers

            return ImageLoader(
                appContext = context.applicationContext,
                memoryCache = mem,
                diskCache = disk,
                fetchers = allFetchers,
                respectCacheHeaders = respectCacheHeaders,
            )
        }
    }
}
