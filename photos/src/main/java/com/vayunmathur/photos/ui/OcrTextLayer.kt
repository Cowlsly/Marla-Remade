package com.vayunmathur.photos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.photos.data.OcrLayout
import kotlin.math.roundToInt

/**
 * An invisible, selectable copy of a photo's recognised text laid over the image,
 * so text in a screenshot or a sign can be long-pressed, dragged over and copied
 * with the platform's own selection UI (Copy / Select all / Share / Translate).
 *
 * [layout]'s coordinates are pixels of the bitmap OCR ran on, so each box is
 * mapped through the `ContentScale.Fit` letterbox this image is drawn in — hence
 * [containerSize] and the layout's own dimensions rather than [com.vayunmathur.photos.data.Photo.width].
 * The caller passes the image's zoom/pan `graphicsLayer` in [modifier] so the
 * text tracks pinch-zoom in lockstep.
 */
@Composable
fun OcrTextLayer(
    layout: OcrLayout,
    containerSize: IntSize,
    showOutlines: Boolean,
    modifier: Modifier = Modifier,
) {
    if (layout.boxes.isEmpty() || containerSize.width <= 0 || containerSize.height <= 0) return

    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(color = Color.Transparent, fontSize = BASE_FONT_SIZE) }
    val placements = remember(layout, containerSize, style, measurer) {
        placeBoxes(layout, containerSize) { text ->
            measurer.measure(text = text, style = style, maxLines = 1, softWrap = false).size
        }
    }
    if (placements.isEmpty()) return

    val outlineColor = MaterialTheme.colorScheme.primary
    val selectionColors = TextSelectionColors(
        handleColor = outlineColor,
        backgroundColor = outlineColor.copy(alpha = 0.4f),
    )
    val density = LocalDensity.current

    Box(modifier = modifier) {
        // Drawn under the selectable text so it can never intercept a touch. Makes
        // the feature discoverable without adding a control: the regions light up
        // whenever the metadata card is up.
        AnimatedVisibility(visible = showOutlines, enter = fadeIn(), exit = fadeOut()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                placements.forEach { p ->
                    drawRect(
                        color = outlineColor.copy(alpha = 0.5f),
                        topLeft = Offset(p.left, p.top),
                        size = Size(p.width, p.height),
                        style = Stroke(width = OUTLINE_STROKE.toPx()),
                    )
                }
            }
        }

        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            SelectionContainer {
                Box(modifier = Modifier.fillMaxSize()) {
                    placements.forEach { p ->
                        BasicText(
                            text = p.text,
                            style = style,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .offset { IntOffset(p.left.roundToInt(), p.top.roundToInt()) }
                                // Laid out at the glyph run's natural size, then
                                // stretched onto the box by the layer transform
                                // below. An OCR box's aspect ratio never matches a
                                // font's, so a font size alone can only match the
                                // box's width or its height, not both.
                                .requiredSize(
                                    width = with(density) { p.measured.width.toDp() },
                                    height = with(density) { p.measured.height.toDp() },
                                )
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = p.scaleX
                                    scaleY = p.scaleY
                                },
                        )
                    }
                }
            }
        }
    }
}

/** One OCR line resolved to on-screen pixels plus the stretch onto its box. */
private data class OcrPlacement(
    val text: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val measured: IntSize,
    val scaleX: Float,
    val scaleY: Float,
)

private fun placeBoxes(
    layout: OcrLayout,
    containerSize: IntSize,
    measure: (String) -> IntSize,
): List<OcrPlacement> {
    // ContentScale.Fit + Alignment.Center: uniform scale, letterboxed and centred.
    val fit = minOf(
        containerSize.width.toFloat() / layout.w,
        containerSize.height.toFloat() / layout.h,
    )
    val originX = (containerSize.width - layout.w * fit) / 2f
    val originY = (containerSize.height - layout.h * fit) / 2f

    return layout.boxes.mapNotNull { box ->
        val width = (box.right - box.left) * fit
        val height = (box.bottom - box.top) * fit
        if (width <= 0f || height <= 0f) return@mapNotNull null
        // Compose concatenates each selectable's text with no separator, so every
        // line has to carry its own newline or a multi-line copy comes out as one
        // run-together string. maxLines = 1 keeps it a single line tall.
        val text = box.text + "\n"
        val measured = measure(text)
        if (measured.width <= 0 || measured.height <= 0) return@mapNotNull null
        OcrPlacement(
            text = text,
            left = originX + box.left * fit,
            top = originY + box.top * fit,
            width = width,
            height = height,
            measured = measured,
            scaleX = width / measured.width,
            scaleY = height / measured.height,
        )
    }
}

private val BASE_FONT_SIZE = 16.sp
private val OUTLINE_STROKE = 1.dp
