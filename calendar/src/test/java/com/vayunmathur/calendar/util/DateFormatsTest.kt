package com.vayunmathur.calendar.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateFormatsTest {

    @Test
    fun timedOccurrenceIsWrittenAsAUtcDateTime() {
        // 14:30 in New York on a summer date is 18:30 UTC.
        val value = LocalDate(2025, 7, 4)
            .toIcalUtcDateTime(LocalTime(14, 30), TimeZone.of("America/New_York"))
        assertEquals("20250704T183000Z", value)
    }

    @Test
    fun aUtcDateTimeCanRollOverIntoTheNextDay() {
        val value = LocalDate(2025, 7, 4)
            .toIcalUtcDateTime(LocalTime(22, 0), TimeZone.of("America/New_York"))
        assertEquals("20250705T020000Z", value)
    }

    @Test
    fun allDayOccurrenceIsADateOnly() {
        assertEquals("20250704", LocalDate(2025, 7, 4).toIcalBasic())
    }

    @Test
    fun writtenOccurrencesParseBackToTheirLocalDate() {
        // The provider round-trip the RDATE column relies on: what toIcalUtcDateTime writes has to
        // come back out of parseIcalBasicDate as the same calendar day it was written for.
        val date = LocalDate(2025, 7, 4)
        val written = date.toIcalUtcDateTime(LocalTime(9, 0), TimeZone.of("America/New_York"))
        assertEquals(date, parseIcalBasicDate(written))
        assertEquals(date, parseIcalBasicDate(date.toIcalBasic()))
    }

    @Test
    fun occurrencesWrittenByOtherClientsStillParse() {
        // Google Calendar and friends prefix RDATE values with parameters; Event.getAllEvents strips
        // everything up to the last colon before handing the value over.
        assertEquals(
            LocalDate(2025, 7, 4),
            parseIcalBasicDate("TZID=America/New_York:20250704T090000".substringAfterLast(':')),
        )
        assertEquals(
            LocalDate(2025, 7, 4),
            parseIcalBasicDate("VALUE=DATE:20250704".substringAfterLast(':')),
        )
    }

    @Test
    fun garbageIsNotADate() {
        assertNull(parseIcalBasicDate(""))
        assertNull(parseIcalBasicDate("not-a-date"))
    }
}
