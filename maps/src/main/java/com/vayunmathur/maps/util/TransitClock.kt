package com.vayunmathur.maps.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Query-time fields for the offline transit index, all derived in one timezone.
 * [weekday] is 0=Mon..6=Sun to match the index's service masks, [date] is
 * `yyyymmdd`, and `prev*` describe the preceding service day (GTFS trips stored
 * as `24:30:00` belong to it but run in this one).
 *
 * Lives outside [OfflineRouter] so it can be unit-tested: that object's
 * initializer loads the native library.
 */
internal data class TransitClock(
    val depSecs: Int,
    val weekday: Int,
    val date: Int,
    val prevWeekday: Int,
    val prevDate: Int,
    /** Epoch millis of midnight in that zone, so `depSecs` is absolute. */
    val midnightMillis: Long,
)

/**
 * Derive a [TransitClock] in IANA zone [zoneId] at [now], falling back to the
 * device zone when [zoneId] is null or unknown. The `.transit` index is
 * world-merged, so using the device zone for a distant feed picks trips hours
 * off.
 */
internal fun transitClock(
    zoneId: String?,
    now: (ZoneId) -> ZonedDateTime = { ZonedDateTime.now(it) },
): TransitClock {
    val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val at = now(zone)
    val today = at.toLocalDate()
    val yesterday = today.minusDays(1)
    return TransitClock(
        depSecs = at.toLocalTime().toSecondOfDay(),
        // DayOfWeek is Mon=1..Sun=7.
        weekday = today.dayOfWeek.value - 1,
        date = yyyymmdd(today),
        prevWeekday = yesterday.dayOfWeek.value - 1,
        prevDate = yyyymmdd(yesterday),
        midnightMillis = today.atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

private fun yyyymmdd(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

/**
 * Format a GTFS service-day time (which may exceed 86400 for a trip running
 * past midnight) as a wall clock `HH:mm`. Empty for a missing time.
 */
internal fun formatServiceTime(secs: Int): String {
    if (secs <= 0) return ""
    val s = secs % 86_400
    return "%02d:%02d".format(Locale.ROOT, s / 3600, (s % 3600) / 60)
}
