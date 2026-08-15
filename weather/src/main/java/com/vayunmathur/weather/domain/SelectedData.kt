package com.vayunmathur.weather.util

import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conditions resolved for whatever the user has selected (an hour, a day, or
 * nothing). The headline fields drive `CurrentWeatherCard`; [blockCurrent]
 * feeds the humidity/wind/pressure/visibility blocks; [uvIndexMax], the
 * sunrise/sunset ISO strings and the moon fields feed the UV + Sun + Moon
 * blocks.
 *
 * Resolution prefers hourly data for a selected hour (Open-Meteo exposes
 * apparent temp, humidity, dew point, wind, pressure, visibility and UV
 * hourly), falls back to the matching day, and finally to `current`.
 */
data class ResolvedConditions(
    val weatherCode: Int,
    val isDay: Boolean,
    val temperature: Double,
    val apparentTemperature: Double?,
    val high: Double?,
    val low: Double?,
    val uvIndexMax: Double?,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val precipitationSum: Double?,
    val daylightDurationSec: Double?,
    val moonPhase: Double?,
    val moonriseIso: String?,
    val moonsetIso: String?,
    val blockCurrent: Current,
)

/**
 * Resolve [selected] against [forecast]. Requires a non-null `current`
 * (callers only render the headline/blocks when current observations exist).
 */
fun resolveConditions(
    forecast: ForecastResponse,
    selected: SelectedDateOrTime?,
): ResolvedConditions? {
    val current = forecast.current ?: return null
    val daily = forecast.daily
    val hourly = forecast.hourly

    fun dailyIndexFor(isoDate: String): Int? =
        daily?.time?.indexOf(isoDate)?.takeIf { it >= 0 }

    return when (selected) {
        null -> ResolvedConditions(
            weatherCode = current.weatherCode,
            isDay = current.isDay == 1,
            temperature = current.temperature,
            apparentTemperature = current.apparentTemperature,
            high = daily?.temperatureMax?.firstOrNull(),
            low = daily?.temperatureMin?.firstOrNull(),
            uvIndexMax = daily?.uvIndexMax?.firstOrNull(),
            sunriseIso = daily?.sunrise?.firstOrNull(),
            sunsetIso = daily?.sunset?.firstOrNull(),
            precipitationSum = daily?.precipitationSum?.firstOrNull(),
            daylightDurationSec = daily?.daylightDuration?.firstOrNull(),
            moonPhase = daily?.moonPhase?.firstOrNull(),
            moonriseIso = daily?.moonrise?.firstOrNull(),
            moonsetIso = daily?.moonset?.firstOrNull(),
            blockCurrent = current,
        )

        is SelectedDateOrTime.Time -> {
            val h = hourly?.time?.indexOf(selected.isoTime)?.takeIf { it >= 0 }
            if (hourly == null || h == null) {
                // Selection no longer present (e.g. after a refresh) — behave
                // as if nothing is selected.
                return resolveConditions(forecast, null)
            }
            val d = dailyIndexFor(selected.isoTime.substringBefore('T'))
            val hourCurrent = current.copy(
                time = hourly.time.getOrNull(h) ?: current.time,
                temperature = hourly.temperature.getOrNull(h) ?: current.temperature,
                apparentTemperature = hourly.apparentTemperature.getOrNull(h) ?: current.apparentTemperature,
                relativeHumidity = hourly.relativeHumidity.getOrNull(h) ?: current.relativeHumidity,
                dewPoint = hourly.dewPoint.getOrNull(h) ?: current.dewPoint,
                weatherCode = hourly.weatherCode.getOrNull(h) ?: current.weatherCode,
                windSpeed = hourly.windSpeed.getOrNull(h) ?: current.windSpeed,
                windDirection = hourly.windDirection.getOrNull(h) ?: current.windDirection,
                pressureMsl = hourly.pressureMsl.getOrNull(h) ?: current.pressureMsl,
                visibility = hourly.visibility.getOrNull(h) ?: current.visibility,
                cloudCover = hourly.cloudCover.getOrNull(h) ?: current.cloudCover,
                windGusts = hourly.windGusts.getOrNull(h) ?: current.windGusts,
                isDay = hourly.isDay.getOrNull(h) ?: current.isDay,
            )
            ResolvedConditions(
                weatherCode = hourCurrent.weatherCode,
                isDay = hourCurrent.isDay == 1,
                temperature = hourCurrent.temperature,
                apparentTemperature = hourly.apparentTemperature.getOrNull(h),
                high = d?.let { daily?.temperatureMax?.getOrNull(it) },
                low = d?.let { daily?.temperatureMin?.getOrNull(it) },
                uvIndexMax = hourly.uvIndex.getOrNull(h)
                    ?: d?.let { daily?.uvIndexMax?.getOrNull(it) },
                sunriseIso = d?.let { daily?.sunrise?.getOrNull(it) },
                sunsetIso = d?.let { daily?.sunset?.getOrNull(it) },
                precipitationSum = hourly.precipitation.getOrNull(h),
                daylightDurationSec = d?.let { daily?.daylightDuration?.getOrNull(it) },
                moonPhase = d?.let { daily?.moonPhase?.getOrNull(it) },
                moonriseIso = d?.let { daily?.moonrise?.getOrNull(it) },
                moonsetIso = d?.let { daily?.moonset?.getOrNull(it) },
                blockCurrent = hourCurrent,
            )
        }

        is SelectedDateOrTime.Day -> {
            val d = dailyIndexFor(selected.isoDate)
            if (daily == null || d == null) {
                return resolveConditions(forecast, null)
            }
            ResolvedConditions(
                weatherCode = daily.weatherCode.getOrNull(d) ?: current.weatherCode,
                isDay = true,
                temperature = daily.temperatureMax.getOrNull(d) ?: current.temperature,
                apparentTemperature = daily.apparentTemperatureMax.getOrNull(d),
                high = daily.temperatureMax.getOrNull(d),
                low = daily.temperatureMin.getOrNull(d),
                uvIndexMax = daily.uvIndexMax.getOrNull(d),
                sunriseIso = daily.sunrise.getOrNull(d),
                sunsetIso = daily.sunset.getOrNull(d),
                precipitationSum = daily.precipitationSum.getOrNull(d),
                daylightDurationSec = daily.daylightDuration.getOrNull(d),
                moonPhase = daily.moonPhase.getOrNull(d),
                moonriseIso = daily.moonrise.getOrNull(d),
                moonsetIso = daily.moonset.getOrNull(d),
                // Humidity/wind/pressure/visibility/cloud have no daily summary
                // field, so aggregate the day's hourly values instead.
                blockCurrent = hourly?.let { aggregateDay(it, selected.isoDate, current) } ?: current,
            )
        }
    }
}

/**
 * Build a synthetic [Current] representing a whole day by aggregating that
 * day's hourly samples: averages for humidity, dew point, pressure,
 * visibility and cloud cover; a vector mean for wind; and the peak gust.
 * Returns [fallback] unchanged when the day has no hourly rows.
 */
private fun aggregateDay(hourly: Hourly, isoDate: String, fallback: Current): Current {
    val idx = hourly.time.indices.filter { hourly.time[it].substringBefore('T') == isoDate }
    if (idx.isEmpty()) return fallback

    fun avgOfD(list: List<Double>): Double? =
        idx.mapNotNull { list.getOrNull(it) }.takeIf { it.isNotEmpty() }?.average()
    fun avgOfI(list: List<Int>): Double? =
        idx.mapNotNull { list.getOrNull(it) }.takeIf { it.isNotEmpty() }?.average()
    fun maxOfD(list: List<Double>): Double? =
        idx.mapNotNull { list.getOrNull(it) }.maxOrNull()

    val winds = idx.mapNotNull { i ->
        val s = hourly.windSpeed.getOrNull(i) ?: return@mapNotNull null
        val dir = hourly.windDirection.getOrNull(i) ?: return@mapNotNull null
        s to dir
    }
    val (windSpeed, windDir) = vectorMeanWind(winds) ?: (fallback.windSpeed to fallback.windDirection)

    return fallback.copy(
        relativeHumidity = avgOfI(hourly.relativeHumidity)?.roundToInt() ?: fallback.relativeHumidity,
        dewPoint = avgOfD(hourly.dewPoint) ?: fallback.dewPoint,
        pressureMsl = avgOfD(hourly.pressureMsl) ?: fallback.pressureMsl,
        visibility = avgOfD(hourly.visibility) ?: fallback.visibility,
        cloudCover = avgOfI(hourly.cloudCover)?.roundToInt() ?: fallback.cloudCover,
        windSpeed = windSpeed,
        windDirection = windDir,
        windGusts = maxOfD(hourly.windGusts) ?: fallback.windGusts,
    )
}

/**
 * Vector (resultant) mean of wind samples expressed as (speed, direction-the
 * -wind-comes-from in degrees). Decomposes each sample into N/E components,
 * averages, and recombines — the meteorologically correct way to average a
 * directional quantity. Returns null for an empty input.
 */
private fun vectorMeanWind(samples: List<Pair<Double, Int>>): Pair<Double, Int>? {
    if (samples.isEmpty()) return null
    var u = 0.0
    var v = 0.0
    for ((speed, dirFrom) in samples) {
        val rad = Math.toRadians(dirFrom.toDouble())
        u += speed * sin(rad)
        v += speed * cos(rad)
    }
    u /= samples.size
    v /= samples.size
    val speed = sqrt(u * u + v * v)
    val deg = ((Math.toDegrees(atan2(u, v)) % 360) + 360) % 360
    return speed to deg.roundToInt()
}
