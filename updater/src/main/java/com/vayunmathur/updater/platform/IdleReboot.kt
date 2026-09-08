package com.vayunmathur.updater.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.domain.IdleRebootPolicy
import com.vayunmathur.updater.notifications.UpdateNotifications

private const val TAG = "IdleReboot"

/** Two wakeups an hour while a reboot is owed, against a thirty-minute idle requirement. */
private const val POLL_INTERVAL_MS = 15 * 60 * 1000L

/**
 * Waits for the device to be unused, then reboots into the slot that was just written.
 *
 * The wait is measured in hours, so none of it can live in memory — the process will be killed
 * long before the device is put down for the night. Instead an alarm polls: each tick records
 * whether the device was in use and asks [IdleRebootPolicy] whether enough uninterrupted idle
 * time has passed. State lives in DataStore, so a poll is correct even if it is the first thing
 * a fresh process does.
 *
 * Polling rather than listening for `ACTION_SCREEN_OFF` is deliberate: that broadcast cannot be
 * declared in a manifest, so receiving it would mean holding a foreground service alive for
 * hours purely to watch for it.
 */
object IdleReboot {

    const val ACTION_POLL = "com.vayunmathur.updater.action.IDLE_POLL"
    const val ACTION_REBOOT_NOW = "com.vayunmathur.updater.action.REBOOT_NOW"

    /** Start waiting. [build] is what the inactive slot now holds. */
    suspend fun arm(context: Context, build: String) {
        val store = DataStoreUtils.getInstance(context)
        store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, build)
        // Seed the idle clock from now. Without it the first poll has nothing to measure and
        // would have to skip, adding a whole interval to the wait.
        store.setLong(UpdaterPreferences.LAST_INTERACTIVE, System.currentTimeMillis())
        UpdateNotifications.rebootPending(context, build, rebootNowIntent(context))
        schedule(context)
    }

    /**
     * One alarm tick: reboot, or note the time and wait some more.
     *
     * Returns having either rebooted (in which case nothing after it runs) or rescheduled.
     */
    suspend fun poll(context: Context) {
        val store = DataStoreUtils.getInstance(context)
        val pending = store.getStringAwait(UpdaterPreferences.PENDING_REBOOT_BUILD)
        if (pending.isNullOrEmpty()) {
            Log.i(TAG, "no reboot pending; stopping the poll")
            return
        }

        val power = context.getSystemService<PowerManager>()
        if (power == null) {
            Log.w(TAG, "no PowerManager; cannot tell whether the device is in use")
            schedule(context)
            return
        }

        val now = System.currentTimeMillis()
        val interactive = power.isInteractive
        if (interactive) store.setLong(UpdaterPreferences.LAST_INTERACTIVE, now)

        val lastInteractive = store.getLongAwait(UpdaterPreferences.LAST_INTERACTIVE) ?: 0L
        if (IdleRebootPolicy.shouldReboot(interactive, lastInteractive, now)) {
            rebootNow(context)
            return
        }
        schedule(context)
    }

    /**
     * Reboot immediately, from the notification action or once the device is idle.
     *
     * The pending marker is cleared first. If the reboot is refused — no `REBOOT` permission,
     * which on a build where the privapp entry does not match is exactly what happens — leaving
     * the marker set would poll forever against a device that can never comply.
     */
    suspend fun rebootNow(context: Context) {
        DataStoreUtils.getInstance(context).setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "")
        val power = context.getSystemService<PowerManager>()
        if (power == null) {
            Log.e(TAG, "no PowerManager; cannot reboot")
            return
        }
        try {
            Log.i(TAG, "rebooting into the updated slot")
            power.reboot(null)
        } catch (e: Exception) {
            // SecurityException when REBOOT is not actually granted. Take the notification down
            // with it: leaving a "Restart now" action in the shade that can never succeed is
            // worse than saying nothing.
            Log.e(TAG, "reboot refused", e)
            UpdateNotifications.clearResult(context)
        }
    }

    private fun schedule(context: Context) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        // setAndAllowWhileIdle needs no permission and still fires in Doze, which matters: Doze
        // is precisely the state we are waiting for.
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + POLL_INTERVAL_MS,
            pollIntent(context),
        )
    }

    private fun pollIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, IdleRebootReceiver::class.java).setAction(ACTION_POLL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun rebootNowIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, IdleRebootReceiver::class.java).setAction(ACTION_REBOOT_NOW),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
