package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps a WhatsApp or Signal call alive off-screen — its socket, mic capture and process — and shows the
 * call notification. Started by [InAppCallConnectionService] and self-stops on a terminal state.
 *
 * Audio focus and audio mode are not handled here: the self-managed Telecom connection sets
 * `audioModeIsVoip`, so the system owns routing. Doing it in both places would fight.
 */
class InAppCallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> InAppCallRegistry.answer()
            ACTION_HANGUP -> {
                if (InAppCallRegistry.state.value.phase == InAppCallPhase.Incoming) {
                    InAppCallRegistry.reject()
                } else {
                    InAppCallRegistry.hangup()
                }
            }
        }
        startForegroundWithNotification()
        observeJob?.cancel()
        observeJob = scope.launch {
            InAppCallRegistry.state.collect { state ->
                when (state.phase) {
                    InAppCallPhase.Idle, InAppCallPhase.Ended -> stopSelfSafely()
                    else -> startForegroundWithNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        ensureChannels()
        val state = InAppCallRegistry.state.value
        val notification = if (state.phase == InAppCallPhase.Incoming) {
            buildIncomingCallNotification(state.peerName)
        } else {
            buildOngoingCallNotification(state.phase, state.peerName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannels() {
        ensureNotificationChannel(CHANNEL_ID, getString(R.string.inapp_call_channel_name))
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = getString(R.string.inapp_incoming_call_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.inapp_incoming_call_channel_desc),
        ) {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
    }

    private fun buildIncomingCallNotification(peerName: String): Notification =
        Notification.Builder(this, INCOMING_CHANNEL_ID)
            .setContentTitle(getString(R.string.call_state_incoming_generic))
            .setContentText(peerName)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(callActivityIntent(0))
            .setFullScreenIntent(callActivityIntent(1), true)
            .addAction(
                android.R.drawable.sym_call_incoming,
                getString(R.string.call_answer),
                serviceActionIntent(ACTION_ANSWER, 2),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.call_decline),
                serviceActionIntent(ACTION_HANGUP, 3),
            )
            .build()

    private fun buildOngoingCallNotification(phase: InAppCallPhase, peerName: String): Notification {
        val title = when (phase) {
            InAppCallPhase.Outgoing -> getString(R.string.call_state_dialing)
            InAppCallPhase.Connecting -> getString(R.string.call_state_connecting)
            else -> getString(R.string.inapp_ongoing_call)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(peerName)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(callActivityIntent(0))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.call_end),
                serviceActionIntent(ACTION_HANGUP, 3),
            )
            .build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, InAppCallForegroundService::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun callActivityIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "inapp_calls"
        private const val INCOMING_CHANNEL_ID = "inapp_calls_incoming"
        private const val NOTIFICATION_ID = 4712

        private const val ACTION_ANSWER = "com.vayunmathur.communicate.inappcall.ANSWER_CALL"
        private const val ACTION_HANGUP = "com.vayunmathur.communicate.inappcall.HANGUP_CALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, InAppCallForegroundService::class.java),
            )
        }
    }
}
