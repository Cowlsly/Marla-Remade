package com.vayunmathur.share.platform.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.share.MainActivity
import com.vayunmathur.share.R

private const val CHANNEL_ID = "share_transfer"
private const val NOTIF_ID = 4101

/**
 * Foreground service that keeps Nearby Share transfers alive while the app
 * is backgrounded.
 *
 * Pattern follows the per-repo foreground-service convention (see
 * library:downloadservice / maps RouteService): a dataSync service that
 * posts a low-importance notification while a session pump is active, and
 * tears itself down when transfers complete or the user dismisses.
 *
 * The ViewModel owns the actual TCP + Rust pumps; this service exists just
 * to satisfy the platform's background-execution contract. No session state
 * lives here.
 */
class ShareTransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.share_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.share_notification_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECEIVE, ACTION_START_SEND -> {
                val notif = buildNotification(intent.action == ACTION_START_SEND)
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        NOTIF_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIF_ID, notif)
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Service restarted by system; re-enter foreground with a generic notification.
                val notif = buildNotification(isSending = false)
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIF_ID, notif)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(isSending: Boolean): Notification {
        val title = getString(
            if (isSending) R.string.share_notification_title_sending
            else R.string.share_notification_title_receiving
        )
        val text = getString(R.string.share_notification_text)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.share_notification_action_stop),
                stopIntent,
            )
            .build()
    }

    companion object {
        const val ACTION_START_RECEIVE = "com.vayunmathur.share.action.START_RECEIVE"
        const val ACTION_START_SEND = "com.vayunmathur.share.action.START_SEND"
        const val ACTION_STOP = "com.vayunmathur.share.action.STOP"

        fun startReceiveMode(context: Context, port: Int) {
            val intent = Intent(context, ShareTransferService::class.java).apply {
                action = ACTION_START_RECEIVE
                putExtra(EXTRA_PORT, port)
            }
            start(context, intent)
        }

        fun startSendMode(context: Context, host: String, port: Int) {
            val intent = Intent(context, ShareTransferService::class.java).apply {
                action = ACTION_START_SEND
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            start(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShareTransferService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        private const val EXTRA_PORT = "port"
        private const val EXTRA_HOST = "host"

        private fun start(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }
    }
}
