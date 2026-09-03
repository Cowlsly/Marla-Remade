package com.vayunmathur.calendar.glance

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDefaults.defaultTextStyle
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.calendar.MainActivity
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.ui.atEndOfDayIn
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localeFirstDayOfWeek
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.widgets.DynamicThemeGlance
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class CalendarMonthGlanceWidget : GlanceAppWidget() {
    /**
     * Exact size mode so [LocalSize] reports the widget's real dimensions and each day
     * cell can show as many titled chips as fit (see [maxChipsForSize]).
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date

        val locale = context.resources.configuration.locales[0]
        val firstDayOfWeek = localeFirstDayOfWeek(locale)
        val weeks = computeMonthGrid(today.year, today.month, firstDayOfWeek)
        val startDay = weeks.first().first()
        val endDay = weeks.last().last()

        val instances = Instance.getInstances(
            context,
            startDay.atStartOfDayIn(zone),
            endDay.atEndOfDayIn(zone),
        )
        val eventsByDay = weeks.flatten().associateWith { day ->
            instances.filter { day in it.spanDays }.sortedForMonthCell()
        }

        val monthName = localizedMonthNames(DateNameStyle.SHORT, locale)[today.month.number - 1]
        val monthLabel = context.getString(R.string.month_year_format, monthName, today.year)
        val dayNames = localizedDayOfWeekNames(DateNameStyle.SHORT, locale)
        val weekdayNames = weeks.first().map { day -> dayNames[day.dayOfWeek.isoDayNumber - 1] }

        provideContent {
            DynamicThemeGlance(context) {
                MonthContent(monthLabel, weeks, eventsByDay, today, today.month, weekdayNames)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        try {
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date
            val weeks = computeMonthGrid(today.year, today.month, localeFirstDayOfWeek())
            val monthName = localizedMonthNames(DateNameStyle.SHORT)[today.month.number - 1]
            val monthLabel = context.getString(R.string.month_year_format, monthName, today.year)
            val dayNames = localizedDayOfWeekNames(DateNameStyle.SHORT)
            val weekdayNames = weeks.first().map { day -> dayNames[day.dayOfWeek.isoDayNumber - 1] }
            val previewEvents = previewEventsByDay(context, weeks, zone)
            provideContent {
                DynamicThemeGlance(context) {
                    MonthContent(monthLabel, weeks, previewEvents, today, today.month, weekdayNames)
                }
            }
        } catch (e: Throwable) {
            Log.e("CalendarMonthWidget", "providePreview failed", e)
            try {
                provideContent {
                    DynamicThemeGlance(context) {
                        Box(
                            modifier = GlanceModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Calendar",
                                style = defaultTextStyle.copy(color = GlanceTheme.colors.onBackground),
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // last resort: avoid crashing the preview host
            }
        }
    }
}

/**
 * Static sample events so the widget picker preview shows titled chips (no provider
 * query). One day is deliberately overfull so the preview also shows the "+N more" line.
 */
private fun previewEventsByDay(
    context: Context,
    weeks: List<List<LocalDate>>,
    zone: TimeZone,
): Map<LocalDate, List<Instance>> {
    val titles = listOf(
        context.getString(R.string.widget_preview_event_1_title),
        context.getString(R.string.widget_preview_event_2_title),
        context.getString(R.string.widget_preview_event_3_title),
    )
    val colors = listOf(0xFF4285F4.toInt(), 0xFF34A853.toInt(), 0xFFEA4335.toInt())
    var nextId = 1L
    fun sample(day: LocalDate, index: Int, hour: Int): Instance {
        val begin = LocalDateTime(day, LocalTime(hour, 0)).toInstant(zone).toEpochMilliseconds()
        val id = nextId++
        return Instance(
            id = id,
            eventID = id,
            begin = begin,
            end = begin + 30 * 60 * 1000,
            timezone = zone.id,
            allDay = false,
            eventTitle = titles[index % titles.size],
            color = colors[index % colors.size],
            rrule = null,
        )
    }
    val days = weeks.flatten()
    fun dayOffset(n: Int): LocalDate = days[n % days.size]
    val busy = dayOffset(9)
    val busyEvents = listOf(
        sample(busy, 0, 9),
        sample(busy, 1, 11),
        sample(busy, 2, 15),
        sample(busy, 0, 17),
        sample(busy, 1, 19),
    )
    val first = dayOffset(8)
    val mid = dayOffset(15)
    val later = dayOffset(16)
    val last = dayOffset(22)
    return mapOf(
        first to listOf(sample(first, 0, 9), sample(first, 1, 12)),
        busy to busyEvents,
        mid to listOf(sample(mid, 2, 10)),
        later to listOf(sample(later, 1, 9), sample(later, 2, 14), sample(later, 0, 16)),
        last to listOf(sample(last, 1, 12)),
    )
}

/**
 * Pure-logic month grid: the full weeks covering [year]/[month], each week starting on
 * [firstDayOfWeek] (ISO day number, Sunday = 7). Mirrors the MonthView math in
 * `ui/CalendarScreen.kt` so the widget shows the same days as the app.
 */
internal fun computeMonthGrid(year: Int, month: Month, firstDayOfWeek: Int): List<List<LocalDate>> {
    val firstOfMonth = LocalDate(year, month, 1)
    val lastOfMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))

    val startDay = firstOfMonth.minus(
        DatePeriod(days = (firstOfMonth.dayOfWeek.isoDayNumber - firstDayOfWeek + 7) % 7),
    )
    val lastDayOfWeek = if (firstDayOfWeek == 1) 7 else firstDayOfWeek - 1
    val endDay = lastOfMonth.plus(
        DatePeriod(days = (lastDayOfWeek - lastOfMonth.dayOfWeek.isoDayNumber + 7) % 7),
    )

    val days = generateSequence(startDay) { it.plus(DatePeriod(days = 1)) }
        .takeWhile { it <= endDay }
        .toList()
    return days.chunked(7)
}

/**
 * Day-cell event order, mirroring the in-app month view (`MonthWeekRow` sorts by
 * `startDateTime`): all-day events start at midnight so they naturally come first,
 * then timed events earliest-first. Multi-day events sort by when they start.
 */
internal fun List<Instance>.sortedForMonthCell(): List<Instance> = sortedBy { it.startDateTime }

/** Visible chips plus hidden-event count for a capped day cell. */
internal fun <T> List<T>.cappedForCell(maxChips: Int): Pair<List<T>, Int> =
    if (size <= maxChips) this to 0 else take(maxChips) to (size - maxChips)

/**
 * Black or white, whichever reads on an event chip [background]. Same 0.45 luminance
 * threshold as `contentColorOn` in `:library:ui`, reimplemented here because Glance
 * content cannot depend on Compose Material helpers.
 */
internal fun contrastingTextOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color.Black else Color.White

internal const val MONTH_CHIP_MIN = 1
internal const val MONTH_CHIP_MAX = 4
internal const val MONTH_CHIP_DEFAULT = 3
/** Estimated chrome above the grid: TitleBar plus the weekday header row. */
internal const val MONTH_GRID_CHROME_DP = 80f
/** Day-number line height inside a cell. */
internal const val MONTH_DAY_NUMBER_DP = 20f
/** One titled chip (10sp text plus tight vertical padding). */
internal const val MONTH_CHIP_DP = 18f

/**
 * How many titled chips fit in one day cell for a widget of [height] showing
 * [weekCount] week rows. Heuristic: split the space below the chrome evenly across
 * rows, then fit day number plus chips. Falls back to [MONTH_CHIP_DEFAULT] when the
 * size is unknown (e.g. the picker preview), and clamps to [MONTH_CHIP_MIN]..[MONTH_CHIP_MAX].
 */
internal fun maxChipsForSize(height: Dp, weekCount: Int): Int {
    val h = height.value
    if (!h.isFinite() || h <= 0f || weekCount <= 0) return MONTH_CHIP_DEFAULT
    val rowHeight = (h - MONTH_GRID_CHROME_DP) / weekCount
    val chips = ((rowHeight - MONTH_DAY_NUMBER_DP) / MONTH_CHIP_DP).toInt()
    return chips.coerceIn(MONTH_CHIP_MIN, MONTH_CHIP_MAX)
}

@SuppressLint("RestrictedApi")
@Composable
fun MonthContent(
    monthLabel: String,
    weeks: List<List<LocalDate>>,
    eventsByDay: Map<LocalDate, List<Instance>>,
    today: LocalDate,
    viewingMonth: Month,
    weekdayNames: List<String>,
) {
    val maxChips = maxChipsForSize(LocalSize.current.height, weeks.size)
    Scaffold(
        titleBar = {
            TitleBar(
                ImageProvider(R.drawable.calendar_today_24px),
                monthLabel,
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        },
        horizontalPadding = 0.dp,
    ) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
            Row(GlanceModifier.fillMaxWidth()) {
                weekdayNames.forEach { name ->
                    Box(
                        modifier = GlanceModifier.defaultWeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            weeks.forEach { week ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    week.forEach { day ->
                        MonthDayCell(day, viewingMonth, today, eventsByDay[day].orEmpty(), maxChips)
                    }
                }
            }
        }
    }
}

/**
 * One day in the month grid: the day number on top, then a tight vertical stack of
 * titled event chips (at most [maxChips]), then a "+N more" line when events are hidden.
 * Chips cannot overflow the cell: titles are single-line, each week row's height is
 * fixed by weight, and anything beyond the cap collapses into the "+N more" line.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun RowScope.MonthDayCell(
    day: LocalDate,
    viewingMonth: Month,
    today: LocalDate,
    instances: List<Instance>,
    maxChips: Int,
) {
    val context = LocalContext.current
    val isToday = day == today
    val inMonth = day.month == viewingMonth
    val (visible, hidden) = instances.cappedForCell(maxChips)
    val noTitle = context.getString(R.string.no_title)
    Box(
        modifier = GlanceModifier.defaultWeight()
            .background(if (isToday) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surface)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                day.day.toString(),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = if (isToday || inMonth) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        isToday -> GlanceTheme.colors.onPrimaryContainer
                        inMonth -> GlanceTheme.colors.onSurface
                        else -> GlanceTheme.colors.onSurfaceVariant
                    },
                ),
            )
            visible.forEach { instance ->
                MonthEventChip(instance.eventTitle.ifEmpty { noTitle }, instance.color)
            }
            if (hidden > 0) {
                Text(
                    context.resources.getQuantityString(R.plurals.month_widget_more_events, hidden, hidden),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A titled event chip styled after the in-app `SummaryEventItem`: event color
 * background with a contrasting single-line title.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun MonthEventChip(title: String, color: Int) {
    val background = Color(color)
    Box(
        modifier = GlanceModifier.fillMaxWidth()
            .padding(top = 2.dp)
            .background(ColorProvider(background))
            .cornerRadius(4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = TextStyle(
                fontSize = 10.sp,
                color = ColorProvider(contrastingTextOn(background)),
            ),
            maxLines = 1,
        )
    }
}
