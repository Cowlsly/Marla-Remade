package com.vayunmathur.share.platform.receive

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import com.vayunmathur.share.platform.transfer.ShareTransferService

private const val TAG = "ShareNotifRecv"

/**
 * Turns a notification's Accept / Reject / Cancel tap into service work.
 *
 * Forwarded to [ShareTransferService] rather than handled here: a broadcast receiver gets
 * roughly ten seconds and an accept starts a transfer that runs for minutes, so the follow-up
 * needs a foreground service to live in.
 *
 * `exported="false"` with no intent-filter and an explicit component target, following the
 * `email` / `clock` receivers: nothing outside the app can reach it.
 */
class ShareNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        val handle = intent.getLongExtra(ShareTransferService.EXTRA_SESSION_HANDLE, 0L)
        val notifId = intent.getIntExtra(ShareTransferService.EXTRA_NOTIF_ID, -1)
        if (handle == 0L) {
            Log.w(TAG, "$action with no session handle")
            return
        }
        if (ShareReceiveController.connectionFor(context, handle) == null) {
            // The process died between posting the notification and this tap, so the socket
            // and the native session are both gone. Say so instead of silently doing nothing.
            Log.w(TAG, "no live session $handle for $action; clearing notification $notifId")
            if (notifId >= 0) context.getSystemService<NotificationManager>()?.cancel(notifId)
            return
        }
        ShareTransferService.routeSessionAction(context, action, handle, notifId)
    }

    private companion object {
        val HANDLED = setOf(
            ShareTransferService.ACTION_ACCEPT,
            ShareTransferService.ACTION_REJECT,
            ShareTransferService.ACTION_CANCEL,
        )
    }
}
