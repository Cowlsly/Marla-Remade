package com.vayunmathur.health.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.domain.DoseSchedule
import kotlin.time.Clock

/**
 * Arms and cancels the next dose alarm for a schedule.
 *
 * Only ever one alarm per schedule, holding the *next* dose and re-armed each time it fires — the
 * clock app's arrangement, which keeps the number of live alarms proportional to the number of
 * schedules rather than the number of doses.
 *
 * Uses `setAndAllowWhileIdle` rather than the clock's `setAlarmClock`. Precision is not needed here,
 * and that choice buys a lot: no `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` permission, no
 * "Alarms & reminders" screen for the user to find, and no alarm icon permanently in the status
 * bar. It still fires in Doze, which a plain `setWindow` would not; Doze rate-limits these to
 * roughly one every nine minutes, which is irrelevant at this cadence.
 */
object DoseScheduler {

    const val EXTRA_SCHEDULE_ID = "SCHEDULE_ID"
    const val EXTRA_MEDICATION_ID = "MEDICATION_ID"

    private const val TAG = "DoseScheduler"

    fun arm(context: Context, schedule: MedicationSchedule) {
        val next = DoseSchedule.nextDose(schedule, Clock.System.now())
        if (next == null) {
            cancel(context, schedule.id)
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toEpochMilliseconds(),
            pendingIntent(context, schedule.id, schedule.medicationId, PendingIntent.FLAG_UPDATE_CURRENT),
        )
        Log.i(TAG, "Schedule ${schedule.id}: next dose at $next")
    }

    /** Arms an alarm [delayMillis] from now for a snoozed dose, leaving the schedule alone. */
    fun armSnooze(context: Context, scheduleId: String, medicationId: String, delayMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMillis,
            pendingIntent(context, scheduleId, medicationId, PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    fun cancel(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE so a schedule that was never armed does not get an alarm conjured for it
        // just to cancel it.
        val existing = PendingIntent.getBroadcast(
            context,
            requestCode(scheduleId),
            Intent(context, DoseReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        existing?.let { alarmManager.cancel(it) }
    }

    private fun pendingIntent(
        context: Context,
        scheduleId: String,
        medicationId: String,
        flags: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(scheduleId),
        Intent(context, DoseReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_MEDICATION_ID, medicationId)
        },
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(scheduleId: String): Int = scheduleId.hashCode()
}
