package com.vayunmathur.setupwizard.platform

import android.content.Context
import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone
import android.icu.util.ULocale
import com.vayunmathur.setupwizard.R

/** One row of the time zone picker. */
data class ZoneInfo(
    val id: String,
    /** The city the zone is named for, e.g. "Los Angeles". */
    val displayName: String,
    /** Offset and zone name together, e.g. "GMT-08:00 Pacific Standard Time". */
    val standardName: String,
)

/**
 * The shortlist of time zones offered during setup, named the way ICU names them.
 *
 * One representative zone per offset rather than the full tzdb, which is what
 * `R.array.time_zones` holds: picking from ~90 cities is a setup step, picking from ~600 is
 * not. Settings offers the full list afterwards.
 */
object TimeZoneCatalog {

    fun all(context: Context): List<ZoneInfo> {
        val names = TimeZoneNames.getInstance(ULocale.getDefault())
        return context.resources.getStringArray(R.array.time_zones).map { describe(it, names) }
    }

    fun current(): ZoneInfo = describe(
        TimeZone.getDefault().id,
        TimeZoneNames.getInstance(ULocale.getDefault()),
    )

    private fun describe(zoneId: String, names: TimeZoneNames): ZoneInfo {
        val zone = TimeZone.getTimeZone(zoneId)
        // Exemplar location is the city; falling back to the zone's own display name keeps a
        // row readable for the handful of ids ICU has no location for (Etc/UTC).
        val displayName = names.getExemplarLocationName(TimeZone.getCanonicalID(zoneId) ?: zoneId)
            ?: zone.displayName
        val offset = zone.getDisplayName(false, TimeZone.LONG_GMT)
        val standard = zone.getDisplayName(false, TimeZone.LONG)
        return ZoneInfo(zoneId, displayName, "$offset $standard")
    }
}
