package com.vayunmathur.email.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Wakes snoozed messages whose time has arrived: clears their `snoozedUntil`
 * and marks them unread so they resurface at the top of the inbox. Scheduled
 * once per snooze via [scheduleNext]; each run clears all currently-due rows.
 */
class SnoozeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = EmailRepository.get(applicationContext).getDatabase().emailDao()
        dao.wakeDueSnoozed(System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        fun scheduleNext(context: Context, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
            val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
