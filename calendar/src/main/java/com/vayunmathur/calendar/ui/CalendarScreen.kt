package com.vayunmathur.calendar.ui

import com.vayunmathur.library.util.DateNameStyle
import android.text.format.DateFormat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarActions
import com.vayunmathur.calendar.util.CalendarUiState
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconToday
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.sharedText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localeFirstDayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.datetime.todayIn
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun firstDayOfWeekOffset(date: LocalDate, locale: Locale): Int {
    val firstDayOfWeek = localeFirstDayOfWeek(locale)
    return (date.dayOfWeek.isoDayNumber - firstDayOfWeek + 7) % 7
}

/** Binds [CalendarViewModel] to the stateless [CalendarScreen]. */
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendarsList by viewModel.calendars.collectAsStateWithLifecycle()
    val calendars = remember(calendarsList) { calendarsList.associateBy { it.id } }
    val calendarVisibility by viewModel.calendarVisibility.collectAsStateWithLifecycle()
    val currentLayout by viewModel.currentLayout.collectAsStateWithLifecycle()

    // currently-viewed date is owned by the VM (initialized from persisted
    // last_viewed_date in DataStore, or today when absent).
    val dateViewing by viewModel.selectedDate.collectAsStateWithLifecycle()

    // Stable anchor for pagers/scrollers — always use today so the initial
    // page offset is correct regardless of any stale persisted date.
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    ResultEffect<LocalDate>("GotoDate") { result ->
        viewModel.setSelectedDate(result)
    }

    CalendarScreen(
        state = CalendarUiState(
            layout = currentLayout,
            dateViewing = dateViewing,
            today = today,
            events = events,
            calendars = calendars,
            calendarVisibility = calendarVisibility,
        ),
        // Navigation is the binder's job; everything else comes from the ViewModel. Not
        // remembered, so `dateViewing` captured below is always the current one.
        actions = object : CalendarActions by viewModel {
            override fun openDatePicker(date: LocalDate) {
                backStack.add(Route.Calendar.GotoDialog(date))
            }

            override fun openSettings() {
                backStack.add(Route.Settings)
            }

            override fun openEvent(instance: Instance) {
                viewModel.setLastViewedDate(dateViewing)
                backStack.add(Route.Event(instance))
            }

            override fun createEvent() {
                // persist currently viewed date before navigating to the new event page
                viewModel.setLastViewedDate(dateViewing)
                backStack.add(Route.EditEvent(null))
            }

            override fun createEventOn(date: LocalDate) {
                viewModel.setLastViewedDate(dateViewing)
                // Pre-fill the pressed date at the current hour so the editor opens on a sensible
                // timed slot; the editor derives a default end an hour later.
                val tz = TimeZone.currentSystemDefault()
                val nowTime = Clock.System.now().toLocalDateTime(tz).time
                val begin = date.atTime(nowTime.hour, 0).toInstant(tz).toEpochMilliseconds()
                backStack.add(Route.EditEvent(null, beginTime = begin))
            }
        },
    )
}

/**
 * The calendar screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: CalendarUiState, actions: CalendarActions) {
    val context = LocalContext.current

    // shared vertical scroll so hour labels and grid scroll together
    val verticalState = rememberScrollState()

    AppScaffold(
        title = {
            // show month/year of the currently visible date
            val mon = localizedMonthNames(DateNameStyle.SHORT)[state.dateViewing.month.number - 1]
            Row(
                Modifier.clickable { actions.openDatePicker(state.dateViewing) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.month_year_format, mon, state.dateViewing.year), fontWeight = FontWeight.Bold)
                IconArrowDropDown()
            }
        },
        actions = {
            var showLayoutMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showLayoutMenu = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(state.layout.shortNameRes), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        IconArrowDropDown(tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                    CalendarViewModel.CalendarLayout.entries.forEach { layout ->
                        DropdownMenuItem(
                            text = { Text(stringResource(layout.prettyNameRes)) },
                            onClick = {
                                actions.setLayout(layout)
                                showLayoutMenu = false
                            }
                        )
                    }
                }
            }

            IconButton({ actions.setSelectedDate(state.today) }) {
                IconToday()
            }

            IconButton({ actions.openSettings() }) {
                IconSettings()
            }
        },
        floatingActionButton = {
            FloatingActionButton({ actions.createEvent() }) {
                IconAdd()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            when (state.layout) {
                CalendarViewModel.CalendarLayout.Agenda -> AgendaView(
                    context, state.events, state.calendars, state.calendarVisibility, state.today, state.dateViewing,
                    actions::visibleInstances,
                    onEventClick = { actions.openEvent(it) },
                    onDateViewingChanged = { actions.setSelectedDate(it) }
                )
                CalendarViewModel.CalendarLayout.Month -> MonthView(
                    context, state.events, state.calendars, state.calendarVisibility, state.today, state.dateViewing,
                    actions::visibleInstances,
                    onEventClick = { actions.openEvent(it) },
                    onDayClick = { actions.setSelectedDate(it) },
                    onDayLongClick = { actions.createEventOn(it) },
                    onDateViewingChanged = { actions.setSelectedDate(it) },
                    previewInstances = state.previewInstances,
                )
                else -> {
                    CalendarPagerView(
                        context,
                        state.layout,
                        state.today,
                        state.dateViewing,
                        state.events,
                        state.calendars,
                        state.calendarVisibility,
                        verticalState,
                        actions::visibleInstances,
                        onEventClick = { actions.openEvent(it) },
                        onDateViewingChanged = { actions.setSelectedDate(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun CalendarPagerView(
    context: android.content.Context,
    currentLayout: CalendarViewModel.CalendarLayout,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    verticalState: ScrollState,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val daysToShow = when (currentLayout) {
        CalendarViewModel.CalendarLayout.Day -> 1
        CalendarViewModel.CalendarLayout.WorkWeek,
        CalendarViewModel.CalendarLayout.WorkWeekSummary,
        CalendarViewModel.CalendarLayout.WorkWeekCompact -> 5
        else -> 7
    }
    val isSummary = currentLayout == CalendarViewModel.CalendarLayout.WorkWeekSummary || 
                    currentLayout == CalendarViewModel.CalendarLayout.FullWeekSummary
    val isCompact = currentLayout == CalendarViewModel.CalendarLayout.WorkWeekCompact ||
                    currentLayout == CalendarViewModel.CalendarLayout.FullWeekCompact
    
    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    // Track whether the pager is being programmatically scrolled to avoid feedback loops
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, currentLayout) {
        if (programmaticScroll) return@LaunchedEffect
        val delta = pagerState.currentPage - 5000
        val currentStart = if (daysToShow == 1) {
            anchorDate.plus(DatePeriod(days = delta))
        } else {
            anchorDate.plus(DatePeriod(days = delta * 7))
        }
        onDateViewingChanged(currentStart)
    }

    LaunchedEffect(dateViewing) {
        if (!pagerState.isScrollInProgress) {
            val delta = if (daysToShow == 1) {
                dateViewing.toEpochDays() - anchorDate.toEpochDays()
            } else {
                (dateViewing.toEpochDays() - anchorDate.toEpochDays()) / 7
            }
            val targetPage = 5000 + delta.toInt()
            if (pagerState.currentPage != targetPage) {
                programmaticScroll = true
                pagerState.scrollToPage(targetPage)
                programmaticScroll = false
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val delta = page - 5000
        val pageStartDate = if (daysToShow == 1) {
            anchorDate.plus(DatePeriod(days = delta))
        } else {
            anchorDate.plus(DatePeriod(days = delta * 7))
        }
        
        val startDay = when (currentLayout) {
            CalendarViewModel.CalendarLayout.Day -> pageStartDate
            CalendarViewModel.CalendarLayout.WorkWeek,
            CalendarViewModel.CalendarLayout.WorkWeekSummary,
            CalendarViewModel.CalendarLayout.WorkWeekCompact ->
                pageStartDate.minus(DatePeriod(days = (pageStartDate.dayOfWeek.isoDayNumber - 1) % 7))
            else -> {
                val locale = context.resources.configuration.locales[0]
                pageStartDate.minus(DatePeriod(days = firstDayOfWeekOffset(pageStartDate, locale)))
            }
        }
        
        val weekDays = (0 until daysToShow).map { startDay.plus(DatePeriod(days = it)) }
        val vEventsByID = remember(events) { events.associateBy { it.id!! } }

        val weekInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, daysToShow) {
            value = loadInstances(
                weekDays.first().atStartOfDayIn(TimeZone.currentSystemDefault()),
                weekDays.last().atEndOfDayIn(TimeZone.currentSystemDefault())
            )
        }

        Column(Modifier.fillMaxSize()) {
            WeekHeader(weekDays, leadingGutter = !isSummary)
            
            if (isSummary) {
                SummaryGrid(context, weekInstances, vEventsByID, calendars, weekDays, onEventClick)
            } else {
                val (allDay, notAllDay) = weekInstances.partition { it.allDay }
                val allDayByDate = weekDays.associateWith { d -> allDay.filter { d in it.spanDays } }
                val timedByDateHour = weekDays.associateWith { d ->
                    notAllDay.filter { d in it.spanDays }.groupBy { it.startDateTime.hour }
                }

                AllDayRow(allDayByDate, vEventsByID, calendars, weekDays, onEventClick)
                HourlyGrid(
                    context,
                    timedByDateHour,
                    weekDays,
                    verticalState,
                    shrinkEmptyHours = isCompact,
                    onEventClick = onEventClick,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }
    }
}

/**
 * The key that morphs one event's title into the title on its detail screen, or null when this copy
 * is not the one the morph should start from.
 *
 * Keyed on the instance rather than the event: every occurrence of a repeating event is on screen at
 * once in a month, and they all share an event id. Keyed only on the day the occurrence starts,
 * because an event spanning days is drawn once per day it covers - and the week and month pagers keep
 * the neighbouring pages composed, so those copies are live too. One key, one origin.
 */
private fun eventTitleMorphKey(instance: Instance, date: LocalDate): String? =
    if (date == instance.startDateTime.date) "calendar-event-title-${instance.id}" else null

@Composable
fun SummaryGrid(
    context: android.content.Context,
    instances: List<Instance>,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        weekDays.forEachIndexed { index, day ->
            val dayInstances = instances.filter { day in it.spanDays }.sortedBy { it.startDateTime }
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                    .padding(2.dp)
            ) {
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    SummaryEventItem(context, instance, ev, calendars, onEventClick, eventTitleMorphKey(instance, day))
                }
            }
            if (index < weekDays.size - 1) {
                VerticalDivider(modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
fun SummaryEventItem(
    context: android.content.Context,
    instance: Instance,
    ev: Event,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit,
    titleSharedKey: Any? = null
) {
    val eventColor = Color(ev.color ?: calendars[ev.calendarID]!!.color)
    val onEventColor = contentColorOn(eventColor)
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(eventColor)
            .fillMaxWidth()
            .clickable { onEventClick(instance) }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column {
            Text(
                ev.title.ifEmpty { context.getString(R.string.no_title) },
                color = onEventColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey)
            )
            if (!instance.allDay) {
                val is24 = DateFormat.is24HourFormat(context)
                Text(
                    "${DateString.time(instance.startDateTime.time, is24)} - ${DateString.time(instance.endDateTime.time, is24)}",
                    color = onEventColor.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    lineHeight = 10.sp
                )
            }
        }
    }
}

/**
 * Month grid. [today] doubles as the pager anchor: page 5000 is the month containing it,
 * and it is the day drawn with the "today" highlight.
 */
@Composable
fun MonthView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    today: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit,
    /**
     * Pre-resolved instances, used instead of querying through [loadInstances]. Only a
     * preview passes this — see [com.vayunmathur.calendar.util.CalendarUiState].
     */
    previewInstances: List<Instance>? = null,
) {
    val anchorDate = today
    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        if (programmaticScroll) return@LaunchedEffect
        val monthDate = anchorDate.plus(DatePeriod(months = pagerState.currentPage - 5000))
        onDateViewingChanged(monthDate)
    }

    LaunchedEffect(dateViewing) {
        if (!pagerState.isScrollInProgress) {
            val monthsDiff = (dateViewing.year * 12 + dateViewing.month.number) - (anchorDate.year * 12 + anchorDate.month.number)
            val targetPage = 5000 + monthsDiff
            if (pagerState.currentPage != targetPage) {
                programmaticScroll = true
                pagerState.scrollToPage(targetPage)
                programmaticScroll = false
            }
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val monthDate = anchorDate.plus(DatePeriod(months = page - 5000))
        val firstOfMonth = LocalDate(monthDate.year, monthDate.month, 1)
        val lastOfMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        
        val locale = context.resources.configuration.locales[0]
        val firstDayOfWeek = localeFirstDayOfWeek(locale)
        val lastDayOfWeek = if (firstDayOfWeek == 1) 7 else firstDayOfWeek - 1
        
        val startDay = firstOfMonth.minus(DatePeriod(days = firstDayOfWeekOffset(firstOfMonth, locale)))
        val endDay = lastOfMonth.plus(DatePeriod(days = (lastDayOfWeek - lastOfMonth.dayOfWeek.isoDayNumber + 7) % 7))
        
        val weeks = remember(startDay, endDay) {
            buildList {
                var curr = startDay
                while (curr <= endDay) {
                    add(curr)
                    curr = curr.plus(DatePeriod(days = 7))
                }
            }
        }

        val vEventsByID = remember(events) { events.associateBy { it.id!! } }
        val loadedInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, endDay) {
            value = loadInstances(
                startDay.atStartOfDayIn(TimeZone.currentSystemDefault()),
                endDay.atEndOfDayIn(TimeZone.currentSystemDefault())
            )
        }
        val monthInstances = previewInstances ?: loadedInstances

        Column(Modifier.fillMaxSize().padding(4.dp), Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
                val headerStart = weeks.first()
                (0..6).forEach { i ->
                    val d = headerStart.plus(DatePeriod(days = i))
                    Text(
                        localizedDayOfWeekNames(DateNameStyle.SHORT)[d.dayOfWeek.isoDayNumber - 1],
                        Modifier.weight(1f),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            weeks.forEach { weekSunday ->
                MonthWeekRow(
                    Modifier.weight(1f),
                    weekSunday,
                    vEventsByID,
                    calendars,
                    onEventClick,
                    onDayClick,
                    onDayLongClick,
                    context,
                    monthDate.month.number,
                    monthInstances,
                    today
                )
            }
        }
    }
}

@Composable
fun MonthWeekRow(
    modifier: Modifier,
    weekSunday: LocalDate,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    context: android.content.Context,
    viewingMonth: Int,
    allInstances: List<Instance>,
    today: LocalDate
) {
    val weekDays = (0..6).map { weekSunday.plus(DatePeriod(days = it)) }

    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { date ->
            val dayInstances = allInstances.filter { date in it.spanDays }
                .sortedBy { it.startDateTime }
            val isToday = date == today
            val isPartOfViewingMonth = date.month.number == viewingMonth

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .combinedClickable(
                        onClick = { onDayClick(date) },
                        onLongClick = { onDayLongClick(date) },
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isToday) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = date.day.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = date.day.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPartOfViewingMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isPartOfViewingMonth) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.height(2.dp))
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    SummaryEventItem(context, instance, ev, calendars, onEventClick, eventTitleMorphKey(instance, date))
                }
            }
        }
    }
}

@Composable
fun AgendaView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val initialIndex = 50000
    val listState = rememberLazyListState(initialIndex)
    val vEventsByID = remember(events) { events.associateBy { it.id!! } }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            if (date != dateViewing) {
                onDateViewingChanged(date)
            }
        }
    }

    LaunchedEffect(dateViewing) {
        if (!listState.isScrollInProgress) {
            val targetIndex = initialIndex + (dateViewing.toEpochDays() - anchorDate.toEpochDays()).toInt()
            if (listState.firstVisibleItemIndex != targetIndex) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(100000) { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            val dayInstances by produceState(emptyList<Instance>(), date, events, calendarVisibility) {
                value = loadInstances(
                    date.atStartOfDayIn(TimeZone.currentSystemDefault()),
                    date.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.currentSystemDefault())
                ).sortedBy { it.startDateTime }
            }

            val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
            val isToday = date == today

            Column(Modifier.fillMaxWidth().then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)) else Modifier)) {
                Text(
                    text = DateString.dateWeekday(date),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                dayInstances.filter { date in it.spanDays }.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    val titleKey = eventTitleMorphKey(instance, date)
                    ListItem(
                        content = {
                            Text(
                                ev.title.ifEmpty { context.getString(R.string.no_title) },
                                modifier = if (titleKey == null) Modifier else Modifier.sharedText(titleKey)
                            )
                        },
                        supportingContent = {
                            Text(dateRangeString(context, instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay, includeDate = false))
                        },
                        leadingContent = {
                            Box(Modifier.size(16.dp).background(Color(ev.color ?: calendars[ev.calendarID]!!.color), CircleShape))
                        },
                        modifier = Modifier.clickable { onEventClick(instance) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

fun LocalDate.atEndOfDayIn(currentSystemDefault: TimeZone): Instant {
    return this.plus(DatePeriod(days = 1)).atStartOfDayIn(currentSystemDefault)
}

/** Width of the hour-label gutter down the left of the day/week grid. */
private val HourGutterWidth = 56.dp

@Composable
private fun WeekHeader(weekDays: List<LocalDate>, leadingGutter: Boolean) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        if (leadingGutter) Spacer(Modifier.width(HourGutterWidth))
        weekDays.forEach { d ->
            val isToday = d == today
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (isToday) Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        ) else Modifier
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    localizedDayOfWeekNames(DateNameStyle.SHORT)[d.dayOfWeek.isoDayNumber - 1],
                    Modifier,
                    if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    d.day.toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AllDayRow(
    allDayByDate: Map<LocalDate, List<Instance>>,
    events: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(HourGutterWidth))
        weekDays.forEach { d ->
            val instances = allDayByDate[d].orEmpty()
            Column(Modifier.weight(1f)) {
                if (instances.isEmpty()) {
                    Box(modifier = Modifier
                        .height(32.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {}
                } else {
                    Column {
                        instances.forEach { instance ->
                            val ev = events[instance.eventID]!!
                            val eventColor = Color(ev.color ?: calendars[ev.calendarID]!!.color)
                            val titleKey = eventTitleMorphKey(instance, d)
                            Box(
                                Modifier
                                    .padding(bottom = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(eventColor)
                                    .height(28.dp)
                                    .clickable { onEventClick(instance) }
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    ev.title.ifEmpty { stringResource(R.string.no_title) },
                                    Modifier
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .then(if (titleKey == null) Modifier else Modifier.sharedText(titleKey)),
                                    contentColorOn(eventColor),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyGrid(
    context: android.content.Context,
    timedByDateHour: Map<LocalDate, Map<Int, List<Instance>>>,
    weekDays: List<LocalDate>,
    verticalState: ScrollState,
    shrinkEmptyHours: Boolean,
    onEventClick: (Instance) -> Unit,
    innerPadding: PaddingValues
) {
    val minEventHeight = 18.dp
    val minEventWidth = 56.dp

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var now by remember { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            kotlinx.coroutines.delay(60.seconds)
        }
    }

    val eventsByDay = weekDays.associateWith { d ->
        timedByDateHour[d]?.values?.flatten().orEmpty().distinctBy { it.id }
    }
    val positionedByDay = eventsByDay.mapValues { (d, events) -> computePositionedEventsForDay(events, d) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottomPadding = innerPadding.calculateBottomPadding() + 4.dp
        // One scale for the whole page: the columns share the hour gutter, so a shrunk hour has to
        // be shrunk on every day at once.
        val scale = if (shrinkEmptyHours) {
            HourScale.collapsingEmptyHours(busyHours(positionedByDay.values.flatten()), maxHeight - bottomPadding)
        } else {
            HourScale.uniform()
        }

        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(verticalState)
                .padding(bottom = bottomPadding), Arrangement.spacedBy(4.dp)
        ) {
            HourLabelColumn(context, scale)

            // create 7 equal columns with weight so all 7 fit on screen
            for (d in weekDays) {
                val isToday = d == today

                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                    // background hourly grid — fixed 24 rows with faint hour separators
                    Column {
                        for (hour in 0..23) {
                            Box(
                                Modifier
                                    .height(scale.heightOf(hour))
                                    .fillMaxWidth()
                            ) {
                                if (hour != 0) {
                                    HorizontalDivider(
                                        Modifier.align(Alignment.TopStart),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    if (isToday) {
                        val yOffset = scale.offsetOf(now.hour * 60 + now.minute)
                        Box(
                            Modifier
                                .offset(y = yOffset - 1.dp)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.Red)
                                .zIndex(10f)
                        )
                    }

                    val positioned = positionedByDay.getValue(d)
                    val eventsForDay = eventsByDay.getValue(d)
                    val instancesById = remember(eventsForDay) { eventsForDay.associateBy { it.id } }

                    // overlay event segments positioned by their time within the day and column
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columnWidth = this.maxWidth

                        positioned.forEach { ev ->
                            val instance = instancesById.getValue(ev.instanceID)
                            // compute vertical position and height
                            val yOffset = scale.offsetOf(ev.startMinutes)
                            var heightDp = scale.offsetOf(ev.endMinutes) - yOffset
                            if (heightDp < minEventHeight) heightDp = minEventHeight

                            // compute horizontal position and size
                            val widthFraction = 1f / ev.totalColumns.toFloat()
                            val xFraction = ev.columnIndex * widthFraction
                            val xOffsetDp = columnWidth * xFraction
                            val widthDp = (columnWidth * widthFraction).coerceAtLeast(minEventWidth)

                            Box(
                                Modifier
                                    .offset(xOffsetDp, yOffset)
                                    .size(widthDp, heightDp)
                                    .padding(2.dp)
                                    .zIndex(1f + ev.columnIndex * 0.01f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(ev.color))
                                    .clickable { onEventClick(instance) }
                            ) {
                                val titleKey = eventTitleMorphKey(instance, d)
                                Text(
                                    ev.title.ifEmpty { stringResource(R.string.no_title) },
                                    Modifier
                                        .padding(6.dp)
                                        .then(if (titleKey == null) Modifier else Modifier.sharedText(titleKey)),
                                    contentColorOn(Color(ev.color)),
                                    maxLines = 2,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hour gutter. It lives inside the grid's scroll container so it stays pinned to the rows
 * it labels even when [scale] gives them different heights.
 */
@Composable
private fun HourLabelColumn(context: android.content.Context, scale: HourScale) {
    Column {
        for (hour in 0..23) {
            Box(
                Modifier
                    .height(scale.heightOf(hour))
                    .width(HourGutterWidth)
                    .clipToBounds()
            ) {
                val hourString = DateString.hourLabel(hour, DateFormat.is24HourFormat(context))
                Text(
                    text = hourString,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
