package com.vayunmathur.email.util

import androidx.core.content.edit

/**
 * Simple process-wide flag indicating whether the email app is currently in
 * the foreground (any activity at or above STARTED). Set from `MainActivity`'s
 * `onStart` / `onStop`. The sync worker reads this to suppress notifications
 * when the user is actively viewing the app.
 *
 * Also provides [tryStartIdleIfForeground] which is the safe entry point to
 * restart IDLE after boot when [BootReceiver] deferred it on S+. Called from
 * MainActivity.onStart when `idle_restart_pending` pref is set.
 */
object AppLifecycleTracker {
    @Volatile
    var isAppInForeground: Boolean = false

    /**
     * If the app is in foreground and a deferred IDLE restart is pending
     * (set by BootReceiver on S+), attempt to start ImapIdleService.
     * Returns true if start was attempted (regardless of success).
     */
    fun tryStartIdleIfForeground(context: android.content.Context): Boolean {
        if (!isAppInForeground) return false
        val prefs = context.getSharedPreferences(com.vayunmathur.email.util.BootReceiver.PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean(com.vayunmathur.email.util.BootReceiver.KEY_PENDING, false)) return false

        // Clear pending first to avoid loops, attempt start
        val started = com.vayunmathur.email.data.ImapIdleService.start(context)
        if (started) {
            prefs.edit { remove(com.vayunmathur.email.util.BootReceiver.KEY_PENDING) }
        } else {
            // Schedule retry worker if direct start failed
            try {
                com.vayunmathur.email.data.ImapIdleRetryWorker.schedule(context)
            } catch (_: Throwable) {}
        }
        return true
    }
}
