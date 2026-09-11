package com.vayunmathur.health.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.health.notifications.DoseNotification
import com.vayunmathur.health.service.DoseSoundService

/**
 * Handles Taken and Snooze, from either the notification actions or the full-screen reminder.
 *
 * Both routes go through here rather than through the activity, because the notification's buttons
 * have to work when the activity was never allowed to start.
 */
class DoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID) ?: return
        val medicationId = intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID) ?: return

        stopRinging(context)

        when (intent.action) {
            ACTION_TAKEN -> Log.i(TAG, "Dose taken for $medicationId")
            ACTION_SNOOZE -> {
                DoseScheduler.armSnooze(context, scheduleId, medicationId, SNOOZE_MS)
                Log.i(TAG, "Dose for $medicationId snoozed for ${SNOOZE_MS / 60_000} minutes")
            }
        }
    }

    private fun stopRinging(context: Context) {
        DoseNotification.cancel(context)
        try {
            context.stopService(Intent(context, DoseSoundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop DoseSoundService", e)
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.vayunmathur.health.DOSE_TAKEN"
        const val ACTION_SNOOZE = "com.vayunmathur.health.DOSE_SNOOZE"

        /** Fixed rather than configurable, which is more than this needs for now. */
        const val SNOOZE_MS = 15 * 60 * 1000L

        private const val TAG = "DoseActionReceiver"
    }
}
