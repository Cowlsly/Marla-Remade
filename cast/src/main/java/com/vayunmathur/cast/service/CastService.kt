package com.vayunmathur.cast.service

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
import com.vayunmathur.cast.MainActivity
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastPhase
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "CastService"

/**
 * `IMPORTANCE_LOW`: an open cast session is a status indicator, not an event, so it must never
 * make a sound. There is nothing else on this channel for a user who silences it to lose.
 */
private const val CHANNEL_ID = "cast_session"

private const val NOTIF_ID = 4201

/**
 * Keeps the cast session alive while the app is not in front.
 *
 * A lifecycle host with no session state of its own: [CastController] owns the socket and the
 * session, so this can be started and stopped freely. `START_NOT_STICKY` rather than sticky,
 * because a restarted service has nothing to restore - the TLS channel died with the process and
 * the receiver has already dropped it.
 *
 * The foreground type is `mediaPlayback`, which is what Android's own documentation lists for
 * casting. `dataSync` is unusable: Android 15 caps it at six cumulative hours per 24 h and then
 * calls `onTimeout()`.
 */
class CastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Guards against a second onStartCommand registering the status collector twice. */
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.cast_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.cast_notification_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything else: this is started with startForegroundService, and the platform
        // kills a process that does not honour that within a few seconds.
        enterForeground()
        if (intent?.action == ACTION_STOP) {
            CastController.disconnect(this)
            return START_NOT_STICKY
        }
        if (!collecting) {
            collecting = true
            // One collector for the lifetime of the service, so the notification tracks what is
            // playing instead of freezing on whatever was true when it started.
            scope.launch {
                CastController.device
                    .combine(CastController.sessionState) { device, state -> device to state }
                    .collect { (device, state) ->
                        if (device == null) return@collect
                        val text = when (state.phase) {
                            CastPhase.Launching ->
                                getString(R.string.cast_notification_text_connecting)
                            CastPhase.Ready -> getString(R.string.cast_notification_text_ready)
                            // Not the raw LAUNCH_ERROR reason: those are wire constants, and the
                            // screen is where the explanation belongs.
                            CastPhase.Failed -> getString(R.string.cast_notification_text_failed)
                            // The receiver app exited on its own - the channel is still up, so
                            // this is a real resting state rather than a transient one.
                            CastPhase.Idle -> getString(R.string.cast_notification_text_idle)
                        }
                        getSystemService<NotificationManager>()
                            ?.notify(NOTIF_ID, buildNotification(device.friendlyName, text))
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Android 15's cumulative-runtime timeout. `mediaPlayback` is not capped, so this should
     * never fire; if a future platform version caps it anyway, tearing the session down cleanly
     * beats being killed with a socket open.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out (type $fgsType)")
        CastController.disconnect(this)
    }

    private fun enterForeground() {
        val device = CastController.device.value
        val notification = buildNotification(
            device?.friendlyName ?: getString(R.string.app_name),
            getString(R.string.cast_notification_text_connecting),
        )
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
            // A background start the platform refuses. Nothing to recover: the session itself is
            // unaffected, it just will not survive the app being backgrounded.
            Log.w(TAG, "could not enter the foreground", e)
        }
    }

    private fun buildNotification(deviceName: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.cast_notification_title, deviceName))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_cast, getString(R.string.cast_notification_action_stop), stop)
            .build()
    }

    companion object {
        /** Ends the session from the notification's own action. */
        const val ACTION_STOP = "com.vayunmathur.cast.action.STOP"

        fun start(context: Context) {
            launch(context, Intent(context, CastService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastService::class.java))
        }

        private fun launch(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Refused because the app is in the background with no exemption. The session
                // still works; it just will not outlive the Activity.
            }
        }
    }
}
