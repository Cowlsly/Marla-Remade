package com.vayunmathur.clock.platform
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.vayunmathur.clock.service.AlarmSoundService
import com.vayunmathur.clock.R

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        createNotificationChannels(context)
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)
        Log.i(TAG, "Alarm $alarmId fired (snooze=${intent.getBooleanExtra("IS_SNOOZE", false)})")

        // 1. Create the Intent for your "Ringing" Activity
        val ringIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_ID", alarmId)
        }

        // 2. Wrap it in a PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Build the Notification. This is what actually rings: FLAG_INSISTENT below repeats
        // the ring channel's sound until something cancels it, so the alarm sounds even when
        // AlarmSoundService never reaches startForeground.
        val builder = NotificationCompat.Builder(context, ALARM_RING_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(context.getString(R.string.label_alarm))
            .setContentText(context.getString(R.string.alarm_notification_wake_up))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // This is the key: it launches the activity automatically if the phone is locked
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            // Backstop: an insistent notification rings forever, so bound it in case every
            // dismiss path is missed (process killed before the UI ever appeared, say).
            .setTimeoutAfter(RING_TIMEOUT_MS)

        val repository = ClockRepository.get(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val alarm = repository.getAlarm(alarmId)
                if (alarm.days == 0) {
                    repository.upsertAlarm(alarm.copy(enabled = false))
                } else {
                    AlarmScheduler.schedule(context, alarm)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alarm $alarmId: could not reschedule", e)
            } finally {
                pendingResult.finish()
            }
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !notificationManager.canUseFullScreenIntent()
        ) {
            // Without it the ringing screen can only appear if the background activity start
            // below happens to be allowed, which off-screen it is not.
            Log.w(TAG, "Alarm $alarmId: USE_FULL_SCREEN_INTENT not granted; ringing UI may not appear")
        }
        notificationManager.notify(
            alarmId.toInt(),
            builder.build().apply { flags = flags or Notification.FLAG_INSISTENT },
        )

        // 4. Start the Sound Service immediately so we hear it even if Activity doesn't launch
        val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
            putExtra("ALARM_ID", alarmId)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // Losing this used to throw out of onReceive, which took the notification's
            // rescheduling and the activity start below down with it.
            Log.e(TAG, "Alarm $alarmId: could not start AlarmSoundService", e)
        }

        // 5. Try to start the activity explicitly (useful if screen is already on)
        try {
            context.startActivity(ringIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm $alarmId: background activity start refused; relying on full-screen intent", e)
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"

        /** How long the insistent notification may ring unattended before the system drops it. */
        const val RING_TIMEOUT_MS = 10 * 60 * 1000L
    }
}