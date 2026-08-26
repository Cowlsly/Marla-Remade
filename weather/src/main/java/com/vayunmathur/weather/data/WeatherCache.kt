package com.vayunmathur.weather.data

import androidx.room3.Entity

/**
 * Most-recent forecast + air-quality JSON for a (lat, lon) point. Lets the
 * home pager render instantly while a refresh runs in the background, and lets
 * the OpenAssistant intent return the cached value when the network is down.
 *
 * Forecast and air quality are cached together under a single
 * [fetchedAtEpochMs] so every piece of weather data for a location shares one
 * "last updated" time and can't drift out of sync. [airQualityJson] is
 * nullable because Open-Meteo's air-quality endpoint can be unavailable for a
 * point even when the forecast succeeds.
 *
 * Coordinates are rounded to 4 decimals (≈11 m) before insertion so we don't
 * cache 1000 near-identical rows for tiny GPS jitter.
 */
@Entity(primaryKeys = ["latRounded", "lonRounded"])
data class WeatherCache(
    val latRounded: Double,
    val lonRounded: Double,
    val forecastJson: String,
    val airQualityJson: String? = null,
    val fetchedAtEpochMs: Long,
)
