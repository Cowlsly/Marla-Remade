package com.vayunmathur.cast.tv.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vayunmathur.cast.tv.MainActivity
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ReceiverService"

/**
 * `IMPORTANCE_LOW`: being available to cast to is a status, not an event, so it must never make a
 * sound - least of all on a TV in a living room.
 */
private const val CHANNEL_ID = "cast_tv_receiver"

private const val NOTIF_ID = 4301

/**
 * Keeps this TV listening while nothing is on screen.
 *
 * A receiver that stops advertising when its Activity goes away is not a receiver: the user opens the
 * app once and then expects the TV to be in the phone's list, including after the launcher has moved
 * on. `mediaPlayback` is the honest foreground type - the service holds two sockets and decodes a
 * stream - and it is not subject to Android 15's cumulative-runtime cap, which covers only `dataSync`
 * and `mediaProcessing`.
 *
 * `START_STICKY`, unlike `:cast`'s service: there is genuinely something to restore here. A restarted
 * receiver just re-advertises and waits, with no consent token or single-use permission involved.
 */
class ReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.tv_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.tv_notification_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, because this is started with startForegroundService and the platform kills a process
        // that does not honour that within a few seconds.
        enterForeground()
        if (intent?.action == ACTION_STOP) {
            ReceiverController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ReceiverController.start(this)
        if (!collecting) {
            collecting = true
            scope.launch {
                ReceiverController.state.collect { state ->
                    getSystemService<NotificationManager>()
                        ?.notify(NOTIF_ID, buildNotification(statusText(state.phase)))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ReceiverController.stop()
        scope.cancel()
    }

    /**
     * Android 15's cumulative-runtime timeout.
     *
     * Checked rather than assumed: the cap applies to `dataSync` and `mediaProcessing` only, so this
     * should never fire. Kept because if a future platform version does cap `mediaPlayback`, closing
     * the sockets cleanly beats being killed with a phone mid-stream.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out (type $fgsType)")
        ReceiverController.stop()
        stopSelf()
    }

    private fun enterForeground() {
        val notification = buildNotification(getString(R.string.tv_notification_text_idle))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // A background start the platform refuses. The receiver still works; it just will not
            // outlive the Activity.
            Log.w(TAG, "could not enter the foreground", e)
        }
    }

    private fun statusText(phase: ReceiverPhase): String = when (phase) {
        is ReceiverPhase.Pairing -> getString(R.string.tv_notification_text_pairing)
        is ReceiverPhase.Mirroring ->
            getString(R.string.tv_notification_text_mirroring, phase.sourceName)
        else -> getString(R.string.tv_notification_text_idle)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_tv)
            .setContentTitle(getString(R.string.tv_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.vayunmathur.cast.tv.action.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
            } catch (_: Exception) {
                // Refused because the app is in the background with no exemption. The Activity's own
                // session still works; it just will not outlive it.
            }
        }
    }
}
