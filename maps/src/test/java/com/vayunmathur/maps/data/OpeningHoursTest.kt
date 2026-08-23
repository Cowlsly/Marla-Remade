package com.vayunmathur.maps.data

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OSM `opening_hours` parser.
 *
 * Every failure mode here reads as a confident lie rather than as an error: an
 * unparsed rule leaves no rules, and no rules renders as "Closed" on every day of
 * the week. So the cases that matter are the ones where the string is perfectly
 * valid OSM and we used to drop it — `24/7` above all.
 *
 * Assertions go through [OpeningHours.isOpen] rather than the formatted week from
 * [OpeningHours.openingHours], because formatting a clock time reaches into
 * `android.icu`, which is not available to a JVM unit test. Only the two literal
 * strings ("Closed", "Open 24 hours") are safe to assert on.
 */
class OpeningHoursTest {

    /** A Wednesday, so `Mo-Fr` matches and `Sa`/`Su` do not. */
    private fun wed(hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 8, 19, hour, minute)

    private fun sat(hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 8, 22, hour, minute)

    private fun sun(hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 8, 23, hour, minute)

    @Test
    fun `24-7 is open at every hour, not permanently closed`() {
        val hours = OpeningHours.from("24/7")
        assertTrue(hours.hasRules)
        for (h in listOf(0, 3, 12, 23)) {
            assertTrue(hours.isOpen(wed(h)), "open at $h:00")
            assertTrue(hours.isOpen(sat(h)), "open on Saturday at $h:00")
        }
        assertEquals(
            DayOfWeek.entries.associateWith { "Open 24 hours" },
            hours.openingHours(),
        )
    }

    /** The same schedule written the long way round, which normalises identically. */
    @Test
    fun `an explicit all-day interval is open all day`() {
        val hours = OpeningHours.from("Mo-Su 00:00-24:00")
        assertTrue(hours.isOpen(wed(0)))
        assertTrue(hours.isOpen(wed(23, 59)))
    }

    @Test
    fun `a weekday and weekend rule each apply to their own days`() {
        val hours = OpeningHours.from("Mo-Fr 08:00-18:00; Sa 09:00-13:00")

        assertTrue(hours.isOpen(wed(8)))
        assertTrue(hours.isOpen(wed(17, 59)))
        assertFalse(hours.isOpen(wed(7, 59)))
        assertFalse(hours.isOpen(wed(18)))

        assertTrue(hours.isOpen(sat(9)))
        assertFalse(hours.isOpen(sat(13)))

        // Sunday is named by neither rule.
        assertFalse(hours.isOpen(sun(12)))
    }

    /** An interval that wraps midnight has to stay open on both sides of it. */
    @Test
    fun `an overnight interval spans midnight`() {
        val hours = OpeningHours.from("Mo-Su 22:00-02:00")
        assertTrue(hours.isOpen(wed(23)))
        assertTrue(hours.isOpen(wed(1)))
        assertFalse(hours.isOpen(wed(12)))
    }

    /**
     * Public holidays need a calendar we do not have, so the rule must be dropped
     * rather than applied to all seven days — which is what a missing day selector
     * means everywhere else in the grammar.
     */
    @Test
    fun `a public-holiday rule is dropped rather than applied to every day`() {
        val hours = OpeningHours.from("Mo-Fr 08:00-18:00; PH off")
        assertTrue(hours.isOpen(wed(9)), "the weekday rule survives")

        assertFalse(OpeningHours.from("PH off").hasRules, "nothing understood")
    }

    /**
     * The time list contains a space, which the old last-space split fed to the day
     * parser — losing the whole rule and rendering the place as always closed.
     */
    @Test
    fun `a spaced time list keeps both of its intervals`() {
        val hours = OpeningHours.from("Mo-Fr 08:00-12:00, 13:00-18:00")
        assertTrue(hours.isOpen(wed(9)))
        assertFalse(hours.isOpen(wed(12, 30)), "closed over lunch")
        assertTrue(hours.isOpen(wed(14)))
    }

    @Test
    fun `a trailing comment is ignored rather than eaten as a time`() {
        val hours = OpeningHours.from("""Mo-Fr 08:00-18:00 "by appointment"""")
        assertTrue(hours.isOpen(wed(9)))
        assertFalse(hours.isOpen(wed(19)))
    }

    @Test
    fun `off closes the day it names without touching the others`() {
        val hours = OpeningHours.from("Mo-Su 09:00-17:00; Su off")
        assertTrue(hours.isOpen(wed(10)))
        assertFalse(hours.isOpen(sun(10)), "Sunday is off")
    }

    /** Unrepresentable rules must be dropped quietly, never thrown out of the sheet. */
    @Test
    fun `malformed and unsupported rules are dropped without throwing`() {
        for (raw in listOf(
            "Mo-Fr sunrise-sunset",
            "Jan-Mar 09:00-17:00",
            "garbage",
            "Mo-Fr 08:00",
            ";;;",
            "",
        )) {
            val hours = OpeningHours.from(raw)
            assertFalse(hours.isOpen(wed(12)), "'$raw' must not claim to be open")
            assertFalse(hours.hasRules, "'$raw' understood nothing, and says so")
        }
    }

    /** A dropped rule must not take a valid neighbour down with it. */
    @Test
    fun `a valid rule survives a malformed one beside it`() {
        val hours = OpeningHours.from("Mo-Fr sunrise-sunset; Sa 09:00-13:00")
        assertTrue(hours.hasRules)
        assertTrue(hours.isOpen(sat(10)))
    }

    @Test
    fun `the next status change is the end of the current interval`() {
        val hours = OpeningHours.from("Mo-Fr 08:00-18:00")
        assertEquals(wed(18), hours.nextStatusChangeTime(wed(9)))
    }

    /** Nothing ever changes under 24/7, and the sentinel for that is `current`. */
    @Test
    fun `a 24-7 schedule reports no next status change`() {
        val hours = OpeningHours.from("24/7")
        assertEquals(wed(9), hours.nextStatusChangeTime(wed(9)))
    }
}
