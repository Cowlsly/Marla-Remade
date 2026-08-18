package com.vayunmathur.launcher.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager

/**
 * What this build is actually allowed to do, asked at runtime.
 *
 * The system launcher does several things a third-party one cannot: uninstall without a
 * confirmation dialog, pause a work profile, pull the notification shade down from the workspace.
 * Each is worth having *if* the app happens to be built into a ROM, and each must be invisible
 * otherwise — a menu item that always fails is worse than no menu item.
 *
 * So every probe here follows the shape [com.vayunmathur.library.ui.SpecialAccess] uses, with two
 * rules that matter more than the individual answers:
 *
 *  - **`runCatching`-guarded, defaulting to false.** These call into APIs that a stock device may
 *    not expose at all, and a launcher that crashes on the way up leaves the user with no home
 *    screen. Failing to answer therefore means "no".
 *  - **Nothing here grants anything.** A privileged permission is granted by being on the system
 *    image, not by asking. There are no `request*()` counterparts because there is nothing to
 *    request: the answer is fixed at install time.
 *
 * The consequence to hold onto: on an ordinary device every one of these is false, and the app
 * behaves exactly as it does without this file.
 */
class LauncherPrivilege(context: Context, private val bridge: () -> ActivityBridge?) {

    private val appContext = context.applicationContext

    /** Whether we hold the HOME role, which gates shortcuts, widgets and the wallpaper. */
    fun hasHomeRole(): Boolean = runCatching { bridge()?.isDefaultHome() == true }.getOrDefault(false)

    /**
     * Whether an app can be removed without the system's confirmation dialog.
     *
     * `DELETE_PACKAGES` is privileged, so this is false on a stock device and the uninstall goes
     * through `ACTION_DELETE` — which is the right behaviour there anyway.
     */
    fun canUninstallSilently(): Boolean = holds("android.permission.DELETE_PACKAGES")

    /**
     * Whether the work profile can be paused and resumed from the drawer's Work tab.
     *
     * `UserManager.requestQuietModeEnabled` is public API but needs `MODIFY_QUIET_MODE`, which is
     * not.
     */
    fun canToggleQuietMode(): Boolean = holds("android.permission.MODIFY_QUIET_MODE")

    /**
     * Whether a downward swipe on the workspace can pull the notification shade down, as it does on
     * the system launcher.
     *
     * Needs `EXPAND_STATUS_BAR`, and the call itself is not public API — see
     * [expandNotificationShade], which is why this is the probe rather than the feature.
     */
    fun canExpandNotificationShade(): Boolean = holds("android.permission.EXPAND_STATUS_BAR")

    /**
     * Pulls the notification shade down, and reports whether it worked.
     *
     * `StatusBarManager.expandNotificationsPanel` has never been public API, so this is reflection —
     * which is exactly why it is guarded twice over: by [canExpandNotificationShade] before it is
     * offered at all, and by `runCatching` here in case a release has renamed or removed it.
     */
    fun expandNotificationShade(): Boolean = runCatching {
        val service = appContext.getSystemService("statusbar") ?: return false
        service.javaClass.getMethod("expandNotificationsPanel").invoke(service)
        true
    }.getOrDefault(false)

    /** Pauses or resumes [user]'s profile, and reports whether it worked. */
    fun setQuietMode(user: UserHandle, quiet: Boolean): Boolean = runCatching {
        val manager = appContext.getSystemService(UserManager::class.java) ?: return false
        manager.requestQuietModeEnabled(quiet, user)
    }.getOrDefault(false)

    /** Whether [user]'s profile is currently paused. */
    fun isQuietModeEnabled(user: UserHandle): Boolean = runCatching {
        appContext.getSystemService(UserManager::class.java)?.isQuietModeEnabled(user) == true
    }.getOrDefault(false)

    private fun holds(permission: String): Boolean = runCatching {
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
