package com.vayunmathur.safefamily.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * What supervision does to one app.
 *
 * A row exists only for apps the parent has actually chosen; absence means unsupervised, which
 * is why there is no "unrestricted" state here. Both restrictions live on the same row because
 * they are two answers to one question - when may this app be used - and keeping them together
 * is what lets the enforcer resolve them without a join.
 *
 * Order of precedence when both apply is decided in the enforcer, not here: bedtime wins,
 * because a limit that has not been reached should not unblock an app during the night.
 */
@Entity
data class AppRule(
    @PrimaryKey val packageName: String,
    /**
     * Minutes of use permitted per day, or null for no limit.
     *
     * Capped at 24 h by the platform - `PackageUsagePolicy.Builder.performBuild` throws above
     * that, and on a negative value.
     */
    val dailyLimitMinutes: Int? = null,
    /** Whether this app is blocked during the bedtime window. */
    val blockedAtBedtime: Boolean = false,
)

/**
 * The nightly window during which [AppRule.blockedAtBedtime] apps are unavailable.
 *
 * Stored as minutes past local midnight rather than a timestamp so it survives timezone changes
 * and DST the way a user expects: "9pm" means 9pm wherever the device is.
 *
 * [startMinute] greater than [endMinute] is the normal case, not an error - a window that
 * crosses midnight. [contains] is the only place that asymmetry is interpreted.
 */
@Entity
data class BedtimeSchedule(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    /** Minutes past local midnight at which the window opens. */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** Minutes past local midnight at which it closes. */
    val endMinute: Int = DEFAULT_END_MINUTE,
    /** Bitmask of days the window applies to, bit 0 = Monday. */
    val daysMask: Int = ALL_DAYS,
) {
    /** Whether [minuteOfDay] on [dayIndex] (0 = Monday) falls inside the window. */
    fun contains(dayIndex: Int, minuteOfDay: Int): Boolean {
        if (!enabled) return false
        return if (startMinute <= endMinute) {
            // Same-day window.
            isDaySet(dayIndex) && minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            // Crosses midnight. The evening half belongs to `dayIndex`; the morning half belongs
            // to the *previous* day's window, so that "Fri 21:00-07:00" still applies at 02:00
            // on Saturday.
            (isDaySet(dayIndex) && minuteOfDay >= startMinute) ||
                (isDaySet((dayIndex + 6) % DAYS_IN_WEEK) && minuteOfDay < endMinute)
        }
    }

    fun isDaySet(dayIndex: Int): Boolean = (daysMask shr dayIndex) and 1 == 1

    companion object {
        /** There is one schedule. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0
        const val DAYS_IN_WEEK = 7
        const val ALL_DAYS = 0b111_1111
        const val MINUTES_PER_DAY = 24 * 60

        /** 21:00. */
        const val DEFAULT_START_MINUTE = 21 * 60

        /** 07:00. */
        const val DEFAULT_END_MINUTE = 7 * 60
    }
}
