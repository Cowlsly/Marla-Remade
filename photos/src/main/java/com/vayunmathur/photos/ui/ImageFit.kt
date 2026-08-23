package com.vayunmathur.photos.ui

import androidx.compose.ui.unit.IntSize

/**
 * Where a `ContentScale.Fit` + `Alignment.Center` image lands inside its
 * container: a uniform [scale] plus the letterbox [originX]/[originY] offset.
 *
 * Both the OCR text layer and the face-box layer overlay geometry that was
 * measured in the pixels of some source bitmap, so both need this same mapping;
 * sharing it is what keeps the two overlays from drifting apart.
 */
data class ImageFitTransform(
    val scale: Float,
    val originX: Float,
    val originY: Float,
)

/**
 * Resolve the letterbox for a [srcWidth] x [srcHeight] image drawn to fit
 * [containerSize], or null if either is degenerate.
 *
 * The source dimensions must be the *display-orientation* dimensions of the
 * bitmap the coordinates came from, not
 * [com.vayunmathur.photos.data.Photo.width]/[com.vayunmathur.photos.data.Photo.height]:
 * MediaStore's values are not EXIF-corrected and are transposed for rotated
 * JPEGs, which would turn every box 90°.
 */
fun imageFitTransform(srcWidth: Int, srcHeight: Int, containerSize: IntSize): ImageFitTransform? {
    if (srcWidth <= 0 || srcHeight <= 0) return null
    if (containerSize.width <= 0 || containerSize.height <= 0) return null
    val scale = minOf(
        containerSize.width.toFloat() / srcWidth,
        containerSize.height.toFloat() / srcHeight,
    )
    return ImageFitTransform(
        scale = scale,
        originX = (containerSize.width - srcWidth * scale) / 2f,
        originY = (containerSize.height - srcHeight * scale) / 2f,
    )
}
