package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vayunmathur.library.ml.DetectedFaceBox
import com.vayunmathur.library.ml.FaceDetector
import com.vayunmathur.library.ml.FaceEmbedder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device face detection + recognition. Everything runs locally on `:library:ml`'s
 * Vulkan compute runtime — no ONNX Runtime, no Google Play Services, F-Droid clean.
 *
 * Pipeline:
 *  1. Detection — **SCRFD 500M** ([FaceDetector]) finds faces and returns a bounding box
 *     + 5 landmarks (the two eyes are used for alignment). Letterboxing, anchor decode
 *     and NMS all run natively.
 *  2. Alignment — the two eye landmarks drive a similarity transform (rotate +
 *     uniform scale + translate) onto a canonical [INPUT_SIZE]² crop.
 *  3. Embedding — **MobileFaceNet** ([FaceEmbedder], insightface `w600k_mbf`,
 *     ArcFace-trained) produces a 512-d embedding from the aligned crop. Pixels
 *     are normalised (px - 127.5)/127.5 natively. The result is L2-normalised here.
 *  4. Matching — cosine similarity clusters faces of the same person
 *     (see [CLUSTER_THRESHOLD]).
 *
 * The models ship as `.maml` assets in this app. They are **GPU-only**: on a device
 * without Vulkan fp16 compute [modelsAvailable] is false and people clustering turns off,
 * rather than falling back to a second implementation.
 */
object FaceRecognizer {

    /** Square input side (px) the embedder expects. ArcFace/MobileFaceNet is 112. */
    const val INPUT_SIZE = 112

    /**
     * Bump this whenever the embedder model (or preprocessing) changes. The face
     * worker compares it against a stored value and, on mismatch, clears existing
     * clusters and re-scans so photos are re-grouped with the new embeddings.
     * (v5 = BlazeFace + EdgeFace on ONNX Runtime; v6 = SCRFD detector +
     * MobileFaceNet embedder on ncnn; v7 = the same two models on `:library:ml`'s
     * Vulkan runtime — fp16 weights, fp32 accumulation and our own letterbox, so the
     * embeddings are close to v6's but not identical, and mixing the two spaces in one
     * index would cluster badly.)
     */
    const val EMBEDDER_VERSION = 7

    /**
     * Minimum cosine similarity for a face to join an existing cluster. ArcFace
     * (MobileFaceNet) has a spread-out cosine distribution; 0.5 is a reasonable
     * starting floor — validate on sample faces and adjust (higher = stricter/
     * more clusters, lower = looser/fewer).
     */
    const val CLUSTER_THRESHOLD = 0.5f

    /** Cosine above which two whole clusters merge in the second-pass cleanup. */
    const val MERGE_THRESHOLD = 0.6f

    private const val TAG = "FaceRecognizer"

    // Canonical eye positions inside the aligned crop (ArcFace 5-point template).
    private const val LEFT_EYE_X = 38.2946f / 112f
    private const val RIGHT_EYE_X = 73.5318f / 112f
    private const val EYE_Y = 51.6963f / 112f

    /**
     * One detected face: its L2-normalised embedding plus the detector's bounding
     * box, normalised to 0..1 of the source image so it survives resizing.
     */
    data class DetectedFace(
        val embedding: FloatArray,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    @Volatile private var detector: FaceDetector? = null
    @Volatile private var embedder: FaceEmbedder? = null
    @Volatile private var initFailed = false

    /** True if the native models could be loaded and the device has fp16 compute. */
    fun modelsAvailable(context: Context): Boolean = ensureInit(context)

    @Synchronized
    private fun ensureInit(context: Context): Boolean {
        if (detector != null && embedder != null) return true
        if (initFailed) return false
        return try {
            val app = context.applicationContext
            val newDetector = FaceDetector(app)
            val newEmbedder = FaceEmbedder(app)
            // GPU-only: a device without Vulkan fp16 compute reports unavailable rather
            // than throwing, so the constructors succeeding is not enough.
            if (!newDetector.isAvailable || !newEmbedder.isAvailable) {
                Log.w(TAG, "no Vulkan fp16 compute; face clustering is off")
                newDetector.close()
                newEmbedder.close()
                initFailed = true
                return false
            }
            detector = newDetector
            embedder = newEmbedder
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Face models unavailable", e)
            try { detector?.close() } catch (_: Exception) {}
            try { embedder?.close() } catch (_: Exception) {}
            detector = null
            embedder = null
            initFailed = true
            false
        }
    }

    /** Detect every face in [bitmap] and return one [DetectedFace] per face. */
    fun detectAndEmbed(context: Context, bitmap: Bitmap): List<DetectedFace> {
        if (!ensureInit(context)) return emptyList()
        val det = detector ?: return emptyList()
        val emb = embedder ?: return emptyList()

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val faces = try {
            det.detect(argb)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            if (argb != bitmap) argb.recycle()
            return emptyList()
        }

        val out = ArrayList<DetectedFace>(faces.size)
        for (f in faces) {
            val rect = faceRect(f, argb.width, argb.height) ?: continue
            val aligned = alignFace(argb, f, rect) ?: continue
            try {
                // Null means the inference failed on this crop; the others may still be
                // fine, so skip this face rather than abandoning the photo.
                val raw = emb.embed(aligned)
                if (raw == null) {
                    Log.e(TAG, "Face embedding failed")
                    continue
                }
                out += DetectedFace(
                    embedding = l2Normalize(raw),
                    left = f.left,
                    top = f.top,
                    right = f.right,
                    bottom = f.bottom,
                )
            } finally {
                aligned.recycle()
            }
        }
        if (argb != bitmap) argb.recycle()
        return out
    }

    /**
     * Warp the source so the two eye landmarks land on fixed canonical positions
     * in an [INPUT_SIZE] crop (similarity transform). Falls back to a plain box
     * crop when landmarks are unusable.
     */
    private fun alignFace(src: Bitmap, f: DetectedFaceBox, box: Rect): Bitmap? {
        val hasEyes = f.leftEyeX != 0f || f.leftEyeY != 0f || f.rightEyeX != 0f || f.rightEyeY != 0f
        if (hasEyes) {
            // Sort eyes by x so the left-most maps to the left canonical eye.
            val eyes = listOf(
                floatArrayOf(f.leftEyeX * src.width, f.leftEyeY * src.height),
                floatArrayOf(f.rightEyeX * src.width, f.rightEyeY * src.height),
            ).sortedBy { it[0] }
            val srcPts = floatArrayOf(eyes[0][0], eyes[0][1], eyes[1][0], eyes[1][1])
            val dstPts = floatArrayOf(
                LEFT_EYE_X * INPUT_SIZE, EYE_Y * INPUT_SIZE,
                RIGHT_EYE_X * INPUT_SIZE, EYE_Y * INPUT_SIZE,
            )
            val matrix = Matrix()
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 2)) {
                return try {
                    val outBmp = createBitmap(INPUT_SIZE, INPUT_SIZE)
                    Canvas(outBmp).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                    outBmp
                } catch (_: Exception) { null }
            }
        }
        return try {
            val crop = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height())
            val scaled = crop.scale(INPUT_SIZE, INPUT_SIZE)
            if (scaled != crop) crop.recycle()
            scaled
        } catch (_: Exception) { null }
    }

    /** Expand the normalised detector box by a small margin and clamp to pixels. */
    private fun faceRect(f: DetectedFaceBox, w: Int, h: Int): Rect? {
        val bw = (f.right - f.left) * w
        val bh = (f.bottom - f.top) * h
        val marginX = bw * 0.15f
        val marginY = bh * 0.15f
        val left = (f.left * w - marginX).toInt().coerceIn(0, w - 1)
        val top = (f.top * h - marginY).toInt().coerceIn(0, h - 1)
        val right = (f.right * w + marginX).toInt().coerceIn(left + 1, w)
        val bottom = (f.bottom * h + marginY).toInt().coerceIn(top + 1, h)
        if (right - left < 8 || bottom - top < 8) return null
        return Rect(left, top, right, bottom)
    }

    /** Cosine similarity of two embeddings (range roughly -1..1). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return -1f
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Return an L2-normalised copy (unit length) so cosine == dot product. */
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
}
