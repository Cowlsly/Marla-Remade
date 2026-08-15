package com.vayunmathur.weather.util

import androidx.annotation.StringRes
import com.vayunmathur.weather.R

/**
 * Bucket the WMO weather codes Open-Meteo returns (0..99) into a handful of
 * categories we have icons + labels for. The mapping follows
 * https://open-meteo.com/en/docs#api_form table "Weather variable
 * documentation → weather_code".
 *
 * Use [forCode] from anywhere that needs a label / icon for a numeric code.
 */
enum class WeatherCondition(@StringRes val label: Int) {
    Clear(R.string.condition_clear),
    MostlyClear(R.string.condition_mostly_clear),
    PartlyCloudy(R.string.condition_partly_cloudy),
    Cloudy(R.string.condition_cloudy),
    Fog(R.string.condition_fog),
    Drizzle(R.string.condition_drizzle),
    Rain(R.string.condition_rain),
    Snow(R.string.condition_snow),
    Thunderstorm(R.string.condition_thunderstorm),
    Unknown(R.string.condition_unknown);

    /** Drawable id for this condition. `isDay` swaps clear/partly-cloudy night variants. */
    fun iconRes(isDay: Boolean): Int = when (this) {
        Clear -> if (isDay) R.drawable.outline_clear_day_24 else R.drawable.outline_clear_night_24
        // Mostly/mainly clear shows the same sun / moon as fully clear.
        MostlyClear -> if (isDay) R.drawable.outline_clear_day_24 else R.drawable.outline_clear_night_24
        PartlyCloudy -> if (isDay) R.drawable.outline_partly_cloudy_day_24 else R.drawable.outline_partly_cloudy_night_24
        Cloudy -> R.drawable.outline_cloudy_24
        Fog -> R.drawable.outline_fog_24
        Drizzle -> R.drawable.outline_drizzle_24
        Rain -> R.drawable.outline_rain_24
        Snow -> R.drawable.outline_snow_24
        Thunderstorm -> R.drawable.outline_thunder_24
        Unknown -> R.drawable.outline_cloudy_24
    }
}

fun weatherConditionForCode(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition.Clear
    1 -> WeatherCondition.MostlyClear
    2 -> WeatherCondition.PartlyCloudy
    3 -> WeatherCondition.Cloudy
    45, 48 -> WeatherCondition.Fog
    51, 53, 55, 56, 57 -> WeatherCondition.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.Rain
    71, 73, 75, 77, 85, 86 -> WeatherCondition.Snow
    95, 96, 99 -> WeatherCondition.Thunderstorm
    else -> WeatherCondition.Unknown
}
