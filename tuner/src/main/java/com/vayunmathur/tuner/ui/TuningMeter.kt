package com.vayunmathur.tuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.tuner.domain.TuningBand

/**
 * The tuning readout: a linear scale over plus or minus fifty cents with the needle on it.
 *
 * Linear on purpose. Expanding the scale near zero would make the app *feel* more precise while
 * doing nothing but magnifying measurement jitter, and the honest end-to-end accuracy here is
 * about half a cent - the in-tune band is drawn at plus or minus one cent to match.
 */
@Composable
fun TuningMeter(
    cents: Double?,
    band: TuningBand,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val trackColour = scheme.surfaceVariant
    val tickColour = scheme.onSurfaceVariant.copy(alpha = 0.5f)
    val centreColour = scheme.outline
    val inTuneZone = scheme.primary.copy(alpha = 0.18f)
    val needleColour = when (band) {
        TuningBand.IN_TUNE -> scheme.primary
        TuningBand.CLOSE -> scheme.tertiary
        TuningBand.OUT -> scheme.error
    }

    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val width = size.width
        val height = size.height
        val centreX = width / 2f
        val trackTop = height * 0.55f
        val trackHeight = height * 0.12f

        drawRect(
            color = trackColour,
            topLeft = Offset(0f, trackTop),
            size = Size(width, trackHeight),
        )
        // The green band is a *zone*, not a point: a needle that can only ever be "exactly zero"
        // would be claiming a precision the microphone does not have.
        val zoneHalf = width / 2f * (IN_TUNE_CENTS / SCALE_CENTS).toFloat()
        drawRect(
            color = inTuneZone,
            topLeft = Offset(centreX - zoneHalf, trackTop),
            size = Size(zoneHalf * 2f, trackHeight),
        )

        for (step in -5..5) {
            val x = centreX + step / 5f * (width / 2f) * 0.94f
            val major = step % 5 == 0
            drawLine(
                color = if (major) centreColour else tickColour,
                start = Offset(x, trackTop - if (major) height * 0.22f else height * 0.10f),
                end = Offset(x, trackTop + trackHeight + if (major) height * 0.10f else 0f),
                strokeWidth = if (major) 3f else 1.5f,
            )
        }

        if (cents == null) return@Canvas
        val clamped = cents.coerceIn(-SCALE_CENTS, SCALE_CENTS)
        val needleX = centreX + (clamped / SCALE_CENTS).toFloat() * (width / 2f) * 0.94f
        drawLine(
            color = needleColour,
            start = Offset(needleX, height * 0.10f),
            end = Offset(needleX, trackTop + trackHeight + height * 0.16f),
            strokeWidth = 8f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = needleColour,
            radius = height * 0.09f,
            center = Offset(needleX, trackTop + trackHeight / 2f),
        )
    }
}

private const val SCALE_CENTS = 50.0
private const val IN_TUNE_CENTS = 1.0
