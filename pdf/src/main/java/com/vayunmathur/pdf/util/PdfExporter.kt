package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Post-processing filter applied to each scanned page before export. */
enum class ScanFilter { NONE, GRAYSCALE, BW, CONTRAST }

suspend fun savePdfToUri(
    context: Context,
    images: List<CapturedImage>,
    targetUri: Uri,
    filter: ScanFilter = ScanFilter.NONE,
    addOcr: Boolean = false,
): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    val ocr = if (addOcr) OcrEngine(context).takeIf { it.isAvailable() } else null
    try {
        images.forEachIndexed { index, capturedImage ->
            val uri = capturedImage.uri
            try {
                val crop = capturedImage.cropRect
                val quadrilateral = capturedImage.quadrilateral
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }

                val (cropWidth, cropHeight) = when {
                    quadrilateral != null -> {
                        val bounds = quadrilateral.toBoundingRect()
                        bitmap.width * bounds.width to bitmap.height * bounds.height
                    }
                    crop != null -> bitmap.width * crop.width to bitmap.height * crop.height
                    else -> bitmap.width.toFloat() to bitmap.height.toFloat()
                }

                val a4LongSide = 842f
                val scale = a4LongSide / maxOf(cropWidth, cropHeight)
                val targetWidth = (cropWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (cropHeight * scale).toInt().coerceAtLeast(1)

                // Render the (warped/cropped/full) source into a page-sized bitmap.
                val pageBitmap = createBitmap(targetWidth, targetHeight)
                val pageCanvas = Canvas(pageBitmap)
                pageCanvas.drawColor(Color.WHITE)
                when {
                    quadrilateral != null -> {
                        val warped = warpQuadToBitmap(bitmap, quadrilateral, targetWidth, targetHeight)
                        pageCanvas.drawBitmap(warped, 0f, 0f, null)
                        warped.recycle()
                    }
                    crop != null -> {
                        val srcRect = android.graphics.Rect(
                            (crop.left * bitmap.width).roundToInt(),
                            (crop.top * bitmap.height).roundToInt(),
                            (crop.right * bitmap.width).roundToInt(),
                            (crop.bottom * bitmap.height).roundToInt(),
                        )
                        pageCanvas.drawBitmap(bitmap, srcRect, android.graphics.Rect(0, 0, targetWidth, targetHeight), null)
                    }
                    else -> {
                        val matrix = Matrix()
                        matrix.postScale(scale, scale)
                        pageCanvas.drawBitmap(bitmap, matrix, null)
                    }
                }
                applyScanFilter(pageBitmap, filter)

                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                if (ocr != null) {
                    drawOcrTextLayer(page.canvas, ocr.recognizeDetailed(pageBitmap))
                }

                pdfDocument.finishPage(page)
                pageBitmap.recycle()
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("PdfExporter", "Error processing image $uri", e)
            }
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos -> pdfDocument.writeTo(fos) }
        }
        true
    } catch (e: Exception) {
        Log.e("PdfExporter", "Failed to save PDF", e)
        false
    } finally {
        pdfDocument.close()
        ocr?.close()
    }
}

/** Apply a scan [filter] to [bmp] in place (via a filtered self-copy). */
private fun applyScanFilter(bmp: Bitmap, filter: ScanFilter) {
    if (filter == ScanFilter.NONE) return
    val matrix = when (filter) {
        ScanFilter.GRAYSCALE, ScanFilter.BW -> ColorMatrix().apply { setSaturation(0f) }
        ScanFilter.CONTRAST -> ColorMatrix(
            floatArrayOf(
                1.5f, 0f, 0f, 0f, -50f,
                0f, 1.5f, 0f, 0f, -50f,
                0f, 0f, 1.5f, 0f, -50f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        ScanFilter.NONE -> return
    }
    if (filter == ScanFilter.BW) {
        // Strong contrast after desaturation approximates a bilevel scan.
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    4f, 0f, 0f, 0f, -430f,
                    0f, 4f, 0f, 0f, -430f,
                    0f, 0f, 4f, 0f, -430f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(bmp).drawBitmap(copy, 0f, 0f, paint)
    copy.recycle()
}

/** Draw an invisible (transparent) OCR text layer so scans are selectable/searchable. */
private fun drawOcrTextLayer(canvas: Canvas, result: OcrEngine.OcrResult) {
    val paint = Paint().apply { color = Color.argb(0, 0, 0, 0) }
    val matrix = Matrix()
    for (b in result.boxes) {
        if (b.text.isBlank()) continue
        val c = b.corners
        // The run's own width and height, so a skewed scan's text layer follows
        // the text rather than its bounding box.
        val bw = hypot(c[1].x - c[0].x, c[1].y - c[0].y)
        val bh = hypot(c[3].x - c[0].x, c[3].y - c[0].y)
        if (bw <= 1f || bh <= 1f) continue
        paint.textScaleX = 1f
        paint.textSize = bh * 0.8f
        val measured = paint.measureText(b.text)
        if (measured > 0f) paint.textScaleX = bw / measured
        val degrees = Math.toDegrees(atan2(c[1].y - c[0].y, c[1].x - c[0].x).toDouble()).toFloat()
        matrix.setRotate(degrees)
        matrix.postTranslate(c[0].x, c[0].y)
        canvas.save()
        canvas.concat(matrix)
        canvas.drawText(b.text, 0f, bh - bh * 0.15f, paint)
        canvas.restore()
    }
}

/**
 * Perspective-warps [quad] (normalized corners) out of [src] into a new [width]x[height] bitmap.
 * Falls back to a bounding-box crop when the perspective matrix is degenerate. Caller owns the result.
 */
fun warpQuadToBitmap(src: Bitmap, quad: Quadrilateral, width: Int, height: Int): Bitmap {
    val targetWidth = width.coerceAtLeast(1)
    val targetHeight = height.coerceAtLeast(1)
    val srcPoints = quad.toSrcPoints(src.width, src.height)
    val dstPoints = floatArrayOf(
        0f, 0f,
        targetWidth.toFloat(), 0f,
        targetWidth.toFloat(), targetHeight.toFloat(),
        0f, targetHeight.toFloat()
    )
    val matrix = Matrix()
    return if (matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
        createBitmap(targetWidth, targetHeight).also {
            Canvas(it).drawBitmap(src, matrix, null)
        }
    } else {
        val bounds = quad.toBoundingRect()
        val left = (bounds.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (bounds.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = targetWidth.coerceAtMost(src.width - left)
        val h = targetHeight.coerceAtMost(src.height - top)
        Bitmap.createBitmap(src, left, top, w, h)
    }
}
