package com.vayunmathur.cast

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log

private const val TAG = "CastApplication"

/** The launcher entry, an `<activity-alias>` so it can be hidden without touching MainActivity. */
private const val LAUNCHER_ALIAS = "com.vayunmathur.cast.LauncherAlias"

/**
 * Hides the launcher icon when this app is the privileged system cast provider — i.e. only on
 * MAOS, where the OS itself drives casting (the system Cast page, desktop mode) and the app's own
 * screen-sharing UI is redundant. An ordinary install (e.g. from F-Droid) is never granted
 * `REMOTE_DISPLAY_PROVIDER`, so the icon stays and the app works exactly as before.
 *
 * This mirrors the Files app's approach: a component whose enabled state follows a MAOS-only
 * signal, flipped from a small [Application] at runtime rather than baked into the manifest, so the
 * APK is byte-for-byte identical everywhere it ships.
 */
class CastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!isSystemCastProvider()) return
        val alias = ComponentName(this, LAUNCHER_ALIAS)
        // Idempotent: only the first launch on MAOS actually flips it; the setting then persists.
        if (packageManager.getComponentEnabledSetting(alias) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            return
        }
        runCatching {
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "could not hide the launcher icon", it) }
    }

    /**
     * True when the OS granted this app `REMOTE_DISPLAY_PROVIDER` — the signal that it is the
     * preinstalled, privileged system cast provider (only ever the case on MAOS). It is
     * `signature|privileged`, so an ordinary install requests it but is denied.
     */
    private fun isSystemCastProvider(): Boolean =
        checkSelfPermission("android.permission.REMOTE_DISPLAY_PROVIDER") ==
            PackageManager.PERMISSION_GRANTED
}
