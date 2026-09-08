package com.vayunmathur.updater.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delivers the idle poll alarm and the notification's "restart now" action.
 *
 * Not exported: both intents come from this app's own `PendingIntent`s, and the action they
 * lead to is rebooting the device.
 */
class IdleRebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != IdleReboot.ACTION_POLL && action != IdleReboot.ACTION_REBOOT_NOW) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == IdleReboot.ACTION_REBOOT_NOW) {
                    IdleReboot.rebootNow(appContext)
                } else {
                    IdleReboot.poll(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
