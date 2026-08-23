package com.vayunmathur.maps.data

import com.vayunmathur.library.util.localizedAmPmMarker
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
class OpeningHours(val rawString: String) {

    private val rules: List<OpeningRule> = parse(rawString)

    /**
     * Whether anything at all was understood.
     *
     * Worth asking before showing a schedule: with no rules every day reads
     * "Closed", which for an unparseable string is a confident lie. Callers should
     * fall back to whatever else they have instead.
     */
    val hasRules: Boolean get() = rules.isNotEmpty()

    companion object {
        fun from(input: String): OpeningHours = OpeningHours(input)

        /** OSM spells an all-day interval `00:00-24:00`, which normalises to this. */
        private val ALL_DAY = TimeInterval(LocalTime(0, 0), LocalTime(0, 0))

        private fun parse(input: String): List<OpeningRule> {
            // Wrap each rule parse in runCatching so a single malformed segment
            // doesn't crash the bottom sheet. Real OSM `opening_hours` strings
            // include things like "Mo-Fr sunrise-sunset", "Jan-Mar 09:00-17:00" or
            // localised abbreviations we don't handle yet — silently drop those
            // rather than throwing NumberFormatException up the UI stack.
            return input.split(";").mapNotNull { part ->
                runCatching { parseRule(part) }.getOrNull()
            }
        }

        private fun parseRule(part: String): OpeningRule? {
            // A trailing comment (`Mo-Fr 08:00-18:00 "by appointment"`) documents the
            // schedule rather than being part of it.
            val spec = part.substringBefore('"').trim()
            if (spec.isEmpty()) return null

            val tokens = spec.split(' ').filter { it.isNotEmpty() }
            // Split day selector from time selector at the first time-looking token.
            // Anchoring on the LAST space instead mis-sliced every rule whose time
            // list contains one — `Mo-Fr 08:00-12:00, 13:00-18:00` handed the day
            // parser `Mo-Fr 08:00-12:00,` and lost the rule entirely.
            val firstTime = tokens.indexOfFirst { isTimeToken(it) }
            val dayTokens = if (firstTime == -1) tokens else tokens.subList(0, firstTime)
            val timeTokens = if (firstTime == -1) emptyList() else tokens.subList(firstTime, tokens.size)

            // No day selector at all ("24/7", a bare "off") applies to every day.
            val days =
                if (dayTokens.isEmpty()) DayOfWeek.entries.toSet()
                else parseDays(dayTokens.joinToString(" "))
            // A selector we cannot represent — `PH`, `Jan-Mar`, `week 1-53` — has to
            // drop its rule. Public holidays in particular would otherwise need a
            // holiday calendar we do not have, and guessing is worse than omitting.
            if (days.isEmpty()) return null

            // Joined without a separator so `08:00-12:00, 13:00-18:00` rejoins into
            // the comma-separated list `parseIntervals` expects.
            // A null means the time selector was there but said nothing we could use
            // (`Mo-Fr 08:00`, a bare `Mo-Fr`). Keeping such a rule would read as
            // "closed those days", inventing a fact from a typo.
            val intervals = parseIntervals(timeTokens.joinToString("")) ?: return null
            return OpeningRule(days, intervals)
        }

        /** A time selector rather than a day one: a clock time, `24/7`, or a closure. */
        private fun isTimeToken(token: String): Boolean {
            val t = token.trimEnd(',')
            return t.firstOrNull()?.isDigit() == true ||
                t.equals("off", ignoreCase = true) ||
                t.equals("closed", ignoreCase = true)
        }

        private fun parseDays(dayStr: String): Set<DayOfWeek> {
            val days = mutableSetOf<DayOfWeek>()
            val map = mapOf(
                "Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY,
                "We" to DayOfWeek.WEDNESDAY, "Th" to DayOfWeek.THURSDAY,
                "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY,
                "Su" to DayOfWeek.SUNDAY
            )

            if (dayStr == "Mo-Su" || dayStr.isBlank()) return DayOfWeek.entries.toSet()

            dayStr.split(",").forEach { segment ->
                val cleanSegment = segment.trim()
                if (cleanSegment.contains("-")) {
                    val parts = cleanSegment.split("-")
                    if (parts.size < 2) return@forEach
                    val start = map[parts[0]] ?: return@forEach
                    val end = map[parts[1]] ?: return@forEach
                    var curr = start
                    while (curr != end) {
                        days.add(curr)
                        curr = DayOfWeek.entries[(curr.ordinal + 1) % 7]
                    }
                    days.add(end)
                } else {
                    map[cleanSegment]?.let { days.add(it) }
                }
            }
            return days
        }

        /**
         * The intervals a time selector names: empty for an explicit closure, and
         * null when nothing could be read at all — a distinction the caller needs,
         * since "closed" and "unintelligible" must not render the same way.
         */
        private fun parseIntervals(timeStr: String): List<TimeInterval>? {
            val spec = timeStr.trim().trimEnd(',')
            if (spec.isEmpty()) return null
            if (spec.equals("off", ignoreCase = true) || spec.equals("closed", ignoreCase = true)) {
                return emptyList()
            }
            // `24/7` is its own token in the grammar, not a day/time pair. Falling
            // through would leave it with no intervals, which renders as permanently
            // shut — a 24-hour pharmacy shipped as closed.
            if (spec == "24/7") return listOf(ALL_DAY)
            return spec.split(",").mapNotNull {
                val range = it.split("-")
                if (range.size != 2) return@mapNotNull null
                val start = parseCustomTime(range[0]) ?: return@mapNotNull null
                val end = parseCustomTime(range[1]) ?: return@mapNotNull null
                TimeInterval(start, end)
            }.ifEmpty { null }
        }

        // Returns null for unparseable strings instead of throwing — OSM has
        // entries like "sunrise", "12:00+", "dusk" that we can't represent.
        private fun parseCustomTime(time: String): LocalTime? {
            val parts = time.trim().split(":")
            if (parts.isEmpty() || parts[0].isBlank()) return null
            return try {
                var hour = parts[0].toInt()
                val minute = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                if (hour >= 24) hour -= 24
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null
                LocalTime(hour, minute)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    fun isOpen(dateTime: LocalDateTime): Boolean {
        val rule = rules.findLast { it.days.contains(dateTime.dayOfWeek) } ?: return false
        return rule.intervals.any { it.contains(dateTime.time) }
    }

    /**
     * Finds the next status change by checking the boundaries of intervals
     * across the next 7 days.
     */
    fun nextStatusChangeTime(current: LocalDateTime): LocalDateTime {
        val currentlyOpen = isOpen(current)
        val timeZone = TimeZone.currentSystemDefault()

        // Check today and the next 6 days
        for (i in 0..7) {
            val date = current.toInstant(timeZone)
                .plus(i, DateTimeUnit.DAY, timeZone)
                .toLocalDateTime(timeZone)
                .date

            val dayOfWeek = date.dayOfWeek
            val rule = rules.findLast { it.days.contains(dayOfWeek) } ?: continue

            // Collect all relevant times for this day
            val changeTimes = rule.intervals.flatMap { listOf(it.start, it.end) }.distinct().sorted()

            for (time in changeTimes) {
                val candidate = LocalDateTime(date, time)
                if (candidate > current && isOpen(candidate) != currentlyOpen) {
                    return candidate
                }
            }
        }
        return current
    }

    fun openingHours(): Map<DayOfWeek, String> {
        return DayOfWeek.entries.associateWith { day ->
            val rule = rules.findLast { it.days.contains(day) }
            val intervals = rule?.intervals
            when {
                intervals.isNullOrEmpty() -> "Closed"
                // Only when the whole day is one open interval. Testing `any` instead
                // would relabel a multi-interval rule and throw its other intervals
                // away.
                intervals.size == 1 && intervals[0].isAllDay -> "Open 24 hours"
                else -> intervals.joinToString(", ") {
                    "${it.start.format(timeFormat)}-${it.end.format(timeFormat)}"
                }
            }
        }
    }
}

val timeFormat: DateTimeFormat<LocalTime> get() = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(":")
    minute()
    chars(" ")
    localizedAmPmMarker()
}

@Serializable
private data class OpeningRule(val days: Set<DayOfWeek>, val intervals: List<TimeInterval>)

@Serializable
private data class TimeInterval(val start: LocalTime, val end: LocalTime) {
    /**
     * OSM writes an all-day interval as `00:00-24:00`, and 24:00 normalises to 00:00
     * — so the range comes out zero-width. Reading that literally is what made an
     * all-day interval render as permanently Closed.
     */
    val isAllDay: Boolean get() = start == end

    fun contains(time: LocalTime): Boolean {
        return when {
            isAllDay -> true
            end < start -> time !in end..<start
            else -> time in start..<end
        }
    }
}