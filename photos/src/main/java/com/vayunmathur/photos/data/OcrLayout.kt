package com.vayunmathur.photos.data

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One recognised text line, in the pixel coordinates of the OCR bitmap. */
@Serializable
data class OcrBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * A photo's OCR result with geometry, stored as JSON in [Photo.ocrBoxes].
 *
 * [w]/[h] are the dimensions of the bitmap OCR actually ran on, not
 * [Photo.width]/[Photo.height]: the OCR decode caps the long side (so the scale
 * differs), and MediaStore's WIDTH/HEIGHT are not EXIF-orientation-corrected,
 * so they are transposed for rotated JPEGs. ImageDecoder applies EXIF rotation,
 * so these are the display-orientation dimensions the viewer needs to rebuild
 * the ContentScale.Fit letterbox rect the boxes sit in.
 */
@Serializable
data class OcrLayout(
    val w: Int,
    val h: Int,
    val boxes: List<OcrBox>,
) {
    /** The lines joined by newlines — what [Photo.ocrText] holds for search. */
    val text: String get() = boxes.joinToString("\n") { it.text }.trim()
}

private val ocrJson = Json { ignoreUnknownKeys = true }

fun OcrLayout.toJson(): String = ocrJson.encodeToString(this)

/** Parse a stored [OcrLayout], or null if absent/unreadable (e.g. an older format). */
fun parseOcrLayout(json: String?): OcrLayout? {
    if (json.isNullOrBlank()) return null
    return try {
        ocrJson.decodeFromString<OcrLayout>(json).takeIf { it.w > 0 && it.h > 0 }
    } catch (e: Exception) {
        Log.w("OcrLayout", "Failed to parse stored OCR layout", e)
        null
    }
}
