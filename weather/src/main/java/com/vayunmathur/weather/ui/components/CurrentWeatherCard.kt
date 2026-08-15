package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.domain.weatherConditionForCode
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R

/**
 * Direct port of WeatherMaster's `PixelStyleCurrentWeatherCard`. Centered
 * column: 32 dp condition icon + `titleLarge Medium` condition label, then
 * 136 sp bold `primary`-colored temperature, then `titleLarge Medium`
 * feels-like, then a "Max X° Min Y°" row.
 *
 * Driven by already-resolved values so it can render the live current
 * conditions, a selected hour, or a selected day with the same code. When
 * [apparentTemperature] is null (e.g. a selected day), the feels-like line
 * is hidden.
 */
@Composable
fun CurrentWeatherCard(
    weatherCode: Int,
    isDay: Boolean,
    temperature: Double,
    apparentTemperature: Double?,
    high: Double?,
    low: Double?,
    tempUnit: TemperatureUnit,
) {
    val condition = weatherConditionForCode(weatherCode)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIconBox(
                icon = condition.iconContent(isDay),
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(condition.label),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = formatTemperatureCompact(temperature, tempUnit),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 136.sp,
            fontWeight = FontWeight.Bold,
        )
        if (apparentTemperature != null) {
            Text(
                text = stringResource(R.string.feels_like, formatTemperatureCompact(apparentTemperature, tempUnit)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (high != null && low != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.max_min, formatTemperatureCompact(high, tempUnit), formatTemperatureCompact(low, tempUnit)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
