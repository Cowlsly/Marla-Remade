package com.vayunmathur.calendar.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.AllDayFormat
import com.vayunmathur.calendar.util.BasicIsoInstantFormat
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Composable
fun EventCard(event: Event) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        ListItem({
            Text(event.title)
        }, supportingContent = {
            Column {
                // Format date range using the shared helper
                Text(dateRangeString(context, event.startDateTimeDisplay.date, event.endDateTimeDisplay.date, event.startDateTimeDisplay.time, event.endDateTimeDisplay.time, event.allDay))
                // RRULE text
                event.rrule?.let { Text(it.describe(context)) }
                if (event.rdate.isNotEmpty()) {
                    Text(
                        context.resources.getQuantityString(
                            R.plurals.repeat_dates_summary,
                            event.rdate.size + 1,
                            event.rdate.size + 1,
                        )
                    )
                }

                if (event.description.isNotBlank()) {
                    Text(event.description)
                }
                if (event.location.isNotBlank()) {
                    Text(event.location)
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

// Simple ICS parser that returns a list of Event (uses the app's Event class)
fun parseICSFile(iS: InputStream): List<Event> {
    val events = mutableListOf<Event>()

    // Read and unfold lines (lines that start with space or tab are continuations)
    val rawLines = BufferedInputStream(iS).bufferedReader().readLines()
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith(" ") || line.startsWith('\t')) {
            if (lines.isNotEmpty()) {
                val prev = lines.removeAt(lines.size - 1)
                lines.add(prev + line.trimStart())
            } else {
                lines.add(line.trimStart())
            }
        } else {
            lines.add(line)
        }
    }

    var current = mutableMapOf<String, String>()
    // RDATE and EXDATE legitimately appear on several lines in one VEVENT, so unlike every other
    // property they have to accumulate instead of overwriting. The params half is kept per line
    // because each line carries its own TZID.
    var rdateProps = mutableListOf<Pair<String, String>>()
    var exdateProps = mutableListOf<Pair<String, String>>()
    var inEvent = false

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
            inEvent = true
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            continue
        }
        if (line.equals("END:VEVENT", ignoreCase = true)) {
            // finalize event
            try {
                val uid = current["UID"] ?: current["ID"] ?: ""
                val id = if (uid.isNotBlank()) uid.hashCode().toLong() else null
                val title = current["SUMMARY"] ?: "Untitled"
                val description = current["DESCRIPTION"] ?: ""
                val location = current["LOCATION"] ?: ""

                val (startMillis, startAllDay, startTz) = parseICSTime(current["DTSTART_PROP"], current["DTSTART"])
                val (endMillisRaw, _, endTzRaw) = parseICSTime(current["DTEND_PROP"], current["DTEND"])

                var endMillis = endMillisRaw
                if (endMillis == null) {
                    // try DURATION
                    val duration = current["DURATION"]
                    if (duration != null) {
                        endMillis = tryParseDurationMillis(duration, startMillis ?: 0L)
                    }
                }

                if (endMillis == null && startMillis != null) {
                    // as fallback, set end = start
                    endMillis = startMillis
                }

                val timezone = startTz ?: endTzRaw ?: "UTC"

                val rrule = current["RRULE"]?.let { RRule.parse(it, TimeZone.of(timezone)) }

                // If event was all-day but end time is same-day start, adjust end to next day
                if (startAllDay && startMillis != null && endMillis == startMillis) {
                    endMillis = startMillis + 1.days.inWholeMilliseconds
                }

                val zone = TimeZone.of(timezone)
                val startDate = startMillis?.let {
                    Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
                }
                // DTSTART is an occurrence in its own right, so the model keeps RDATE as the extra
                // days only; a file that lists the start date again would otherwise double it up.
                val rdate = parseIcsDates(rdateProps, zone).filter { it != startDate }
                val exdate = parseIcsDates(exdateProps, zone)

                val evt = Event(id, -1, title, description, location, null, startMillis ?: 0L, endMillis ?: (startMillis ?: 0L), timezone,
                    startAllDay, rrule, exdate = exdate, rdate = rdate)
                events.add(evt)
            } catch (e: Exception) {
                Log.e("IcsImport", "Error parsing VEVENT", e)
            }
            inEvent = false
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            continue
        }

        if (!inEvent) continue

        // Split property into name;params:value
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) continue
        val left = line.take(colonIndex)
        val value = line.substring(colonIndex + 1)

        // Extract property name and keep full left for param-aware keys
        val semicolonIndex = left.indexOf(';')
        val propName = if (semicolonIndex > 0) left.take(semicolonIndex).uppercase() else left.uppercase()

        // Store value; also keep property with params for DTSTART/DTEND
        when (propName) {
            "DTSTART" -> {
                current["DTSTART"] = value
                current["DTSTART_PROP"] = left // keep params
            }
            "DTEND" -> {
                current["DTEND"] = value
                current["DTEND_PROP"] = left
            }
            "RDATE" -> rdateProps.add(left to value)
            "EXDATE" -> exdateProps.add(left to value)
            else -> current[propName] = value
        }
    }

    return events
}

/**
 * The days a set of RDATE or EXDATE lines names, in [zone]. Each line's value is itself a
 * comma-separated list, and a value is either a bare day or a datetime that has to be resolved to
 * one.
 */
private fun parseIcsDates(props: List<Pair<String, String>>, zone: TimeZone): List<LocalDate> =
    props.flatMap { (left, values) -> values.split(",").map { left to it.trim() } }
        .mapNotNull { (left, value) ->
            if (value.length == 8 && value.all { it.isDigit() }) {
                runCatching { AllDayFormat.parse(value) }.getOrNull()
            } else {
                // Reuse the DTSTART/DTEND parser so TZID, 'Z' and floating values behave the same.
                parseICSTime(left, value).first?.let {
                    Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
                }
            }
        }
        .distinct()
        .sorted()

// Parse ICS time value with optional params-left (like DTSTART;TZID=America/Los_Angeles)
private fun parseICSTime(propLeft: String?, value: String?): Triple<Long?, Boolean, String?> {
    if (value == null) return Triple(null, false, null)

    val left = propLeft ?: ""
    val up = left.uppercase()

    // all-day if VALUE=DATE or value is 8 chars
    val isAllDay = up.contains("VALUE=DATE") || value.length == 8 && value.all { it.isDigit() }

    return try {
        if (isAllDay) {
            val dt = AllDayFormat.parse(value)
            // atStartOfDayIn returns an Instant
            val start = dt.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            Triple(start, true, "UTC")
        } else {
            // 1. Handle UTC 'Z' suffix or explicit offsets
            if (value.endsWith("Z") || value.contains("+") || value.contains("-")) {
                // DateTimeComponents.parse returns a result we convert to an Instant
                val result = BasicIsoInstantFormat.parse(value)
                val instant = result.toInstantUsingOffset()
                Triple(instant.toEpochMilliseconds(), false, "UTC")
            } else {
                // 2. Handle strings without offsets (floating time)
                val tzid = extractTZID(left)
                val candidates = listOf(DateTimeFormat, DateTimeShortFormat)
                var parsedInstant: Instant? = null

                for (fmt in candidates) {
                    try {
                        val ldt = LocalDateTime.parse(value, fmt)
                        val zone = tzid?.let { TimeZone.of(it) } ?: TimeZone.UTC
                        parsedInstant = ldt.toInstant(zone)
                        break
                    } catch (_: IllegalArgumentException) {
                        // try next candidate
                    }
                }

                if (parsedInstant != null) {
                    Triple(parsedInstant.toEpochMilliseconds(), false, tzid ?: "UTC")
                } else {
                    Triple(null, false, tzid)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("IcsImport", "Error parsing ICS time: $value", e)
        Triple(null, false, null)
    }
}

private fun extractTZID(left: String): String? =
    left.split(';')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0].uppercase() == "TZID" }
        ?.get(1)

private fun tryParseDurationMillis(duration: String, startMillis: Long): Long? =
    runCatching { startMillis + Duration.parse(duration).inWholeMilliseconds }.getOrNull()

// Formats for local times without offset
val DateTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
}

val DateTimeShortFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute()
}
