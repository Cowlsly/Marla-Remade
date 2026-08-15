package com.vayunmathur.weather.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.weather.domain.WeatherCondition

/**
 * Reusable weather-icon renderer. Same role as WeatherMaster's
 * `WeatherIconBox` — caller provides the icon composable, we render it at the
 * requested size in `onSurface` color (overridable).
 */
@Composable
fun WeatherIconBox(
    icon: @Composable (Modifier, Color) -> Unit,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    icon(Modifier.size(size), tint)
}

/**
 * Composable icon for this condition. Renders the same distinct weather drawables
 * the home-screen widget uses (via [WeatherCondition.iconRes]) so hourly/daily
 * rows show sun / moon / sun-behind-cloud / cloud etc. — the previous Material
 * glyphs drew "partly cloudy" and "cloudy" as identical clouds. `isDay` swaps the
 * clear / partly-cloudy night variants.
 */
fun WeatherCondition.iconContent(isDay: Boolean): @Composable (Modifier, Color) -> Unit {
    val res = iconRes(isDay)
    return { m, t -> Icon(painterResource(res), contentDescription = null, modifier = m, tint = t) }
}
