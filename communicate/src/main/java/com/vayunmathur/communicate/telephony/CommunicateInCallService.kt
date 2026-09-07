package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.library.util.ensureNotificationChannel

/** In-call bridge for carrier/SIM calls that lets Communicate expose notification controls. */
class CommunicateInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            currentCall = call
            showOrClearNotification(call)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            currentCall = call
            showOrClearNotification(call)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall?.unregisterCallback(callback)
        currentCall = call
        call.registerCallback(callback)
        showOrClearNotification(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall == call) currentCall = null
        clearNotification()
        super.onCallRemoved(call)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
            ACTION_DECLINE -> currentCall?.reject(false, null)
            ACTION_DISCONNECT -> currentCall?.disconnect()
        }
        currentCall?.let(::showOrClearNotification) ?: clearNotification()
        return START_NOT_STICKY
    }

    private fun showOrClearNotification(call: Call) {
        when (call.state) {
            Call.STATE_RINGING -> showNotification(call, incoming = true)
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE, Call.STATE_HOLDING -> showNotification(call, incoming = false)
            else -> clearNotification()
        }
    }

    private fun showNotification(call: Call, incoming: Boolean) {
        ensureNotificationChannel(
            id = CHANNEL_ID,
            name = getString(R.string.regular_call_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.regular_call_channel_desc),
        ) {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val title = if (incoming) getString(R.string.regular_call_incoming) else getString(R.string.regular_call_ongoing)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(callerLabel(number).ifBlank { getString(R.string.app_name) })
            .setSmallIcon(if (incoming) android.R.drawable.sym_call_incoming else android.R.drawable.sym_action_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(activityIntent())

        if (incoming) {
            builder
                .addAction(android.R.drawable.sym_call_incoming, getString(R.string.call_answer), actionIntent(ACTION_ANSWER, 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.call_decline), actionIntent(ACTION_DECLINE, 2))
        } else {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.call_end),
                actionIntent(ACTION_DISCONNECT, 3),
            )
        }

        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Memoised because the notification is rebuilt on every state and details change, and the
     * lookup is a synchronous provider query on the main thread.
     */
    private fun callerLabel(number: String): String {
        if (number != lookedUpNumber) {
            lookedUpNumber = number
            lookedUpName = CommunicateRepository.findContactName(this, number)
        }
        return lookedUpName ?: number
    }

    private fun clearNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, CommunicateInCallService::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "regular_calls"
        private const val NOTIFICATION_ID = 4811
        private const val ACTION_ANSWER = "com.vayunmathur.communicate.regularcall.ANSWER"
        private const val ACTION_DECLINE = "com.vayunmathur.communicate.regularcall.DECLINE"
        private const val ACTION_DISCONNECT = "com.vayunmathur.communicate.regularcall.DISCONNECT"

        private var currentCall: Call? = null
        private var lookedUpNumber: String? = null
        private var lookedUpName: String? = null
    }
}
