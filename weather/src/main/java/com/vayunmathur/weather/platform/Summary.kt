package com.vayunmathur.weather.util

import android.content.Context
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.ForecastResponse
import java.util.Locale

/**
 * Rule-based one-paragraph human summary of today's forecast. Same role as
 * WeatherMaster's `computeDaySummary`, but synthesized purely from the
 * data we already have — no LLM, no extra network calls. Deterministic so
 * the same input always produces the same string.
 */
fun computeDaySummary(context: Context, forecast: ForecastResponse, tempUnit: TemperatureUnit): String {
    val current = forecast.current
    val daily = forecast.daily
    val conditionLabel = current?.weatherCode?.let {
        context.getString(weatherConditionForCode(it).label).lowercase(Locale.getDefault())
    } ?: context.getString(R.string.summary_mixed_conditions)
    val hi = daily?.temperatureMax?.firstOrNull()
    val lo = daily?.temperatureMin?.firstOrNull()
    val precip = daily?.precipitationProbabilityMax?.firstOrNull() ?: 0
    val wind = current?.windSpeed

    val parts = mutableListOf<String>()
    parts.add(context.getString(R.string.summary_expect_conditions, conditionLabel))
    if (hi != null && lo != null) {
        parts.add(
            context.getString(
                R.string.summary_high_low,
                formatTemperatureCompact(hi, tempUnit),
                formatTemperatureCompact(lo, tempUnit),
            )
        )
    }
    when {
        precip >= 70 -> parts.add(context.getString(R.string.summary_rain_likely))
        precip >= 40 -> parts.add(context.getString(R.string.summary_showers_possible))
        precip >= 20 -> parts.add(context.getString(R.string.summary_slight_chance_rain))
    }
    if (wind != null && wind >= 30) {
        parts.add(context.getString(R.string.summary_winds_noticeable))
    }
    return parts.joinToString(" ")
}
