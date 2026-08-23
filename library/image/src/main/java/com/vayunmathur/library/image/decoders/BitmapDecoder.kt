package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.vayunmathur.library.image.ImageRequest
import java.nio.ByteBuffer

object BitmapDecoder {

    fun isSvg(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val head = String(bytes.take(1024).toByteArray()).trimStart()
        return head.startsWith("<svg", ignoreCase = true) ||
            head.startsWith("<?xml") && head.contains("<svg", ignoreCase = true)
    }

    suspend fun decode(
        bytes: ByteArray,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        val viaImageDecoder = try {
            decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), request, allowHardware)
        } catch (_: Exception) {
            null
        }
        if (viaImageDecoder != null) return viaImageDecoder
        return try { decodeWithBitmapFactory(bytes, targetW, targetH, allowHardware) } catch (_: Exception) { null }
    }

    /**
     * Decode straight from an [ImageDecoder.Source], so local media never has to be
     * read into a `ByteArray` first. Downsampling happens inside the decoder, which
     * is why the target size matters more than where the bytes came from.
     */
    fun decode(
        source: ImageDecoder.Source,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (targetW > 0 && targetH > 0 && (w > targetW || h > targetH)) {
                    val ratio = maxOf(w.toFloat() / targetW, h.toFloat() / targetH)
                    if (ratio > 1f) {
                        decoder.setTargetSize((w / ratio).toInt().coerceAtLeast(1), (h / ratio).toInt().coerceAtLeast(1))
                    }
                }
                decoder.isUnpremultipliedRequired = false
                decoder.allocator = if (allowHardware) {
                    ImageDecoder.ALLOCATOR_DEFAULT
                } else {
                    ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeWithBitmapFactory(
        bytes: ByteArray,
        reqW: Int,
        reqH: Int,
        allowHardware: Boolean,
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val (w, h) = boundsOpts.outWidth to boundsOpts.outHeight

        var sample = 1
        if (reqW > 0 && reqH > 0 && w > 0 && h > 0) {
            var halfW = w / 2
            var halfH = h / 2
            while (halfW / sample >= reqW && halfH / sample >= reqH) {
                sample *= 2
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = if (allowHardware) Bitmap.Config.ARGB_8888 else Bitmap.Config.ARGB_8888
            // HARDWARE config cannot be used with inSampleSize on older APIs safely – keep ARGB
            inMutable = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("BitmapFactory failed")
        // If hardware requested, convert roughly by copying with HARDWARE.
        if (allowHardware) {
            return try {
                bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
            } catch (_: Exception) { bmp }
        }
        return bmp
    }
}
