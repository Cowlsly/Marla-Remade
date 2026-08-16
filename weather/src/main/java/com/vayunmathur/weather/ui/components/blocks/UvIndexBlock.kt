package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.MaterialShapes
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import kotlin.math.roundToInt

/**
 * Port of WeatherMaster's `UvIndexBlock` — Cookie12Sided surface with the
 * 5 colored severity dots drawn on a Canvas inside the cookie. The active
 * level's dot renders at full alpha; the others fade to 15%. Big UV
 * number centered, severity label bottom-center.
 *
 * Dot offsets are taken straight from WeatherMaster's source (positions
 * within a 176×176 reference canvas) and re-scaled to the actual block
 * size.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UvIndexBlock(uvIndex: Double?) {
    val v = uvIndex?.roundToInt()
    val activeLevel: Int = when {
        v == null -> -1
        v < 3 -> 0      // Low
        v < 6 -> 1      // Moderate
        v < 8 -> 2      // High
        v < 11 -> 3     // Very high
        else -> 4       // Extreme
    }
    val labelRes = when (activeLevel) {
        -1 -> null
        0 -> R.string.level_low
        1 -> R.string.level_moderate
        2 -> R.string.level_high
        3 -> R.string.level_very_high
        else -> R.string.level_extreme
    }
    val dotColors = listOf(
        Color(0xFF6DD58C), // Low — green
        Color(0xFFFCC934), // Moderate — yellow
        Color(0xFFFA903E), // High — orange
        Color(0xFFAF5CF7), // Very high — purple
        Color(0xFFEE675C), // Extreme — red
    )
    // Reference offsets within a 176×176 box (matches WeatherMaster).
    val dotOffsets = listOf(
        Offset(31f, 121f),  // Low
        Offset(54f, 145f),  // Moderate
        Offset(88f, 155f),  // High
        Offset(144f, 121f), // Very high
        Offset(120f, 145f), // Extreme
    )
    val dotRadii = listOf(8f, 6f, 6f, 6f, 6f)

    StatBlock(shape = MaterialShapes.Cookie12Sided.toShape()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val sx = size.width / 176f
            val sy = size.height / 176f
            dotColors.forEachIndexed { i, color ->
                val o = dotOffsets[i]
                drawCircle(
                    color = color,
                    radius = dotRadii[i] * sx,
                    center = Offset(o.x * sx, o.y * sy),
                    alpha = if (i == activeLevel) 1f else 0.15f,
                )
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                icon = { m, c -> IconSunny(m, c) },
                title = stringResource(R.string.metric_uv_index),
                topPadding = 32.dp,
            )
        }
        Text(
            text = v?.toString() ?: "—",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.align(Alignment.Center).offset(y = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = labelRes?.let { stringResource(it) } ?: "—",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-35).dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
