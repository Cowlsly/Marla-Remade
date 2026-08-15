package com.vayunmathur.weather.intents

import android.content.Context
import com.vayunmathur.library.intents.weather.WeatherData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.domain.compassDirection
import com.vayunmathur.weather.domain.parseLocalIsoToEpochSec
import com.vayunmathur.weather.domain.weatherConditionForCode
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Input payload for [GetWeatherIntent]: a single coordinate pair. */
@Serializable
data class LatLonInput(val latitude: Double, val longitude: Double)

/**
 * "Headless" activity launched by OpenAssistant's `get_weather` tool. Fetches
 * a fresh forecast from Open-Meteo for the supplied lat/lon and returns it as
 * a [WeatherData] payload. No UI — the activity finishes immediately as
 * dictated by the [AssistantIntent] base class.
 */
@OptIn(InternalSerializationApi::class)
class GetWeatherIntent : AssistantIntent<LatLonInput, WeatherData>(
    inputSerializer = serializer<LatLonInput>(),
    outputSerializer = serializer<WeatherData>(),
) {
    override suspend fun performCalculation(input: LatLonInput): WeatherData {
        return try {
            val forecast = WeatherApi.forecast(input.latitude, input.longitude)
            forecast.toWeatherData(this, locationName = null)
        } catch (e: Exception) {
            errorWeatherData(null, e.message ?: "Failed to fetch forecast")
        }
    }
}

internal fun errorWeatherData(locationName: String?, error: String) = WeatherData(
    locationName = locationName,
    temperatureCelsius = 0.0,
    feelsLikeCelsius = 0.0,
    condition = "",
    highCelsius = 0.0,
    lowCelsius = 0.0,
    precipitationChancePercent = 0,
    humidityPercent = 0,
    windKph = 0.0,
    windDirection = "",
    uvIndex = 0.0,
    error = error,
)

/** Distill a full [ForecastResponse] into the cross-app [WeatherData] payload. */
internal fun ForecastResponse.toWeatherData(context: Context, locationName: String?): WeatherData {
    val current = current ?: return errorWeatherData(locationName, "No current observations available")
    val condition = context.getString(weatherConditionForCode(current.weatherCode).label)
    val hi = daily?.temperatureMax?.firstOrNull() ?: current.temperature
    val lo = daily?.temperatureMin?.firstOrNull() ?: current.temperature
    val precip = daily?.precipitationProbabilityMax?.firstOrNull() ?: 0
    val uv = daily?.uvIndexMax?.firstOrNull() ?: 0.0
    val sunrise = daily?.sunrise?.firstOrNull()?.let { parseLocalIsoToEpochSec(it, utcOffsetSeconds) }
    val sunset = daily?.sunset?.firstOrNull()?.let { parseLocalIsoToEpochSec(it, utcOffsetSeconds) }

    return WeatherData(
        locationName = locationName,
        temperatureCelsius = current.temperature,
        feelsLikeCelsius = current.apparentTemperature,
        condition = condition,
        highCelsius = hi,
        lowCelsius = lo,
        precipitationChancePercent = precip,
        humidityPercent = current.relativeHumidity,
        windKph = current.windSpeed,
        windDirection = compassDirection(current.windDirection),
        uvIndex = uv,
        sunriseEpochSec = sunrise,
        sunsetEpochSec = sunset,
    )
}
