package com.vayunmathur.weather.data

import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.domain.roundCoord
import kotlinx.serialization.json.Json

/** Shared lenient JSON used for (de)serializing cached weather payloads. */
val weatherJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Encode [forecast] (and [airQuality], if present) and upsert them into the
 * cache for [latitude]/[longitude], rounding the coordinates to the cache's
 * 4-decimal key. Centralizes the cache-write + JSON-encode that the view
 * model, refresh worker, and widget would otherwise each duplicate.
 *
 * Forecast and air quality are written together under one [fetchedAtEpochMs]
 * so a location's data never drifts out of sync. If [airQuality] is null
 * (its endpoint failed this round) we keep whatever air quality was already
 * cached rather than clobbering good data with a transient failure.
 */
suspend fun WeatherDao.writeForecastCache(
    latitude: Double,
    longitude: Double,
    forecast: ForecastResponse,
    airQuality: AirQualityResponse? = null,
    fetchedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val lat = roundCoord(latitude)
    val lon = roundCoord(longitude)
    val airQualityJson = if (airQuality != null) {
        weatherJson.encodeToString(airQuality)
    } else {
        getCache(lat, lon)?.airQualityJson
    }
    upsertCache(
        WeatherCache(
            latRounded = lat,
            lonRounded = lon,
            forecastJson = weatherJson.encodeToString(forecast),
            airQualityJson = airQualityJson,
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
    )
}
