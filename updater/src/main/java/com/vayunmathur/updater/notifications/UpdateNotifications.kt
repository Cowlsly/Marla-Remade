package com.vayunmathur.updater.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.updater.MainActivity
import com.vayunmathur.updater.R

private const val TAG = "UpdateNotifications"

/**
 * The one notification the updater posts while it is working, plus the two terminal ones.
 *
 * Progress is a single notification that is edited in place rather than a new one per state, so
 * downloading, verifying and installing read as one operation moving forward instead of three
 * separate events. It is also the foreground-service notification, which means it must exist
 * before the service can legally start.
 */
object UpdateNotifications {

    const val CHANNEL_PROGRESS = "updater_progress"
    private const val CHANNEL_RESULT = "updater_result"

    /** Reused for every progress update so the notification is replaced, not stacked. */
    const val ID_PROGRESS = 1
    private const val ID_RESULT = 2

    fun ensureChannels(context: Context) {
        context.ensureNotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.channel_progress),
            // LOW: an update running in the background is worth showing, not worth a sound.
            importance = NotificationManager.IMPORTANCE_LOW,
            description = context.getString(R.string.channel_progress_desc),
        )
        context.ensureNotificationChannel(
            CHANNEL_RESULT,
            context.getString(R.string.channel_result),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = context.getString(R.string.channel_result_desc),
        )
    }

    /**
     * The in-progress notification.
     *
     * [percent] below zero renders as indeterminate, which is what a stage with no measurable
     * length should look like rather than a bar frozen at zero.
     */
    fun progress(context: Context, title: String, percent: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setOngoing(true)
            // Without this every progress edit re-alerts, several times a second.
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateProgress(context: Context, title: String, percent: Int) {
        post(context, ID_PROGRESS, progress(context, title, percent))
    }

    /**
     * The update is on the inactive slot and only a reboot is left.
     *
     * Not ongoing and not silent: this is the one moment the user has something to decide, and
     * the reboot otherwise waits for the device to be idle for half an hour.
     */
    fun rebootPending(context: Context, build: String, restart: PendingIntent) {
        post(
            context,
            ID_RESULT,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setContentTitle(context.getString(R.string.notify_reboot_title))
                .setContentText(context.getString(R.string.notify_reboot_text, build))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(false)
                .setContentIntent(openApp(context))
                .addAction(0, context.getString(R.string.notify_restart_now), restart)
                .build(),
        )
    }

    /**
     * A newer build exists but nothing is downloading it.
     *
     * Posted when [com.vayunmathur.updater.domain.AutoInstallPolicy] holds — auto-install is off,
     * or the only connection is metered. Holding silently would look identical to being up to
     * date, so the user would never learn that the metered toggle is the thing standing in the
     * way.
     */
    fun updateAvailable(context: Context, build: String) {
        post(
            context,
            ID_RESULT,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setContentTitle(context.getString(R.string.notify_available_title))
                .setContentText(context.getString(R.string.notify_available_text, build))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    /** [reason] is shown verbatim; see the same decision in the settings screen. */
    fun failed(context: Context, reason: String) {
        post(
            context,
            ID_RESULT,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setContentTitle(context.getString(R.string.notify_failed_title))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun clearResult(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RESULT)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was refused. The update itself is unaffected.
            Log.w(TAG, "cannot post notification $id", e)
        }
    }
}
