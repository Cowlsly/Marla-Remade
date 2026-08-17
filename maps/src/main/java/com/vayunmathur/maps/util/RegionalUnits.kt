package com.vayunmathur.maps.util

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.util.LocaleData
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.os.Build
import java.util.Locale

/**
 * Regional (locale-driven) distance/unit formatting for maps. Units follow the
 * device's regional settings — the same mechanism the shared
 * [com.vayunmathur.library.util.formatSpeed] uses (ICU `usage("road")` on API
 * 33+, `LocaleData.getMeasurementSystem` fallback below) — instead of a
 * maps-local units toggle.
 */

/** True when [locale]'s regional settings prefer imperial road units (mi/ft). */
fun isImperialUnits(locale: Locale = Locale.getDefault()): Boolean {
    val system = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
    return system == LocaleData.MeasurementSystem.US ||
        system == LocaleData.MeasurementSystem.UK
}

/**
 * Format a road distance in [meters] as a short user-facing string using
 * [locale]'s regional units (e.g. "800 m" / "2.3 km" or "0.5 mi" / "150 ft").
 */
internal fun formatDistance(meters: Double, locale: Locale = Locale.getDefault()): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return NumberFormatter.with()
            .usage("road")
            .unit(MeasureUnit.METER)
            .locale(locale)
            .unitWidth(NumberFormatter.UnitWidth.SHORT)
            .format(meters)
            .toString()
    }

    // Fallback for API < 33 (no usage("road")): pick the unit by regional system.
    val (unit, value) = if (isImperialUnits(locale)) {
        if (meters >= 1609.34) MeasureUnit.MILE to (meters / 1609.34)
        else MeasureUnit.FOOT to (meters * 3.28084)
    } else {
        if (meters >= 1000.0) MeasureUnit.KILOMETER to (meters / 1000.0)
        else MeasureUnit.METER to meters
    }
    return NumberFormatter.with()
        .unit(unit)
        .locale(locale)
        .unitWidth(NumberFormatter.UnitWidth.SHORT)
        .precision(Precision.maxFraction(1))
        .format(value)
        .toString()
}
