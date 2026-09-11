package com.vayunmathur.health.domain

import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.RepeatUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reminder arithmetic is the piece most able to be silently wrong, and a mistake here shows up
 * as a dose that never fires rather than as a crash. These cover the interval boundaries, the end
 * date, the exhausted-for-today case and the two DST discontinuities.
 */
class DoseScheduleTest {

    private val utc = TimeZone.UTC
    private val anchor = LocalDate(2026, 3, 2) // a Monday

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: TimeZone = utc) =
        LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute).toInstant(zone)

    private fun schedule(
        times: List<Int> = listOf(8 * 3600),
        unit: RepeatUnit = RepeatUnit.Daily,
        interval: Int = 1,
        days: Int = 0,
        anchorDate: LocalDate = anchor,
        endDate: LocalDate? = null,
        enabled: Boolean = true,
    ) = MedicationSchedule(
        id = "s1",
        medicationId = "m1",
        enabled = enabled,
        times = times,
        repeatUnit = unit,
        interval = interval,
        daysOfWeek = days,
        anchorDate = anchorDate,
        endDate = endDate,
    )

    /** Mon..Sun bits for a `daysOfWeek` mask, bit 0 = Sunday. */
    private val monday = 1 shl 1
    private val thursday = 1 shl 4
    private val sunday = 1 shl 0

    // --- Daily ---------------------------------------------------------------

    @Test
    fun `a daily dose later today is next`() {
        val next = DoseSchedule.nextDose(schedule(), at(anchor, 6), utc)
        assertEquals(at(anchor, 8), next)
    }

    @Test
    fun `once today's only dose has passed the next is tomorrow`() {
        val next = DoseSchedule.nextDose(schedule(), at(anchor, 9), utc)
        assertEquals(at(LocalDate(2026, 3, 3), 8), next)
    }

    @Test
    fun `a dose exactly now is not returned, so re-arming cannot spin`() {
        val next = DoseSchedule.nextDose(schedule(), at(anchor, 8), utc)
        assertEquals(at(LocalDate(2026, 3, 3), 8), next)
    }

    @Test
    fun `twice daily returns the evening dose then the next morning`() {
        val twice = schedule(times = listOf(8 * 3600, 20 * 3600))
        assertEquals(at(anchor, 20), DoseSchedule.nextDose(twice, at(anchor, 9), utc))
        assertEquals(
            at(LocalDate(2026, 3, 3), 8),
            DoseSchedule.nextDose(twice, at(anchor, 21), utc),
        )
    }

    @Test
    fun `times are honoured in ascending order regardless of how they were stored`() {
        val unsorted = schedule(times = listOf(20 * 3600, 8 * 3600))
        assertEquals(at(anchor, 8), DoseSchedule.nextDose(unsorted, at(anchor, 6), utc))
    }

    @Test
    fun `every third day skips the two days between`() {
        val every3 = schedule(interval = 3)
        assertEquals(
            at(LocalDate(2026, 3, 5), 8),
            DoseSchedule.nextDose(every3, at(anchor, 9), utc),
        )
    }

    @Test
    fun `an interval crossing a month boundary still lands correctly`() {
        val every3 = schedule(interval = 3, anchorDate = LocalDate(2026, 3, 30))
        // 30 Mar, 2 Apr, 5 Apr …
        assertEquals(
            at(LocalDate(2026, 4, 2), 8),
            DoseSchedule.nextDose(every3, at(LocalDate(2026, 3, 30), 9), utc),
        )
    }

    // --- Weekly --------------------------------------------------------------

    @Test
    fun `weekly picks the next selected weekday`() {
        val weekly = schedule(unit = RepeatUnit.Weekly, days = monday or thursday)
        assertEquals(
            at(LocalDate(2026, 3, 5), 8), // the Thursday
            DoseSchedule.nextDose(weekly, at(anchor, 9), utc),
        )
    }

    @Test
    fun `every other week skips the intervening week entirely`() {
        val fortnightly = schedule(
            unit = RepeatUnit.Weekly,
            interval = 2,
            days = monday or thursday,
        )
        // Anchor week is 2 Mar. Thu 5 Mar is in it; 9 and 12 Mar are the skipped week;
        // the next Monday that counts is 16 Mar.
        assertEquals(
            at(LocalDate(2026, 3, 16), 8),
            DoseSchedule.nextDose(fortnightly, at(LocalDate(2026, 3, 5), 9), utc),
        )
    }

    @Test
    fun `both days of a fortnightly pair fall in the same week`() {
        val fortnightly = schedule(
            unit = RepeatUnit.Weekly,
            interval = 2,
            days = monday or thursday,
        )
        // Monday 2 Mar and Thursday 5 Mar are the same week, so both must be dose days even
        // though they are three days apart — the bug a naive day-count interval would introduce.
        assert(DoseSchedule.isDoseDay(fortnightly, LocalDate(2026, 3, 2)))
        assert(DoseSchedule.isDoseDay(fortnightly, LocalDate(2026, 3, 5)))
        assert(!DoseSchedule.isDoseDay(fortnightly, LocalDate(2026, 3, 9)))
    }

    @Test
    fun `weeks are Sunday-started, so a Sunday belongs to the week that follows it`() {
        // Anchor Mon 2 Mar. Sun 8 Mar starts the next week, so with interval 2 it is skipped.
        val fortnightly = schedule(unit = RepeatUnit.Weekly, interval = 2, days = sunday)
        assert(!DoseSchedule.isDoseDay(fortnightly, LocalDate(2026, 3, 8)))
        assert(DoseSchedule.isDoseDay(fortnightly, LocalDate(2026, 3, 15)))
    }

    @Test
    fun `a weekly schedule with no days selected never fires`() {
        assertNull(DoseSchedule.nextDose(schedule(unit = RepeatUnit.Weekly, days = 0), at(anchor, 6), utc))
    }

    // --- Bounds --------------------------------------------------------------

    @Test
    fun `nothing is due before the anchor date`() {
        val next = DoseSchedule.nextDose(schedule(), at(LocalDate(2026, 2, 25), 6), utc)
        assertEquals(at(anchor, 8), next)
    }

    @Test
    fun `an end date stops the schedule`() {
        val ending = schedule(endDate = LocalDate(2026, 3, 3))
        assertEquals(
            at(LocalDate(2026, 3, 3), 8),
            DoseSchedule.nextDose(ending, at(anchor, 9), utc),
        )
        assertNull(DoseSchedule.nextDose(ending, at(LocalDate(2026, 3, 3), 9), utc))
    }

    @Test
    fun `a disabled schedule never fires`() {
        assertNull(DoseSchedule.nextDose(schedule(enabled = false), at(anchor, 6), utc))
    }

    @Test
    fun `a schedule with no times never fires`() {
        assertNull(DoseSchedule.nextDose(schedule(times = emptyList()), at(anchor, 6), utc))
    }

    @Test
    fun `a nonsensical interval never fires rather than looping`() {
        assertNull(DoseSchedule.nextDose(schedule(interval = 0), at(anchor, 6), utc))
    }

    // --- Daylight saving -----------------------------------------------------

    @Test
    fun `a dose in the hour that spring-forward skips still resolves`() {
        // In London, 2026-03-29 jumps 01:00 to 02:00, so 01:30 does not exist that day.
        val london = TimeZone.of("Europe/London")
        val missing = schedule(times = listOf(1 * 3600 + 30 * 60), anchorDate = LocalDate(2026, 3, 28))
        val next = DoseSchedule.nextDose(missing, at(LocalDate(2026, 3, 28), 2, 0, london), london)

        // kotlinx-datetime maps a skipped local time forward rather than throwing. The contract
        // being pinned here is only that a dose is still produced and that it is in the future —
        // silently returning null would mean the reminder stops for a day once a year.
        assert(next != null)
        assert(next!! > at(LocalDate(2026, 3, 28), 2, 0, london))
    }

    @Test
    fun `a dose in the hour that autumn-back repeats resolves once`() {
        val london = TimeZone.of("Europe/London")
        // 2026-10-25 runs 01:00-02:00 twice in London.
        val ambiguous = schedule(times = listOf(1 * 3600 + 30 * 60), anchorDate = LocalDate(2026, 10, 24))
        val next = DoseSchedule.nextDose(ambiguous, at(LocalDate(2026, 10, 24), 2, 0, london), london)
        assert(next != null)
    }
}
