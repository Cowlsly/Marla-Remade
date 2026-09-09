package com.vayunmathur.updater.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backs the "restart now" action on the update-ready notification.
 *
 * A receiver rather than a direct `PendingIntent` to a service so the action works from the
 * shade without bringing the app to the foreground first. Not exported: what it does is reboot
 * the device.
 */
class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        IdleReboot.cancel(context)
        IdleReboot.reboot(context)
    }
}
