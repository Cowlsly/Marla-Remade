package com.vayunmathur.library.ui.game

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDurationTest {

    @Test
    fun secondsAndMinutesArePadded() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:07", formatDuration(7))
        assertEquals("01:00", formatDuration(60))
        assertEquals("04:12", formatDuration(252))
        assertEquals("59:59", formatDuration(3599))
    }

    @Test
    fun hoursAppearOnlyOncePastTheHour() {
        // The hour field is unpadded, so a long game reads 1:02:03 rather than 01:02:03.
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("10:00:00", formatDuration(36_000))
    }

    @Test
    fun negativeInputClampsToZero() {
        // A countdown that has run out should read 00:00, not -1:-1.
        assertEquals("00:00", formatDuration(-1))
        assertEquals("00:00", formatDuration(Int.MIN_VALUE))
    }

    @Test
    fun theLargestPlausibleGameStillFormats() {
        assertEquals("596523:14:07", formatDuration(Int.MAX_VALUE))
    }
}
