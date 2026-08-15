package com.vayunmathur.weather.util

import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/** Temperature display unit. Storage / API is always Celsius. */
enum class TemperatureUnit { Celsius, Fahrenheit }

/** Wind-speed display unit. Storage / API is always km/h. */
enum class WindUnit { KmH, Mph }

/** Pressure display unit. Storage / API is always hPa. */
enum class PressureUnit { Hpa, InHg }

// ---- Unit conversions (centralized so every call site shares one factor) ----

/** Millimetres of precipitation to inches. */
fun mmToInches(mm: Double): Double = mm / 25.4

/** Metres to miles (used for visibility). */
fun metersToMiles(meters: Double): Double = meters / 1609.34

/** Hectopascals to inches of mercury. */
fun hpaToInHg(hpa: Double): Double = hpa * 0.02953

fun Double.celsiusTo(unit: TemperatureUnit): Double = when (unit) {
    TemperatureUnit.Celsius -> this
    TemperatureUnit.Fahrenheit -> this * 9.0 / 5.0 + 32.0
}

fun formatTemperature(celsius: Double, unit: TemperatureUnit): String {
    val v = celsius.celsiusTo(unit).roundToInt()
    val suffix = if (unit == TemperatureUnit.Fahrenheit) "°F" else "°C"
    return "$v$suffix"
}

/** Round to a whole number with no unit suffix — used inside compact hero/strip cells. */
fun formatTemperatureCompact(celsius: Double, unit: TemperatureUnit): String =
    "${celsius.celsiusTo(unit).roundToInt()}°"

fun formatPressure(hpa: Double, unit: PressureUnit): String = when (unit) {
    PressureUnit.InHg -> String.format(Locale.US, "%.2f inHg", hpaToInHg(hpa))
    PressureUnit.Hpa -> "${hpa.roundToInt()} hPa"
}

fun formatWind(kph: Double, unit: WindUnit): String = when (unit) {
    WindUnit.KmH -> "${kph.roundToInt()} km/h"
    WindUnit.Mph -> "${(kph * 0.621371).roundToInt()} mph"
}

fun roundCoord(value: Double): Double = round(value * 10000.0) / 10000.0

/** 16-point compass labels, indexed clockwise from North. */
private val COMPASS_LABELS = listOf(
    "N", "NNE", "NE", "ENE",
    "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW",
    "W", "WNW", "NW", "NNW",
)

/**
 * Convert a 0..360° meteorological wind direction (where the wind comes FROM)
 * to a 16-point compass label — matches Pixel Weather's "12 km/h W" style.
 */
fun compassDirection(degrees: Int): String {
    val normalized = ((degrees % 360) + 360) % 360
    val idx = ((normalized + 11.25) / 22.5).toInt() % 16
    return COMPASS_LABELS[idx]
}
