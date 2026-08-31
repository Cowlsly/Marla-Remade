package com.vayunmathur.health.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Standard row with a small circular tinted icon background (36dp),
 * headline + supporting text, and an optional trailing slot.
 */
@Composable
fun HealthRow(
    headline: String,
    modifier: Modifier = Modifier,
    headlineModifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    leadingTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(horizontal = 16.dp, vertical = 10.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(leadingTint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                leadingIcon(Modifier.size(20.dp), leadingTint)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = headlineModifier,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * Metric row — extends HealthRow with a value+unit titleMedium on the right,
 * optional inline sparkline above the value, optional DeltaChip below.
 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    labelModifier: Modifier = Modifier,
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    leadingTint: Color = MaterialTheme.colorScheme.primary,
    delta: Float? = null,
    sparkline: List<Float>? = null,
    deltaIsGood: (Float) -> Boolean = { it >= 0f },
    onClick: (() -> Unit)? = null,
) {
    HealthRow(
        headline = label,
        modifier = modifier,
        headlineModifier = labelModifier,
        leadingIcon = leadingIcon,
        leadingTint = leadingTint,
        onClick = onClick,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (sparkline != null) {
                    Sparkline(
                        values = sparkline,
                        modifier = Modifier
                            .width(28.dp)
                            .height(16.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                if (delta != null) {
                    Spacer(Modifier.height(2.dp))
                    DeltaChip(percent = delta, isGood = deltaIsGood(delta))
                }
            }
        },
    )
}

/** Small pill showing a percent delta with an arrow. */
@Composable
fun DeltaChip(
    percent: Float,
    isGood: Boolean,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor) = if (isGood) {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val arrow = if (percent >= 0f) "▲" else "▼"
    val absPct = kotlin.math.abs(percent)
    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = "$arrow ${"%.0f".format(absPct)}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Inline bar-style sparkline. Latest bar uses [barColor], the rest use
 * [trackColor] at 0.5 alpha. Auto-scales to the max value in the list.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    if (values.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }
    val maxValue = max(values.max(), 0.0001f)
    val mutedTrack = trackColor.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        val n = values.size
        val gap = 2f
        val totalGap = gap * (n - 1)
        val barWidth = max((size.width - totalGap) / n, 1f)
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
        values.forEachIndexed { index, v ->
            val frac = (v / maxValue).coerceIn(0f, 1f)
            val barHeight = max(size.height * frac, 1f)
            val left = index * (barWidth + gap)
            val top = size.height - barHeight
            val color = if (index == n - 1) barColor else mutedTrack
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * Single ring with a label and value stacked centered inside. Arc starts at
 * the top (-90 deg) and sweeps `360 * progress` degrees.
 */
@Composable
fun MetricRing(
    progress: Float,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 12.dp,
) {
    val trackColor = color.copy(alpha = 0.18f)
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Three concentric activity rings. Outer = steps, middle = energy,
 * inner = mindful. No labels inside — caller provides the legend below.
 */
@Composable
fun ActivityRingsTrio(
    stepsPct: Float,
    energyPct: Float,
    mindfulPct: Float,
    modifier: Modifier = Modifier,
    outerColor: Color = com.vayunmathur.health.ui.HealthColors.Activity,
    middleColor: Color = com.vayunmathur.health.ui.HealthColors.Nutrition,
    innerColor: Color = com.vayunmathur.health.ui.HealthColors.Sleep,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val stroke = (side.value * 0.10f).dp
        val gap = (side.value * 0.02f).dp
        val step = stroke + gap
        Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
            MetricRing(
                progress = stepsPct,
                label = "",
                value = "",
                modifier = Modifier.size(side),
                color = outerColor,
                strokeWidth = stroke,
            )
            MetricRing(
                progress = energyPct,
                label = "",
                value = "",
                modifier = Modifier.size(side - step * 2),
                color = middleColor,
                strokeWidth = stroke,
            )
            MetricRing(
                progress = mindfulPct,
                label = "",
                value = "",
                modifier = Modifier.size(side - step * 4),
                color = innerColor,
                strokeWidth = stroke,
            )
        }
    }
}

/** Color palette for the sleep hypnogram, sourced from MaterialTheme. */
data class HypnogramColors(
    val awake: Color,
    val rem: Color,
    val core: Color,
    val deep: Color,
)

/**
 * Fixed sleep-stage colors modelled on Google Fit / Health's hypnogram.
 *
 * These are intentionally *not* theme-driven — the previous MaterialTheme
 * palette mapped every stage to a tonal variant of the same hue, which made
 * them blend together (everything looked like a shade of white in dark mode
 * and a shade of the seed color in light mode). Distinct hues across the
 * cool→warm range make each stage instantly recognizable in both modes.
 */
val SleepStageColorAwake: Color = Color(0xFFF4B400) // Google amber — warm, stands out from sleep
val SleepStageColorRem: Color = Color(0xFF4FC3F7)   // Light cyan — REM, dream sleep
val SleepStageColorCore: Color = Color(0xFF4285F4)  // Google blue — light/core sleep
val SleepStageColorDeep: Color = Color(0xFF1A237E)  // Deep indigo — deep sleep

private val HypnogramColorsDefault = HypnogramColors(
    awake = SleepStageColorAwake,
    rem = SleepStageColorRem,
    core = SleepStageColorCore,
    deep = SleepStageColorDeep,
)

/**
 * Returns the shared [HypnogramColors] palette. Kept as a function (not a
 * direct constant import) so existing call sites — `val colors = hypnogramColors()`
 * — continue to compile unchanged.
 */
@Composable
fun hypnogramColors(): HypnogramColors = HypnogramColorsDefault
