package com.vayunmathur.share.platform.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores receiving after a reboot or an app update, if the tile left it on.
 *
 * The flag is the source of truth, so this only has to reconcile:
 * [ShareReceiveController.syncServiceState] decides whether to start anything.
 */
class ShareBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ShareReceiveController.syncServiceState(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
