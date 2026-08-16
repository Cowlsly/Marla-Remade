package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.IconWind
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.AirQualityCurrent

/**
 * Port of WeatherMaster's `AirQualityBlock`. Square `extraLarge` surface,
 * header top-start, big right-aligned AQI value bottom-end, then a
 * `LinearProgressIndicator` colored by AQI severity, then a level label.
 */
@Composable
fun AirQualityBlock(air: AirQualityCurrent?) {
    val aqi = air?.usAqi
    val levelRes = aqiLevelRes(aqi)
    val progress = ((aqi ?: 0) / 500f).coerceIn(0f, 1f)

    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconWind(m, c) }, title = stringResource(R.string.block_air_quality))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = aqi?.toString() ?: "—",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.displayMedium,
            )
            LinearProgressIndicator(
                progress = { progress },
                color = aqiColor(aqi),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.height(8.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = levelRes?.let { stringResource(it) } ?: "—",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                maxLines = 2,
                softWrap = true,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun aqiLevelRes(aqi: Int?): Int? = when {
    aqi == null -> null
    aqi <= 50 -> R.string.aqi_good
    aqi <= 100 -> R.string.aqi_moderate
    aqi <= 150 -> R.string.aqi_unhealthy_sensitive
    aqi <= 200 -> R.string.aqi_unhealthy
    aqi <= 300 -> R.string.aqi_very_unhealthy
    else -> R.string.aqi_hazardous
}

private fun aqiColor(aqi: Int?): Color = when {
    aqi == null -> Color(0xFF9E9E9E)
    aqi <= 50 -> Color(0xFF66BB6A)
    aqi <= 100 -> Color(0xFFFFEB3B)
    aqi <= 150 -> Color(0xFFFF9800)
    aqi <= 200 -> Color(0xFFEF5350)
    aqi <= 300 -> Color(0xFFAA00FF)
    else -> Color(0xFF6A1B9A)
}
