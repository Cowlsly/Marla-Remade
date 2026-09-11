package com.vayunmathur.health.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.health.R
import com.vayunmathur.health.platform.DoseActionReceiver
import com.vayunmathur.health.platform.DoseReminderActivity
import com.vayunmathur.health.platform.DoseScheduler

/**
 * Posts the "time to take this" alert.
 *
 * The notification is what actually rings: [Notification.FLAG_INSISTENT] on a channel that owns the
 * alarm sound keeps sounding without any process of ours surviving, and
 * [com.vayunmathur.health.service.DoseSoundService] cancels it once it manages to take over. It is
 * bounded by `setTimeoutAfter` so a reminder nobody is there to dismiss cannot ring indefinitely.
 */
object DoseNotification {

    private const val TAG = "DoseNotification"

    /** How long the reminder may ring unattended before the system drops it. */
    private const val RING_TIMEOUT_MS = 10 * 60 * 1000L

    fun post(context: Context, scheduleId: String, medicationId: String, medicationName: String) {
        createDoseChannels(context)

        val fullScreen = Intent(context, DoseReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DoseScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(DoseScheduler.EXTRA_MEDICATION_ID, medicationId)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            scheduleId.hashCode(),
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, DOSE_REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.dose_due_title))
            .setContentText(medicationName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setFullScreenIntent(fullScreenPending, true)
            // Without a content intent, tapping the notification does nothing and the shade offers
            // no way to stop the ring but a swipe.
            .setContentIntent(fullScreenPending)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .addAction(
                0,
                context.getString(R.string.dose_taken),
                actionIntent(context, DoseActionReceiver.ACTION_TAKEN, scheduleId, medicationId),
            )
            .addAction(
                0,
                context.getString(R.string.dose_snooze),
                actionIntent(context, DoseActionReceiver.ACTION_SNOOZE, scheduleId, medicationId),
            )

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // On Android 14+ this permission is auto-granted only to calling and alarm apps, which a
        // health app is not. Without it the reminder degrades to a heads-up notification instead of
        // taking over the screen — the schedule editor offers the user a way to grant it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !manager.canUseFullScreenIntent()
        ) {
            Log.w(TAG, "USE_FULL_SCREEN_INTENT not granted; the reminder will not take over the screen")
        }

        manager.notify(
            DOSE_REMINDER_NOTIFICATION_ID,
            builder.build().apply { flags = flags or Notification.FLAG_INSISTENT },
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(DOSE_REMINDER_NOTIFICATION_ID)
    }

    private fun actionIntent(
        context: Context,
        action: String,
        scheduleId: String,
        medicationId: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + scheduleId).hashCode(),
        Intent(context, DoseActionReceiver::class.java).apply {
            this.action = action
            putExtra(DoseScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(DoseScheduler.EXTRA_MEDICATION_ID, medicationId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
