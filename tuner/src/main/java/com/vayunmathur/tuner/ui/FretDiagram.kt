package com.vayunmathur.tuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.MUTED_FRET
import com.vayunmathur.tuner.domain.Voicing

/**
 * A standard chord diagram: strings as vertical lines, frets as horizontal lines, dots where
 * fingers go, a ring above an open string and a cross above one that is not played.
 *
 * This diagram is a **suggestion**, not a measurement. The same pitch is available at up to four
 * places on the neck and nothing in the audio says which one was fingered, so the heading above
 * it has to be "one way to play this" rather than "what you played".
 */
@Composable
fun FretDiagram(
    instrument: Instrument,
    voicing: Voicing,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val lineColour = scheme.onSurfaceVariant
    val nutColour = scheme.onSurface
    val dotColour = scheme.primary
    val mutedColour = scheme.error
    val description = stringResource(R.string.diagram_description)

    val stringCount = instrument.strings.size
    if (stringCount < 2) return
    val lowestFretted = voicing.frets.filter { it > 0 }.minOrNull() ?: 0
    val startFret = if (lowestFretted <= 1) 1 else lowestFretted
    val showNut = startFret == 1

    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = description },
    ) {
        val marginX = size.width * 0.14f
        val topSpace = size.height * 0.16f
        val gridWidth = size.width - 2 * marginX
        val gridHeight = size.height - topSpace - size.height * 0.06f
        val stringGap = gridWidth / (stringCount - 1)
        val fretGap = gridHeight / VISIBLE_FRETS

        for (index in 0 until stringCount) {
            val x = marginX + index * stringGap
            drawLine(
                color = lineColour,
                start = Offset(x, topSpace),
                end = Offset(x, topSpace + gridHeight),
                strokeWidth = 2f,
            )
        }
        for (fret in 0..VISIBLE_FRETS) {
            val y = topSpace + fret * fretGap
            val nut = showNut && fret == 0
            drawLine(
                color = if (nut) nutColour else lineColour,
                start = Offset(marginX, y),
                end = Offset(marginX + gridWidth, y),
                strokeWidth = if (nut) 10f else 2f,
            )
        }

        val radius = minOf(stringGap, fretGap) * 0.32f

        voicing.barreFret?.let { barre ->
            val row = barre - startFret
            if (row in 0 until VISIBLE_FRETS) {
                val barred = voicing.frets.indices.filter { voicing.frets[it] >= barre }
                val first = barred.minOrNull()
                val last = barred.maxOrNull()
                if (first != null && last != null && last > first) {
                    val y = topSpace + (row + 0.5f) * fretGap
                    drawRoundRect(
                        color = dotColour,
                        topLeft = Offset(marginX + first * stringGap - radius, y - radius),
                        size = Size((last - first) * stringGap + radius * 2, radius * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    )
                }
            }
        }

        voicing.frets.forEachIndexed { index, fret ->
            val x = marginX + index * stringGap
            when {
                fret == MUTED_FRET -> {
                    val markY = topSpace - radius * 1.4f
                    drawLine(
                        color = mutedColour,
                        start = Offset(x - radius * 0.7f, markY - radius * 0.7f),
                        end = Offset(x + radius * 0.7f, markY + radius * 0.7f),
                        strokeWidth = 4f,
                    )
                    drawLine(
                        color = mutedColour,
                        start = Offset(x + radius * 0.7f, markY - radius * 0.7f),
                        end = Offset(x - radius * 0.7f, markY + radius * 0.7f),
                        strokeWidth = 4f,
                    )
                }

                fret == 0 -> drawCircle(
                    color = lineColour,
                    radius = radius * 0.7f,
                    center = Offset(x, topSpace - radius * 1.4f),
                    style = Stroke(width = 4f),
                )

                else -> {
                    val row = fret - startFret
                    if (row in 0 until VISIBLE_FRETS) {
                        drawCircle(
                            color = dotColour,
                            radius = radius,
                            center = Offset(x, topSpace + (row + 0.5f) * fretGap),
                        )
                    }
                }
            }
        }
    }
}

private const val VISIBLE_FRETS = 5
