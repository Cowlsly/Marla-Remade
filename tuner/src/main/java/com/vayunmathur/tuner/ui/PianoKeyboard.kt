package com.vayunmathur.tuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.domain.pitchClassOf

/**
 * A piano keyboard with the sounding notes highlighted.
 *
 * This diagram is a **measurement**: the pipeline detects notes with their octaves, so the
 * highlighted keys are the notes that were actually heard. That is not true of the fret diagram,
 * and the two must not sit under a heading that implies otherwise.
 */
@Composable
fun PianoKeyboard(notes: List<Int>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val whiteKey = scheme.surfaceContainerLowest
    val blackKey = scheme.onSurface
    val outline = scheme.outlineVariant
    val pressed = scheme.primary
    val pressedBlack = scheme.primaryContainer
    val description = stringResource(R.string.piano_description)

    val lowest = notes.minOrNull() ?: DEFAULT_LOW
    val highest = notes.maxOrNull() ?: (DEFAULT_LOW + 2 * SEMITONES - 1)
    var from = Math.floorDiv(lowest, SEMITONES) * SEMITONES
    var to = Math.floorDiv(highest, SEMITONES) * SEMITONES + SEMITONES - 1
    while (to - from + 1 < MIN_OCTAVES * SEMITONES) to += SEMITONES
    while (to - from + 1 > MAX_OCTAVES * SEMITONES) {
        // Keep the sounding notes in view: trim from whichever end has the most slack.
        if (lowest - from >= to - highest) from += SEMITONES else to -= SEMITONES
    }

    val whites = (from..to).filter { pitchClassOf(it) in WHITE_CLASSES }
    if (whites.isEmpty()) return

    Canvas(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .semantics { contentDescription = description },
    ) {
        val whiteWidth = size.width / whites.size
        val blackWidth = whiteWidth * 0.62f
        val blackHeight = size.height * 0.62f

        whites.forEachIndexed { index, midi ->
            val x = index * whiteWidth
            drawRect(
                color = if (midi in notes) pressed else whiteKey,
                topLeft = Offset(x, 0f),
                size = Size(whiteWidth, size.height),
            )
            drawRect(
                color = outline,
                topLeft = Offset(x, 0f),
                size = Size(whiteWidth, size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )
        }

        whites.forEachIndexed { index, midi ->
            // A black key sits at the boundary after C, D, F, G and A.
            if (pitchClassOf(midi) !in BLACK_AFTER) return@forEachIndexed
            val black = midi + 1
            if (black > to) return@forEachIndexed
            val x = (index + 1) * whiteWidth - blackWidth / 2f
            drawRect(
                color = if (black in notes) pressedBlack else blackKey,
                topLeft = Offset(x, 0f),
                size = Size(blackWidth, blackHeight),
            )
        }
    }
}

private const val SEMITONES = 12
private const val MIN_OCTAVES = 2
private const val MAX_OCTAVES = 4

/** C3, a sensible empty-state window. */
private const val DEFAULT_LOW = 48

private val WHITE_CLASSES = setOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_AFTER = setOf(0, 2, 5, 7, 9)
