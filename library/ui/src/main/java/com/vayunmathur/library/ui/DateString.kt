package com.vayunmathur.library.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedAmPmMarker
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The one place dates and times get turned into display strings.
 *
 * Every app used to hand-build its own `LocalDate.Format {}` / `LocalTime.Format {}`
 * (or manual `"$day/$month"` interpolation), which meant locale-correct month and
 * weekday names, localized AM/PM markers, and the system 12/24-hour setting were
 * re-derived per app and occasionally gotten wrong. These helpers centralize the
 * calendar-date and clock-time shapes the apps actually render.
 *
 * Everything returns a [String] so the caller wraps it in its own `Text` — Material
 * in normal UI, `androidx.glance.text.Text` in a widget, or a `%s` placeholder in a
 * localized resource string.
 *
 * Locale-correct names come from
 * [com.vayunmathur.library.util.localizedMonthNames] /
 * [com.vayunmathur.library.util.localizedDayOfWeekNames] /
 * [com.vayunmathur.library.util.localizedAmPmMarker], the same ICU-backed helpers
 * kotlinx-datetime callers were already using.
 *
 * Out of scope by design: elapsed/countdown durations (timer, stopwatch, track
 * length) and relative time ("Today", "5 minutes ago") — those are not calendar
 * dates or clock times and keep their own formatters.
 */
object DateString {

    // ── Date-only forms (input LocalDate) ──────────────────────────────────

    /**
     * The locale's short numeric date — `3/14/25` in en-US, `14/03/25` in en-GB.
     *
     * The only form built on `java.time`: kotlinx-datetime has no locale-aware
     * field ordering, and `FormatStyle.SHORT` is exactly that ordering.
     */
    fun dateShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(date.toJavaLocalDate())

    /**
     * A long, readable date — locale-aware ordering via [FormatStyle.LONG].
     * e.g. `August 17, 2026` in en-US, `17 August 2026` in en-GB, `2026年8月17日` in ja-JP.
     * Replaces the previous hard-coded "day month year" order.
     */
    fun dateLong(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(locale)
            .format(date.toJavaLocalDate())

    /**
     * Date with weekday and year — locale-aware ordering.
     * e.g. `Mon, Jan 3, 2025` in en-US, `Mon, 3 Jan 2025` in en-GB.
     * Uses the skeleton `EEE MMM d y` so ordering and punctuation follow the system locale.
     */
    fun dateWeekday(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEE MMM d y")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }

    /** Date with weekday, no year — locale-aware, e.g. `Mon, Jan 3` / `Mon, 3 Jan`. */
    fun dateWeekdayNoYear(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEE MMM d")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }

    /**
     * Date without a weekday — locale-aware medium style.
     * e.g. `Jan 3, 2025` in en-US, `3 Jan 2025` in en-GB, `2025/01/03` in ja-JP (medium).
     */
    fun monthDayYear(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(date.toJavaLocalDate())

    /**
     * Month and day without year — locale-aware, e.g. `August 17` / `17 August`.
     * Uses skeleton `MMMM d` for full month name. For short month use [monthDayShort].
     */
    fun dateMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMM d")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }

    /** Month and day, short month name — e.g. `Aug 17` / `17 Aug`. */
    fun monthDayShort(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMM d")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }

    /**
     * Helper for UIs that optionally include the year (e.g. birthday picker with "no year" toggle).
     * When [includeYear] is true uses [dateLong] (full, locale-aware); otherwise [dateMonthDay].
     */
    fun dateWithOptionalYear(date: LocalDate, includeYear: Boolean, locale: Locale = Locale.getDefault()): String =
        if (includeYear) dateLong(date, locale) else dateMonthDay(date, locale)

    /**
     * Ordered date fields for a locale, derived from the best pattern for `yMMMd`.
     * Returns the permutation of [DateField] that matches the system ordering:
     * e.g. en-US → [MONTH, DAY, YEAR], en-GB → [DAY, MONTH, YEAR], ja-JP → [YEAR, MONTH, DAY].
     */
    fun dateFieldOrder(locale: Locale = Locale.getDefault()): List<DateField> {
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMd")
        val y = pattern.indexOf('y').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val m = pattern.indexOf('M').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val d = pattern.indexOf('d').takeIf { it >= 0 } ?: Int.MAX_VALUE
        return listOf(DateField.YEAR to y, DateField.MONTH to m, DateField.DAY to d)
            .sortedBy { it.second }.map { it.first }
    }

    /** Date component for wheel ordering. */
    enum class DateField { YEAR, MONTH, DAY }

    // ── Time-only forms (input LocalTime + the 12/24-hour flag) ────────────

    /** Clock time honouring the 12/24-hour setting — `3:05 PM` / `15:05`. */
    fun time(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        clock(is24Hour)
        if (!is24Hour) { char(' '); localizedAmPmMarker(locale) }
    })

    /** Clock time with seconds — `3:05:23 PM` / `15:05:23`. */
    fun timeSeconds(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        clock(is24Hour, seconds = true)
        if (!is24Hour) { char(' '); localizedAmPmMarker(locale) }
    })

    /**
     * The numeric part of the time, no AM/PM marker — `3:05` / `15:05`.
     *
     * For call sites that render the marker separately (e.g. the clock's main
     * display styles the digits and the marker differently) or concatenate it
     * themselves with custom spacing.
     */
    fun timeNumeric(time: LocalTime, is24Hour: Boolean): String =
        time.format(LocalTime.Format { clock(is24Hour) })

    /** Numeric time with seconds, no AM/PM marker — `3:05:23` / `15:05:23`. */
    fun timeSecondsNumeric(time: LocalTime, is24Hour: Boolean): String =
        time.format(LocalTime.Format { clock(is24Hour, seconds = true) })

    /** A whole-hour axis label — `3 PM` / `15:00`. */
    fun hourLabel(
        time: LocalTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = time.format(LocalTime.Format {
        if (is24Hour) {
            hour(Padding.ZERO); char(':'); char('0'); char('0')
        } else {
            amPmHour(Padding.NONE); char(' '); localizedAmPmMarker(locale)
        }
    })

    /** [hourLabel] for a bare hour-of-day (0..23), for axis iteration. */
    fun hourLabel(
        hour: Int,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = hourLabel(LocalTime(hour, 0), is24Hour, locale)

    // ── Date + time (input LocalDateTime) ──────────────────────────────────

    /** Short date and clock time together — `3/14/25 3:05 PM` / `3/14/25 15:05`. */
    fun dateTime(
        dateTime: LocalDateTime,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = "${dateShort(dateTime.date, locale)} ${time(dateTime.time, is24Hour, locale)}"

    // ── Instant convenience overloads (the common forms) ───────────────────

    fun dateShort(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateShort(instant.toLocalDateTime(zone).date, locale)

    fun dateLong(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateLong(instant.toLocalDateTime(zone).date, locale)

    fun time(
        instant: Instant,
        is24Hour: Boolean,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = time(instant.toLocalDateTime(zone).time, is24Hour, locale)

    fun dateTime(
        instant: Instant,
        is24Hour: Boolean,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = dateTime(instant.toLocalDateTime(zone), is24Hour, locale)
}

// Shared "h:mm" / "HH:mm" (optionally with seconds) core so every time form
// stays consistent: 24h is zero-padded, 12h uses the non-padded am/pm hour.
private fun kotlinx.datetime.format.DateTimeFormatBuilder.WithTime.clock(
    is24Hour: Boolean,
    seconds: Boolean = false,
) {
    if (is24Hour) hour(Padding.ZERO) else amPmHour(Padding.NONE)
    char(':')
    minute()
    if (seconds) { char(':'); second() }
}

/** The device's 12/24-hour setting, for the string forms that need it. */
fun is24Hour(context: Context): Boolean = DateFormat.is24HourFormat(context)

/** [is24Hour] read from the ambient Compose context. */
@Composable
fun rememberIs24Hour(): Boolean {
    val context = LocalContext.current
    return remember { DateFormat.is24HourFormat(context) }
}
