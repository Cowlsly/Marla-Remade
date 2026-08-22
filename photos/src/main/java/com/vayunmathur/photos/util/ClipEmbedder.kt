package com.vayunmathur.photos.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device **TinyCLIP-ViT-8M/16 Text-3M** semantic embedder for photo search.
 *
 * Photos runs its own embedder again. It previously delegated to the OpenAssistant app over IPC
 * for SigLIP2 vectors, which meant semantic search only worked if a second app was installed and
 * had finished a ~452 MB model download. The model now ships in the APK
 * (`assets/clip/model_int8.onnx`, 24 MB int8) so search works on a clean install with no network.
 *
 * The int8 quantization is close to free here: measured cosine against the fp32 export is 0.998
 * for image embeddings and 0.9995 for text, at the same latency. TinyCLIP is a plain ViT, which
 * quantizes cleanly — unlike depthwise-conv architectures where dynamic int8 collapses.
 *
 * The export is a **single combined graph**: `input_ids` + `pixel_values` + `attention_mask` are
 * all required on every call, and it returns both `image_embeds` and `text_embeds`. So each call
 * passes a cheap dummy for the side it does not need (see [DUMMY_IDS] / [dummyPixels]) and reads
 * only the output it wants. The text tower is 3M params, so the waste is small.
 *
 * Vectors are L2-normalised, stored on the [com.vayunmathur.photos.data.Photo] row by
 * [runClipIndexing], and compared with [cosine] at query time.
 */
object ClipEmbedder {

    /** HuggingFace source, also the id stored for change-detection re-indexing. */
    const val MODEL_ID = "onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX"

    /**
     * Bump whenever the embedding space changes so [runClipIndexing] clears every stored vector
     * and re-indexes. 3 = TinyCLIP's 512-d space (2 was OpenAssistant's 768-d SigLIP2).
     */
    const val EMBEDDER_VERSION = 3

    /** Whether semantic search can run at all. */
    enum class Support { READY, UNAVAILABLE }

    /** Model identity + dimension behind the vectors, for change-detection re-index. */
    data class Info(val modelId: String, val dim: Int)

    /** One image could not be embedded, but the embedder itself is healthy. */
    class ImageFailedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private const val TAG = "ClipEmbedder"

    private const val MODEL_ASSET = "clip/model_int8.onnx"

    /** Square RGB input side the vision tower expects (`preprocessor_config.json`). */
    private const val IMAGE_SIZE = 224

    /** CLIP's channel normalisation, from `preprocessor_config.json`. */
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: ClipTokenizer? = null
    @Volatile private var initTried = false
    @Volatile private var cachedDim = 0

    /** Fast capability probe. Loads the model on first call. */
    fun embeddingSupport(context: Context): Support =
        if (ensureInit(context)) Support.READY else Support.UNAVAILABLE

    fun embeddingInfo(context: Context): Info {
        ensureInit(context)
        return Info(MODEL_ID, cachedDim)
    }

    private fun ensureInit(context: Context): Boolean {
        session?.let { return true }
        synchronized(lock) {
            session?.let { return true }
            if (initTried) return false
            initTried = true
            return try {
                val app = context.applicationContext
                val tok = ClipTokenizer.load(app)
                    ?: error("CLIP merges asset missing")
                val opts = OrtSession.SessionOptions().apply {
                    // Single-threaded keeps sustained CPU/battery use low during a long
                    // indexing run; the model is only 24 MB.
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                // Read from assets rather than copying 24 MB into filesDir.
                val bytes = app.assets.open(MODEL_ASSET).use { it.readBytes() }
                val s = env.createSession(bytes, opts)
                cachedDim = (s.outputInfo[OUT_IMAGE]?.info as? ai.onnxruntime.TensorInfo)
                    ?.shape?.last()?.toInt() ?: 512
                session = s
                tokenizer = tok
                Log.i(TAG, "TinyCLIP embedder ready (dim=$cachedDim)")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialise TinyCLIP embedder", t)
                closeLocked()
                false
            }
        }
    }

    /**
     * Embed [uri] into an L2-normalised image vector.
     *
     * @throws ImageFailedException if this particular image cannot be decoded.
     * @throws IllegalStateException if the embedder is unavailable.
     */
    suspend fun imageEmbedding(context: Context, uri: Uri): FloatArray =
        withContext(Dispatchers.Default) {
            check(ensureInit(context)) { "TinyCLIP embedder unavailable" }
            val bitmap = decode(context, uri)
                ?: throw ImageFailedException("could not decode $uri")
            val pixels = try {
                preprocess(bitmap)
            } finally {
                bitmap.recycle()
            }
            run(pixels, DUMMY_IDS, OUT_IMAGE)
        }

    /** Embed [query] into an L2-normalised vector in the SAME space as [imageEmbedding]. */
    suspend fun textEmbedding(context: Context, query: String): FloatArray =
        withContext(Dispatchers.Default) {
            check(ensureInit(context)) { "TinyCLIP embedder unavailable" }
            val tok = tokenizer ?: error("tokenizer not loaded")
            run(dummyPixels(), tok.tokenize(query), OUT_TEXT)
        }

    /**
     * One forward pass of the combined graph, returning the L2-normalised [wanted] output.
     * Both inputs must always be supplied even though only one side is read.
     */
    private fun run(pixels: FloatArray, ids: IntArray, wanted: String): FloatArray {
        val s = session ?: error("session not loaded")
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        // CLIP's text transformer is causal and pools at the end-of-text position, so an
        // all-ones mask over the padded sequence matches how the model was trained.
        val mask = LongArray(ids.size) { 1L }
        val idShape = longArrayOf(1, ids.size.toLong())

        return synchronized(lock) {
            val tensors = LinkedHashMap<String, OnnxTensor>(3)
            try {
                tensors[IN_PIXELS] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(pixels),
                    longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
                )
                tensors[IN_IDS] = OnnxTensor.createTensor(env, LongBuffer.wrap(longIds), idShape)
                tensors[IN_MASK] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), idShape)
                s.run(tensors).use { result ->
                    val out = result.get(wanted).get() as OnnxTensor
                    val vec = FloatArray(out.info.shape.last().toInt())
                    out.floatBuffer.get(vec)
                    l2Normalize(vec)
                }
            } finally {
                tensors.values.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Decode [uri], downsampling during decode so a 48 MP photo never lands on the heap. */
    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var scale = 1
        while (bounds.outWidth / (scale * 2) >= IMAGE_SIZE &&
            bounds.outHeight / (scale * 2) >= IMAGE_SIZE
        ) {
            scale *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = scale
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: Throwable) {
        Log.w(TAG, "decode failed for $uri", t)
        null
    }

    /**
     * CLIP preprocessing: resize so the shortest edge is [IMAGE_SIZE], centre-crop to a square,
     * rescale to `[0,1]`, normalise by [MEAN]/[STD], pack NCHW (RGB).
     */
    private fun preprocess(src: Bitmap): FloatArray {
        val safe = if (src.config == Bitmap.Config.HARDWARE || src.config == null) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: src
        } else {
            src
        }

        // Shortest-edge resize preserving aspect ratio, then centre crop.
        val ratio = IMAGE_SIZE.toFloat() / minOf(safe.width, safe.height)
        val rw = (safe.width * ratio).roundToInt().coerceAtLeast(IMAGE_SIZE)
        val rh = (safe.height * ratio).roundToInt().coerceAtLeast(IMAGE_SIZE)
        val scaled = Bitmap.createScaledBitmap(safe, rw, rh, true)
        val left = (rw - IMAGE_SIZE) / 2
        val top = (rh - IMAGE_SIZE) / 2

        val px = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(px, 0, IMAGE_SIZE, left, top, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== safe) scaled.recycle()
        if (safe !== src) safe.recycle()

        val area = IMAGE_SIZE * IMAGE_SIZE
        val out = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = px[i]
            out[i] = ((((p shr 16) and 0xFF) / 255f) - MEAN[0]) / STD[0]
            out[area + i] = ((((p shr 8) and 0xFF) / 255f) - MEAN[1]) / STD[1]
            out[2 * area + i] = (((p and 0xFF) / 255f) - MEAN[2]) / STD[2]
        }
        return out
    }

    /** Zeroed pixels for a text-only call; the image side of the graph is ignored. */
    private fun dummyPixels() = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
        cachedDim = 0
        initTried = false
    }

    // ---- Provider-agnostic math + (de)serialisation helpers (unchanged) ----

    /** Cosine similarity of two vectors (unit vectors → just the dot product). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return -1f
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun l2Normalize(values: FloatArray): FloatArray {
        var norm = 0f
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return values
        return FloatArray(values.size) { values[it] / norm }
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }

    private const val IN_PIXELS = "pixel_values"
    private const val IN_IDS = "input_ids"
    private const val IN_MASK = "attention_mask"
    private const val OUT_IMAGE = "image_embeds"
    private const val OUT_TEXT = "text_embeds"

    /** Minimal `<|startoftext|><|endoftext|>` prompt for image-only calls. */
    private val DUMMY_IDS = IntArray(ClipTokenizer.CONTEXT_LENGTH).also {
        it[0] = ClipTokenizer.START_TOKEN
        it[1] = ClipTokenizer.END_TOKEN
    }
}
