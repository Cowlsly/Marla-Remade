package com.vayunmathur.safefamily.platform

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "SafeFamilyUsageAccess"

/** `AppOpsManager.OPSTR_GET_USAGE_STATS`, which gates `queryAndAggregateUsageStats`. */
private const val OPSTR_GET_USAGE_STATS = "android:get_usage_stats"

/** `AppOpsManager.MODE_ALLOWED`. */
private const val MODE_ALLOWED = 0

/**
 * Makes sure this app can read per-app foreground time.
 *
 * ## Why this is needed at all
 *
 * `PACKAGE_USAGE_STATS` is `signature|privileged|development|appop|retailDemo`. Safe Family is
 * preinstalled but **not** a priv-app, so the permission half never applies and the app-op sits
 * at `MODE_DEFAULT` - which resolves to denied. Declaring the permission is not enough; verified
 * on device, where `appops get ... GET_USAGE_STATS` reported "No operations. Default mode:
 * default" while the permission was declared.
 *
 * ## Why it matters
 *
 * Without usage stats, [AppLimits] cannot pass a real `timeUsed` when it arms an observer, so a
 * cap measures from the moment it was armed. That makes **rebooting a way to reset a daily
 * limit**, which is not a rough edge in a parental control - it is the whole feature defeated by
 * a child who notices.
 *
 * ## Why self-granting is legitimate here
 *
 * `MANAGE_APP_OPS_MODES` is part of the `SYSTEM_SUPERVISION` role's grant, gated on
 * `android.permission.flags.supervision_role_enabled` which is ENABLED on this build. Managing
 * app ops is squarely what the role exists to let a supervision app do. The op is set on our own
 * uid and nothing else's.
 *
 * `setUidMode` is an `@SystemApi` member of the public `AppOpsManager`, so it is reached
 * reflectively for the same reason [AppLimits] reflects - a stub would shadow the real class -
 * and relies on the same `hidden-api-whitelisted-app` entry in `sysconfig-modern-apps.xml`.
 * The check side, `unsafeCheckOpNoThrow`, is public API and needs neither.
 */
object UsageAccess {

    /**
     * Grant ourselves the usage-stats op if it is not already allowed.
     *
     * Idempotent and cheap - the check is a public call and the grant only runs when the mode is
     * wrong - so callers can invoke it on every reconcile rather than tracking whether it ran.
     */
    fun ensure(context: Context) {
        val ops = context.getSystemService<AppOpsManager>() ?: return
        val uid = Process.myUid()
        val packageName = context.packageName

        val current = runCatching {
            ops.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, packageName)
        }.getOrElse {
            Log.w(TAG, "could not read the usage-stats op", it)
            return
        }
        if (current == MODE_ALLOWED) return

        val method = runCatching {
            AppOpsManager::class.java.getMethod(
                "setUidMode",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrElse {
            Log.w(TAG, "AppOpsManager.setUidMode is unreachable", it)
            return
        }

        runCatching { method.invoke(ops, OPSTR_GET_USAGE_STATS, uid, MODE_ALLOWED) }
            .onSuccess { Log.i(TAG, "granted the usage-stats op to ourselves") }
            .onFailure {
                // SecurityException when MANAGE_APP_OPS_MODES is not held, which happens if the
                // role is unheld or the manifest declaration was dropped. Limits still work from
                // the moment they are armed; only the mid-day resume is lost.
                Log.w(TAG, "could not grant the usage-stats op; limits will not survive a reboot", it)
            }
    }
}
