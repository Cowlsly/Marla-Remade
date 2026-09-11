package com.vayunmathur.health.domain

import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.RepeatUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Works out when the next dose of a [MedicationSchedule] falls due.
 *
 * Pure: no Android, no database, no clock of its own — [nextDose] takes the moment to search from.
 * That is deliberate, because this is the part of the reminder feature most able to be quietly
 * wrong. Interval arithmetic across a month boundary, a schedule whose doses have all passed for
 * today, an end date landing mid-day, and the hour that does not exist on a spring-forward morning
 * are all handled here and all covered by tests, rather than being discovered on a device six weeks
 * later when a dose silently fails to fire.
 */
object DoseSchedule {

    /** How far ahead to look before giving up. Longer than any sane interval. */
    private const val SEARCH_DAYS = 366 * 2

    /**
     * The first dose due strictly after [from], or null if the schedule is finished or unusable.
     *
     * Strictly after, so re-arming from inside the alarm that just fired cannot pick the same
     * instant again and spin.
     */
    fun nextDose(
        schedule: MedicationSchedule,
        from: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Instant? {
        if (!schedule.enabled) return null
        val times = schedule.times.filter { it in 0 until SECONDS_PER_DAY }.sorted()
        if (times.isEmpty()) return null
        if (schedule.interval < 1) return null
        if (schedule.repeatUnit == RepeatUnit.Weekly && schedule.daysOfWeek == 0) return null

        val startDate = from.toLocalDateTime(timeZone).date

        var date = startDate
        repeat(SEARCH_DAYS) {
            val endDate = schedule.endDate
            if (endDate != null && date > endDate) return null

            if (isDoseDay(schedule, date)) {
                for (seconds in times) {
                    val candidate = LocalDateTime(date, LocalTime.fromSecondOfDay(seconds))
                        .toInstant(timeZone)
                    if (candidate > from) return candidate
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    /** Whether [date] is a day this schedule calls for, ignoring the time of day. */
    fun isDoseDay(schedule: MedicationSchedule, date: LocalDate): Boolean {
        if (date < schedule.anchorDate) return false
        schedule.endDate?.let { if (date > it) return false }

        return when (schedule.repeatUnit) {
            RepeatUnit.Daily -> {
                val elapsed = date.toEpochDays() - schedule.anchorDate.toEpochDays()
                elapsed % schedule.interval == 0L
            }

            RepeatUnit.Weekly -> {
                if (!schedule.daysOfWeek.hasDay(date.dayOfWeek)) return false
                // Count in whole weeks from the week the anchor falls in, not from the anchor day
                // itself: "every other week on Mon and Thu" has to treat both of those as the same
                // week even though they are three days apart.
                val elapsedWeeks = weekIndex(date) - weekIndex(schedule.anchorDate)
                elapsedWeeks % schedule.interval == 0L
            }
        }
    }

    /** Whether this seven-bit mask, bit 0 = Sunday, includes [day]. Clock's bit order. */
    fun Int.hasDay(day: DayOfWeek): Boolean = (this and (1 shl day.bitIndex())) != 0

    /** Bit position for [day] in a `daysOfWeek` mask: Sunday 0, Monday 1, … Saturday 6. */
    fun DayOfWeek.bitIndex(): Int = if (this == DayOfWeek.SUNDAY) 0 else isoDayNumber

    /**
     * Which Sunday-started week [date] falls in, as a count from the epoch.
     *
     * 1970-01-01 was a Thursday, so shifting by 4 puts a Sunday at a multiple of 7 and makes
     * integer division give consecutive week numbers.
     */
    private fun weekIndex(date: LocalDate): Long = (date.toEpochDays() + 4) / 7

    private const val SECONDS_PER_DAY = 24 * 60 * 60
}
