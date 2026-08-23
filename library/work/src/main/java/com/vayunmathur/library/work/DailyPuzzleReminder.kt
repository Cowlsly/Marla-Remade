package com.vayunmathur.library.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.R
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * An opt-in daily nudge for a game's daily puzzle, shared by every game that has one.
 *
 * Keyed off the same `keyPrefix` the game gives [DailyChallengeStore], so the worker can read
 * the game's own streak bookkeeping and stay quiet once today's puzzle is done.
 *
 * WorkManager owns the repeat: `PeriodicWorkRequest` reschedules itself, so there is no
 * receiver, no boot receiver and no `AlarmManager` here. Note this is deliberately not
 * [startRepeatedTask], which hardcodes a network constraint and fires immediately — neither
 * suits a nudge that must work offline and only at the chosen time.
 */
object DailyPuzzleReminder {

    const val CHANNEL_ID = "daily_puzzle_reminder"

    /** Default reminder time, 20:00, as minutes since midnight. */
    const val DEFAULT_MINUTES_OF_DAY = 20 * 60L

    private const val KEY_PREFIX = "key_prefix"
    private const val KEY_NOTIFICATION_ID = "notification_id"

    fun update(
        context: Context,
        keyPrefix: String,
        notificationId: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
    ) {
        val workManager = WorkManager.getInstance(context)
        val workName = workName(keyPrefix)
        if (!enabled) {
            workManager.cancelUniqueWork(workName)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailyPuzzleReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(hour, minute), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    KEY_PREFIX to keyPrefix,
                    KEY_NOTIFICATION_ID to notificationId,
                )
            )
            .build()
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    internal fun keyPrefixOf(data: androidx.work.Data): String? = data.getString(KEY_PREFIX)

    internal fun notificationIdOf(data: androidx.work.Data): Int = data.getInt(KEY_NOTIFICATION_ID, 5100)

    private fun workName(keyPrefix: String) = "${keyPrefix}_reminder"

    /** Minutes from now until the next occurrence of [hour]:[minute] (local). */
    private fun initialDelayMinutes(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return ((next.timeInMillis - now.timeInMillis) / 60_000L).coerceAtLeast(1)
    }
}

/**
 * Persisted reminder preferences for one game, so no game hand-rolls the key names.
 *
 * The time is stored as minutes since midnight rather than an hour/minute pair, which keeps it
 * to a single key and a single write.
 */
class DailyReminderSettings(context: Context, keyPrefix: String) {

    private val store = DataStoreUtils.getInstance(context)

    private val enabledKey = "${keyPrefix}_reminder_enabled"
    private val minutesKey = "${keyPrefix}_reminder_minutes"

    /** `DataStoreUtils.booleanFlow(name)` emits nothing until first written; this defaults it. */
    val enabled: Flow<Boolean> = store.booleanFlow(enabledKey, false)

    val minutesOfDay: Flow<Long> =
        store.longFlow(minutesKey, DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY)

    suspend fun setEnabled(enabled: Boolean) = store.setBoolean(enabledKey, enabled)

    suspend fun setMinutesOfDay(minutes: Long) = store.setLong(minutesKey, minutes)
}

/** Posts the nudge, unless today's puzzle is already done. */
class DailyPuzzleReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val keyPrefix = DailyPuzzleReminder.keyPrefixOf(inputData) ?: return Result.success()
        val store = DailyChallengeStore(applicationContext, keyPrefix)
        // Games only record the day once every puzzle in it is solved, so "not today" is
        // exactly "not finished today" — no per-game logic needed here.
        if (store.lastCompletedDay() == store.todayEpochDay()) return Result.success()
        notify(DailyPuzzleReminder.notificationIdOf(inputData))
        return Result.success()
    }

    private fun notify(notificationId: Int) {
        val context = applicationContext
        // Re-checked at post time: the permission can be revoked after the work was scheduled.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        context.ensureNotificationChannel(
            id = DailyPuzzleReminder.CHANNEL_ID,
            name = context.getString(R.string.daily_reminder_channel_name),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = context.getString(R.string.daily_reminder_channel_description),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, DailyPuzzleReminder.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.daily_reminder_title))
            .setContentText(context.getString(R.string.daily_reminder_text, appLabel))
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
