package com.vayunmathur.calendar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.EventActions
import com.vayunmathur.calendar.util.EventUiState
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import com.vayunmathur.library.ui.DateString

/** Binds [CalendarViewModel] to the stateless [EventScreen]. */
@Composable
fun EventScreen(viewModel: CalendarViewModel, instance: Instance, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    val event = events.find { it.id == instance.eventID }
    if (event == null) {
        // simple empty state
        Text(stringResource(R.string.event_not_found))
        return
    }

    val calendar = calendars.find { it.id == event.calendarID }!!

    EventScreen(
        state = EventUiState(event = event, calendar = calendar, instance = instance),
        // Deleting is the ViewModel's; navigating is the binder's.
        actions = object : EventActions by viewModel {
            override fun closeEvent() {
                backStack.pop()
            }

            override fun editEvent(eventId: Long) {
                backStack.add(Route.EditEvent(eventId))
            }
        },
    )
}

/**
 * The event detail screen, with no dependency on the ViewModel so it can be rendered from
 * a `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(state: EventUiState, actions: EventActions) {
    val event = state.event
    val calendar = state.calendar
    val instance = state.instance

    val context = LocalContext.current
    val openLocationLabel = stringResource(R.string.open_location_in_navigation)

    val isEditable = calendar.canModify
    var showDeleteMenu by remember { mutableStateOf(false) }

    DetailScaffold(
        title = "",
        onNavigateBack = actions::closeEvent,
        actions = {
            if(isEditable) {
                IconButton({
                    actions.editEvent(event.id!!)
                }) {
                    IconEdit()
                }
                Box {
                    IconButton({
                        if (event.isRecurring) {
                            // Recurring event - show dropdown menu
                            showDeleteMenu = true
                        } else {
                            // Non-recurring event - delete directly
                            actions.deleteEventSeries(event.id!!)
                            actions.closeEvent()
                        }
                    }) {
                        IconDelete()
                    }
                    DropdownMenu(
                        expanded = showDeleteMenu,
                        onDismissRequest = { showDeleteMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_this_event)) },
                            onClick = {
                                showDeleteMenu = false
                                actions.deleteEventInstance(event.id!!, instance.begin)
                                actions.closeEvent()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_all_events)) },
                            onClick = {
                                showDeleteMenu = false
                                actions.deleteEventSeries(event.id!!)
                                actions.closeEvent()
                            }
                        )
                    }
                }
            }
        }
    ) {
            ListItem({
                Text(event.title, style = MaterialTheme.typography.titleLarge)
            }, supportingContent = {
                Column {
                    Text(calendar.displayName)
                    Text(dateRangeString(context,instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay))
                    instance.rrule?.let { Text(it.describe(context)) }
                }
            }, leadingContent = {
                Box(Modifier.size(24.dp).background(Color(calendar.color), RoundedCornerShape(4.dp)))
            })
            if(event.description.isNotBlank()) ListItem({
                Text(com.vayunmathur.library.util.parseMarkdown(event.description, showMarkers = false))
            }, leadingContent = {
                IconDescription()
            })
            if(event.location.isNotBlank()) ListItem(
                { Text(event.location) },
                Modifier.clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        // "geo:0,0?q=<text>" lets any installed maps/navigation
                        // app (Google Maps, Waze, our own maps app, etc.)
                        // resolve the address. Wrap with chooser so user can
                        // pick if multiple are installed.
                        "geo:0,0?q=${Uri.encode(event.location)}".toUri()
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    val chooser = Intent.createChooser(
                        intent,
                        openLocationLabel
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    try {
                        context.startActivity(chooser)
                    } catch (_: ActivityNotFoundException) {
                        // No nav app installed — silently drop; the text is
                        // still selectable elsewhere.
                    }
                },
                leadingContent = { IconGlobe() },
            )
    }
}

fun dateRangeString(context: Context, startDate: LocalDate, endDate: LocalDate, startTime: LocalTime, endTime: LocalTime, allDay: Boolean, includeDate: Boolean = true): String {
    return if(allDay) {
        if(startDate.toEpochDays() + 1 == endDate.toEpochDays()) {
            if (includeDate) DateString.dateWeekday(startDate) else context.getString(R.string.all_day)
        } else {
            context.getString(R.string.date_range_format, DateString.dateWeekday(startDate), DateString.dateWeekday(endDate))
        }
    } else {
        val is24 = DateFormat.is24HourFormat(context)
        if(startDate == endDate) {
            if (includeDate) {
                context.getString(R.string.date_time_range_format, DateString.dateWeekday(startDate), DateString.time(startTime, is24), DateString.time(endTime, is24))
            } else {
                context.getString(R.string.date_range_format, DateString.time(startTime, is24), DateString.time(endTime, is24))
            }
        } else {
            context.getString(R.string.full_date_time_range_format, DateString.dateWeekday(startDate), DateString.time(startTime, is24), DateString.dateWeekday(endDate), DateString.time(endTime, is24))
        }
    }
}
