package com.vayunmathur.safefamily.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.vayunmathur.safefamily.data.BedtimeSchedule
import com.vayunmathur.safefamily.receiver.BedtimeReceiver
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "SafeFamilyBedtime"

/**
 * Arms the next bedtime boundary.
 *
 * One alarm at a time, always for the next transition in either direction, re-armed each time it
 * fires. A pair of repeating daily alarms would be simpler and wrong: the window can be edited,
 * disabled, or apply to only some days, and a repeating alarm outlives all three.
 *
 * Exact alarms, because the whole feature is a promise about a specific minute. That needs
 * `SCHEDULE_EXACT_ALARM`, and on a device where it has been revoked [arm] degrades to an inexact
 * alarm rather than throwing - late enforcement beats none.
 */
class BedtimeScheduler(private val context: Context) {

    private val alarms = context.getSystemService<AlarmManager>()

    fun arm(schedule: BedtimeSchedule) {
        val alarms = alarms ?: return
        val pending = pendingIntent()
        alarms.cancel(pending)
        if (!schedule.enabled) return

        val next = nextBoundary(schedule) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = alarms.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                Log.w(TAG, "no exact-alarm permission; bedtime will be approximate")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.w(TAG, "could not arm the bedtime alarm", it) }
    }

    /**
     * The next minute at which the window's state changes, searched forward a week.
     *
     * Evaluating [BedtimeSchedule.contains] minute by minute rather than deriving the boundary
     * arithmetically is deliberate: the day mask, the midnight wrap and the "evening belongs to
     * today, morning to yesterday" rule interact, and one predicate that the enforcer also uses
     * cannot disagree with itself. A week of minutes is 10,080 cheap comparisons, run at most
     * twice a day.
     */
    private fun nextBoundary(schedule: BedtimeSchedule): LocalDateTime? {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        var state = schedule.activeAt(now)
        for (step in 1..MINUTES_IN_WEEK) {
            val at = now.plusMinutes(step.toLong())
            val next = schedule.activeAt(at)
            if (next != state) return at
            state = next
        }
        return null
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        BedtimeReceiver.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 1
        const val MINUTES_IN_WEEK = 7 * 24 * 60
    }
}

/** Whether the window is open at [at], in the device's current timezone. */
fun BedtimeSchedule.activeAt(at: LocalDateTime): Boolean =
    contains(at.dayOfWeek.value - 1, at.hour * 60 + at.minute)
