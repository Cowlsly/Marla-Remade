package com.vayunmathur.calendar.data
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.parseIcalBasicDate
import com.vayunmathur.calendar.util.toIcalBasic
import com.vayunmathur.calendar.util.toIcalUtcDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Serializable
data class Event(
    val id: Long?,
    val calendarID: Long,
    val title: String,
    val description: String,
    val location: String,
    val color: Int?,
    // start and end are utc
    val start: Long,
    val end: Long,
    val timezone: String = "UTC",
    val allDay: Boolean,
    val rrule: RRule?,
    val exdate: List<LocalDate> = emptyList(),
    /**
     * Extra dates this event also happens on, beyond the pattern in [rrule]. RFC 5545 RDATE, which
     * is how "repeat on these specific days" is expressed without a pattern: [rrule] is then null
     * and the occurrences are the start date plus these.
     */
    val rdate: List<LocalDate> = emptyList(),
    val reminders: List<Int> = emptyList(), // minutes before start
) {

    /** RRULE and RDATE are independent ways to repeat, and either one makes this a series. */
    val isRecurring: Boolean get() = rrule != null || rdate.isNotEmpty()

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.of(timezone))

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(TimeZone.of(timezone))

    fun toContentValues(calendarId: Long): ContentValues {
        val tz = if (allDay) "UTC" else timezone
        val tzObj = TimeZone.of(tz)
        // All-day events follow RFC 5545: DTSTART/DTEND are aligned to midnight UTC and
        // DTEND is exclusive (the midnight after the last covered day). `end` is already
        // stored as that exclusive instant, so we only need to snap both ends to midnight.
        val dtstart = if (allDay) startDateTimeDisplay.date.atStartOfDayIn(tzObj).toEpochMilliseconds()
            else startDateTimeDisplay.toInstant(tzObj).toEpochMilliseconds()
        val dtendActual = if (allDay) endDateTimeDisplay.date.atStartOfDayIn(tzObj).toEpochMilliseconds()
            else endDateTimeDisplay.toInstant(tzObj).toEpochMilliseconds()
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.DTSTART, dtstart)
            if (isRecurring) {
                put(CalendarContract.Events.DTEND, null as Long?)
                val duration = (dtendActual - dtstart).milliseconds
                put(CalendarContract.Events.DURATION, duration.toIsoString())
                put(CalendarContract.Events.RRULE, rrule?.asString(startDateTimeDisplay.date, tzObj))
            } else {
                put(CalendarContract.Events.DTEND, dtendActual)
                put(CalendarContract.Events.DURATION, null as String?)
                put(CalendarContract.Events.RRULE, null as String?)
            }
            // Always written, including as null, so clearing the repeat also clears the dates.
            put(
                CalendarContract.Events.RDATE,
                rdate.takeIf { it.isNotEmpty() }?.joinToString(",") { date ->
                    if (allDay) date.toIcalBasic()
                    else date.toIcalUtcDateTime(startDateTimeDisplay.time, tzObj)
                },
            )
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (exdate.isNotEmpty()) {
                put(CalendarContract.Events.EXDATE, exdate.joinToString(",") { it.toIcalBasic() })
            }
        }
    }

    companion object {
        fun getAllEvents(context: Context): List<Event> {
            val events = mutableListOf<Event>()
            val remindersByEvent = loadReminders(context)

            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.EXDATE,
                CalendarContract.Events.RDATE
            )
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    // Resolve column indices once, not per row.
                    val idIdx = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
                    val calIdx = it.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
                    val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    val descIdx = it.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                    val locIdx = it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                    val colorIdx = it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_COLOR)
                    val startIdx = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                    val endIdx = it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                    val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                    val tzIdx = it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)
                    val deletedIdx = it.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
                    val rruleIdx = it.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
                    val durationIdx = it.getColumnIndexOrThrow(CalendarContract.Events.DURATION)
                    val exdateIdx = it.getColumnIndexOrThrow(CalendarContract.Events.EXDATE)
                    val rdateIdx = it.getColumnIndexOrThrow(CalendarContract.Events.RDATE)

                    while (it.moveToNext()) {
                        try {
                            val id = it.getLong(idIdx)
                            val calendarID = it.getLong(calIdx)
                            val title = it.getString(titleIdx)
                            val description = it.getStringOrNull(descIdx) ?: ""
                            val location = it.getStringOrNull(locIdx) ?: ""
                            val color = it.getIntOrNull(colorIdx)
                            val start = it.getLong(startIdx)
                            var end = it.getLong(endIdx)
                            val allDay = it.getInt(allDayIdx) == 1
                            val tz = runCatching {
                                TimeZone.of(it.getStringOrNull(tzIdx) ?: TimeZone.currentSystemDefault().id)
                            }.getOrDefault(TimeZone.currentSystemDefault())
                            val timezone = tz.id
                            val deleted = it.getInt(deletedIdx) == 1
                            val rrule = it.getStringOrNull(rruleIdx) ?: ""
                            val duration = it.getStringOrNull(durationIdx)
                            val exdateStr = it.getStringOrNull(exdateIdx)
                            val rdateStr = it.getStringOrNull(rdateIdx)


                            val durationMillis = duration?.let { duration -> try {Duration.parse(duration).inWholeMilliseconds } catch(_: Exception) {0} }

                            if(end == 0L && durationMillis != null) {
                                end = start + durationMillis
                            }

                            // Parse EXDATE field - comma-separated RFC 5545 dates (YYYYMMDD or YYYYMMDDTHHMMSSZ)
                            val exdate = exdateStr?.split(",")?.mapNotNull { parseIcalBasicDate(it.trim()) }
                                ?: emptyList()

                            // RDATE is the same shape, and may carry a leading "TZID=...:" or
                            // "VALUE=DATE:" parameter when another client wrote it.
                            val rdate = rdateStr?.split(",")?.mapNotNull {
                                parseIcalBasicDate(it.trim().substringAfterLast(':'))
                            } ?: emptyList()

                            if (deleted) continue

                            if (title == null) continue
                            val event = Event(
                                id,
                                calendarID,
                                title,
                                description,
                                location,
                                color,
                                start,
                                end,
                                timezone,
                                allDay,
                                RRule.parse(rrule, tz),
                                exdate,
                                rdate,
                                remindersByEvent[id]?.sorted() ?: emptyList(),
                            )
                            events.add(event)
                        } catch (e: Exception) {
                            Log.e("Event", "Error constructing event from cursor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Event", "Error querying events", e)
            }

            return events
        }

        /**
         * Load every reminder in one query and group by event id, avoiding an
         * N+1 lookup per event. Returns event id -> list of minutes-before.
         */
        private fun loadReminders(context: Context): Map<Long, List<Int>> {
            val map = HashMap<Long, MutableList<Int>>()
            runCatching {
                context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                    null, null, null,
                )?.use { c ->
                    val eIdx = c.getColumnIndexOrThrow(CalendarContract.Reminders.EVENT_ID)
                    val mIdx = c.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                    while (c.moveToNext()) {
                        val minutes = c.getInt(mIdx).let { if (it < 0) 0 else it }
                        map.getOrPut(c.getLong(eIdx)) { mutableListOf() }.add(minutes)
                    }
                }
            }.onFailure { Log.e("Event", "Error querying reminders", it) }
            return map
        }
    }
}