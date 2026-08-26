package com.vayunmathur.appstore.data

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.edit

/**
 * Packages the OS will not let this store install, so it stops offering them updates.
 *
 * GrapheneOS reserves a few Google packages for its own first-party package source. Committing
 * a session for one fails with `INSTALL_FAILED_SESSION_INVALID: Only the first party package
 * source and shell are allowed to install <package>`, which is not something the user can act
 * on — so a pending update for it is a notification that can only ever be dismissed.
 *
 * [KNOWN] is consulted on GrapheneOS only; on stock Android these are ordinary updatable apps.
 * [recordIfRestricted] adds whatever else the installer finds out the hard way, so a
 * restriction that isn't listed here costs one failed install rather than one per update check.
 */
object RestrictedPackages {

    /** Android Auto, which GrapheneOS moved to its first-party source. */
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"

    private val KNOWN = setOf(ANDROID_AUTO)

    /**
     * GrapheneOS's own app store, preinstalled on every build — and the first-party source the
     * restricted installs are reserved for. Present as a system package only on GrapheneOS,
     * which makes it a cheaper and steadier signal than any build fingerprint or property.
     */
    private const val GRAPHENEOS_APPS = "app.grapheneos.apps"

    /** The distinguishing part of the OS's refusal, matched case-insensitively. */
    private const val RESTRICTED_MESSAGE = "first party package source"

    private const val PREFS = "appstore-restricted-packages"
    private const val KEY_LEARNED = "restricted_packages"

    /** Packages to leave out of update checks on this device. */
    fun forDevice(context: Context): Set<String> =
        learned(context) + if (isGrapheneOS(context)) KNOWN else emptySet()

    /**
     * Remember [packageName] when [message] is the OS refusing the install as source-restricted.
     *
     * Anything else — an incompatible signer, no storage — is a failure the user can do
     * something about, and must not stop the store offering the update again.
     */
    fun recordIfRestricted(context: Context, packageName: String?, message: String?) {
        if (packageName.isNullOrEmpty()) return
        if (message?.contains(RESTRICTED_MESSAGE, ignoreCase = true) != true) return
        prefs(context).edit { putStringSet(KEY_LEARNED, learned(context) + packageName) }
    }

    private fun learned(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LEARNED, emptySet()).orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isGrapheneOS(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(GRAPHENEOS_APPS, 0)
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)
}
