package com.vayunmathur.calendar.ui

import com.vayunmathur.library.ui.R as UiR
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.ProvideTextStyle
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.RecurrenceDates
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.util.ResultEffect
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior

// Result keys for the date/time pickers
private const val KEY_START_DATE = "EditEvent.startDate"
private const val KEY_END_DATE = "EditEvent.endDate"
private const val KEY_START_TIME = "EditEvent.startTime"
private const val KEY_END_TIME = "EditEvent.endTime"
private const val KEY_RECURRENCE = "EditEvent.recurrence"
private const val KEY_CALENDAR = "EditEvent.calendar"
private const val KEY_TIMEZONE = "EditEvent.timezone"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(viewModel: CalendarViewModel, editRoute: Route.EditEvent, backStack: NavBackStack<Route>) {
    val eventId = editRoute.id
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val event = events.find { it.id == eventId }

    val znow = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = znow.date
    val now = znow.time

    var title by remember { mutableStateOf(event?.title ?: editRoute.title ?: "") }
    var descriptionText by remember { mutableStateOf(event?.description ?: editRoute.description ?: "") }
    val descriptionController = com.vayunmathur.library.ui.rememberOdfMarkdownEditorController(initialMarkdown = descriptionText) { descriptionText = it }
    var location by remember { mutableStateOf(event?.location ?: editRoute.location ?: "") }
    // default to the event's calendar if editing; otherwise the last calendar the user picked, then first editable
    var selectedCalendar by remember {
        mutableLongStateOf(
            event?.calendarID
                ?: viewModel.getDefaultCalendarId()?.takeIf { id -> calendars.any { it.id == id && it.canModify } }
                ?: calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id ?: -1L
        )
    }
    // If calendars load/refresh after composition, ensure the default remains a valid, editable calendar when creating a new event
    LaunchedEffect(calendars) {
        if (event == null) {
            val current = selectedCalendar
            val currentIsEditable = calendars.any { it.id == current && it.canModify }
            if (!currentIsEditable) {
                val fallback = viewModel.getDefaultCalendarId()?.takeIf { id -> calendars.any { it.id == id && it.canModify } }
                    ?: calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id
                if (fallback != null) selectedCalendar = fallback
            }
        }
    }

    val initialBeginLdt = editRoute.beginTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
    val initialEndLdt = editRoute.endTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
        ?: initialBeginLdt?.let {
            val tz = TimeZone.currentSystemDefault()
            it.toInstant(tz).plus(1.hours).toLocalDateTime(tz)
        }

    var allDay by remember { mutableStateOf(event?.allDay ?: editRoute.allDay ?: false) }
    var startDate by remember { mutableStateOf(event?.startDateTimeDisplay?.date ?: initialBeginLdt?.date ?: today) }
    // Stored all-day end is exclusive (midnight after the last day), so show the last covered day.
    var endDate by remember {
        mutableStateOf(
            event?.let { if (it.allDay) it.endDateTimeDisplay.date.minus(DatePeriod(days = 1)) else it.endDateTimeDisplay.date }
                ?: initialEndLdt?.date ?: startDate
        )
    }
    var startTime by remember { mutableStateOf(event?.startDateTimeDisplay?.time ?: initialBeginLdt?.time ?: now) }
    var endTime by remember { mutableStateOf(event?.endDateTimeDisplay?.time ?: initialEndLdt?.time ?: startTime) }
    var timezone by remember { mutableStateOf(event?.timezone ?: TimeZone.currentSystemDefault().id) }
    var rruleObj by remember { mutableStateOf(event?.rrule) }
    var rdateObj by remember { mutableStateOf(event?.rdate ?: emptyList()) }
    val repeatSummary by remember {
        derivedStateOf {
            when {
                rdateObj.isNotEmpty() ->
                    // The event's own date counts as an occurrence alongside the picked ones.
                    context.resources.getQuantityString(
                        R.plurals.repeat_dates_summary, rdateObj.size + 1, rdateObj.size + 1,
                    )
                else -> rruleObj?.describe(context) ?: ""
            }
        }
    }
    var reminders by remember { mutableStateOf(event?.reminders ?: emptyList()) }

    // Shift the end date/time to preserve the current event duration when the start moves.
    fun applyStartChange(newStartDate: LocalDate, newStartTime: LocalTime) {
        val tz = TimeZone.of(timezone)
        val oldStart = startDate.atTime(startTime).toInstant(tz)
        val oldEnd = endDate.atTime(endTime).toInstant(tz)
        var dur = oldEnd - oldStart
        if (dur.isNegative()) dur = Duration.ZERO
        startDate = newStartDate
        startTime = newStartTime
        val newEndLdt = (newStartDate.atTime(newStartTime).toInstant(tz) + dur).toLocalDateTime(tz)
        endDate = newEndLdt.date
        endTime = newEndLdt.time
    }

    // Collect results from pickers
    ResultEffect<LocalDate>(KEY_START_DATE) { selected ->
        applyStartChange(selected, startTime)
    }

    ResultEffect<LocalDate>(KEY_END_DATE) { selected ->
        // ensure end date is not before start date
        endDate = maxOf(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_START_TIME) { selected ->
        applyStartChange(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_END_TIME) { selected ->
        // ensure end time is not before start time when on same date
        endTime = if (endDate == startDate) {
            maxOf(selected, startTime)
        } else {
            selected
        }
    }

    // Recurrence dialog result: either a pattern or a hand-picked set of dates, never both.
    ResultEffect<RRule>(KEY_RECURRENCE) { res ->
        rruleObj = res
        rdateObj = emptyList()
    }
    ResultEffect<RecurrenceDates>(KEY_RECURRENCE) { res ->
        rdateObj = res.dates
        rruleObj = null
    }

    // Result key for calendar picker
    // open dialog via navigation and handle result
    ResultEffect<Long>(KEY_CALENDAR) { calId ->
        selectedCalendar = calId
    }

    // Timezone selector (navigation dialog) - open via Nav route and handle result
    ResultEffect<String>(KEY_TIMEZONE) { z -> timezone = z }

    DetailScaffold(
        title = "",
        onNavigateBack = { backStack.pop() },
        actions = {
            IconButton(onClick = {
                val buildTz = if (allDay) TimeZone.UTC else TimeZone.of(timezone)
                // All-day events are stored at midnight UTC with an exclusive end (the midnight
                // after the last selected day), matching RFC 5545 / the Android calendar provider.
                val startInstant = if (allDay) startDate.atStartOfDayIn(buildTz)
                    else startDate.atTime(startTime).toInstant(buildTz)
                val endInstant = if (allDay) endDate.plus(DatePeriod(days = 1)).atStartOfDayIn(buildTz)
                    else endDate.atTime(endTime).toInstant(buildTz)
                val newEvent = Event(
                    id = eventId,
                    calendarID = selectedCalendar,
                    title = title,
                    description = descriptionText,
                    location = location,
                    color = event?.color,
                    start = startInstant.toEpochMilliseconds(),
                    end = endInstant.toEpochMilliseconds(),
                    timezone = if (allDay) "UTC" else timezone,
                    allDay = allDay,
                    rrule = rruleObj,
                    exdate = event?.exdate ?: emptyList(),
                    rdate = rdateObj,
                    reminders = reminders,
                )
                viewModel.upsertEvent(eventId, newEvent.toContentValues(selectedCalendar), reminders)
                backStack.pop()
            }) {
                IconSave()
            }
        },
        bottomBar = {
            if (descriptionController.focused) {
                com.vayunmathur.library.ui.OdfMarkdownEditorToolbar(descriptionController)
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text(stringResource(R.string.label_title)) })

            // Calendar selector: moved above the datetime section — only when creating a new event
            if (eventId == null) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color(calendars.find { it.id == selectedCalendar }?.color ?: 0))) },
                    { Text(calendars.find { it.id == selectedCalendar }?.displayName ?: stringResource(R.string.select_calendar), Modifier.clickable { backStack.add(Route.EditEvent.CalendarPickerDialog(KEY_CALENDAR)) }) },
                    {}
                )
            }

            Text(
                text = stringResource(R.string.label_description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            com.vayunmathur.library.ui.OdfMarkdownEditorField(
                controller = descriptionController,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .heightIn(min = 96.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Item(
                { IconSchedule() },
                {Text(stringResource(R.string.all_day))},
                { Switch(allDay, { allDay = it }) }
            )

            // Recurrence selector
            val repeats = rruleObj != null || rdateObj.isNotEmpty()
            Item(
                { /* icon placeholder */ },
                { Text(if (!repeats) stringResource(R.string.does_not_repeat) else repeatSummary.ifBlank { stringResource(R.string.repeats) }, Modifier.clickable {
                    // pass initial RecurrenceParams based on existing rrule
                    val initial = RecurrenceParams.fromRRule(rruleObj)
                    backStack.add(Route.EditEvent.RecurrenceDialog(KEY_RECURRENCE, startDate, initial, rdateObj))
                }) },
                { if (repeats) Text(stringResource(UiR.string.remove), Modifier.clickable {
                    rruleObj = null
                    rdateObj = emptyList()
                }) }
            )

            Item(
                {},
                { Text(DateString.dateWeekday(startDate), Modifier.clickable {
                    // open date picker dialog
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_START_DATE, startDate))
                }) },
                { if(!allDay) Text(DateString.time(startTime, DateFormat.is24HourFormat(context)), Modifier.clickable {
                    // open time picker dialog
                    // no min time for start
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_START_TIME, startTime, null))
                }) }
            )
            Item(
                {},
                { Text(DateString.dateWeekday(endDate), Modifier.clickable {
                    // when opening end date, prevent selecting a date before startDate
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_END_DATE, endDate, startDate))
                }) },
                { if(!allDay) Text(DateString.time(endTime, DateFormat.is24HourFormat(context)), Modifier.clickable{
                    // when opening end time, supply minTime if endDate equals startDate
                    val minTime = if (endDate == startDate) startTime else null
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_END_TIME, endTime, minTime))
                }) }
            )

            if (!allDay) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color.Transparent)) { IconGlobe() } },
                    { Text(timezone, Modifier.clickable { backStack.add(Route.EditEvent.TimezonePickerDialog(KEY_TIMEZONE)) }) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // Reminders
            reminders.forEach { minutes ->
                Item(
                    { IconSchedule() },
                    { Text(reminderLabel(context, minutes)) },
                    { Text(stringResource(UiR.string.remove), Modifier.clickable { reminders = reminders - minutes }) },
                )
            }
            var addReminderExpanded by remember { mutableStateOf(false) }
            val available = REMINDER_PRESETS.filter { it !in reminders }
            if (available.isNotEmpty()) {
                Item(
                    { IconSchedule() },
                    {
                        Box {
                            Text(stringResource(R.string.add_reminder), Modifier.clickable { addReminderExpanded = true })
                            DropdownMenu(addReminderExpanded, { addReminderExpanded = false }) {
                                available.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(reminderLabel(context, m)) },
                                        onClick = {
                                            addReminderExpanded = false
                                            reminders = (reminders + m).sorted()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            // The location line on the detail screen morphs into this field. sharedTextKey, not a
            // modifier: the editable line fills the field's width, so keying the field itself would
            // balloon the arriving text out to that width and snap it back on landing. Null for a new
            // event, which has no detail screen to have come from.
            LabeledTextField(
                value = location,
                onValueChange = { location = it },
                label = stringResource(R.string.label_location),
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                singleLine = false,
                sharedTextKey = editRoute.id?.let { "calendar-event-location-$it" },
            )
    }
}

@Composable
fun Item(icon: @Composable () -> Unit = {}, left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.padding(8.dp).padding(horizontal = 8.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Box(Modifier.size(24.dp)) {
                icon()
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                left()
            }
            right()
        }
    }
}

/** Common reminder offsets, in minutes before the event start. */
val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

fun reminderLabel(context: android.content.Context, minutes: Int): String = when {
    minutes <= 0 -> context.getString(R.string.reminder_at_time_of_event)
    minutes % 1440 == 0 -> context.resources.getQuantityString(R.plurals.reminder_days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> context.resources.getQuantityString(R.plurals.reminder_hours_before, minutes / 60, minutes / 60)
    else -> context.resources.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
}