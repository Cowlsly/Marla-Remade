package com.vayunmathur.library.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The permissions that cannot be requested with an `ActivityResultContract`
 * and have to be granted in system settings instead.
 *
 * These are why [PermissionWall] takes an `onRequest` lambda rather than a
 * permission array: there is nothing to request. All any app can do is send
 * the user to the right settings page, and each app was hand-rolling that
 * intent - which is easy to get wrong, since several of these need the package
 * URI and some only exist on newer releases.
 *
 * Each returns false when the platform is too old for that page to exist, so
 * callers can skip the wall entirely rather than launching an intent that
 * resolves to nothing.
 */
object SpecialAccess {

    private fun Context.launch(intent: Intent) =
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun packageUri(context: Context) =
        Uri.fromParts("package", context.packageName, null)

    /** "All files access" - needed to browse storage outside the media collections. */
    fun hasAllFilesAccess(): Boolean = android.os.Environment.isExternalStorageManager()

    fun requestAllFilesAccess(context: Context) {
        context.launch(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                packageUri(context),
            )
        )
    }

    /**
     * Exact alarms, required for anything that must fire at a precise time.
     *
     * From Android 13 on, an app that declares `USE_EXACT_ALARM` is granted this
     * at install and the user cannot revoke it, so only older releases need the
     * revocable `SCHEDULE_EXACT_ALARM` access checked.
     */
    fun hasExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        return manager?.canScheduleExactAlarms() ?: false
    }

    fun requestExactAlarms(context: Context) {
        context.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
    }

    /** Notification listener access, for reading other apps' notifications. */
    fun hasNotificationListener(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    fun requestNotificationListener(context: Context) =
        context.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /** The always-on VPN page; there is no way to query consent up front. */
    fun openVpnSettings(context: Context) =
        context.launch(Intent(Settings.ACTION_VPN_SETTINGS))

    /** Full-screen intent permission, for alarm-style full screen notifications. */
    fun hasFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return manager?.canUseFullScreenIntent() ?: false
    }

    fun requestFullScreenIntent(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.launch(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
            )
        } else {
            openAppSettings(context)
        }
    }
}
