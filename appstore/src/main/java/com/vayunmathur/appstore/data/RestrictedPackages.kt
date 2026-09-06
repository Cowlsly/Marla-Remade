package com.vayunmathur.appstore.data

import android.content.Context
import androidx.core.content.edit

/**
 * Packages the OS will not let this store install, so it stops offering them updates.
 *
 * GrapheneOS reserves a few Google packages for its own first-party package source. Committing
 * a session for one fails with `INSTALL_FAILED_SESSION_INVALID: Only the first party package
 * source and shell are allowed to install <package>`, which is not something the user can act
 * on — so a pending update for it is a notification that can only ever be dismissed.
 *
 * [KNOWN] is consulted on GrapheneOS-derived builds only; on stock Android these are ordinary
 * updatable apps. [recordIfRestricted] adds whatever else the installer finds out the hard way,
 * so a restriction that isn't listed here costs one failed install rather than one per update
 * check.
 */
object RestrictedPackages {

    /** Android Auto, which GrapheneOS moved to its first-party source. */
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"

    private val KNOWN = setOf(ANDROID_AUTO)

    /**
     * The system feature GrapheneOS declares, and which Modern Apps OS inherits from it.
     *
     * The old check looked for `app.grapheneos.apps` as a system package, which is GrapheneOS's
     * own store — Modern Apps OS ships this store instead and so was misread as stock Android,
     * losing both the restricted-package list and the Sandboxed Google Play section. The
     * feature is true on both and false on stock, which is the distinction that actually
     * matters here.
     */
    private const val GRAPHENEOS_FEATURE = "grapheneos.version"

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

    /**
     * Whether this is GrapheneOS or a build derived from it, such as Modern Apps OS.
     *
     * Also gates the Sandboxed Google Play section: those packages only work alongside the
     * gmscompat layer, which is part of the OS, so offering them on stock Android would offer
     * an install that cannot function.
     */
    fun isGrapheneOS(context: Context): Boolean =
        context.packageManager.hasSystemFeature(GRAPHENEOS_FEATURE)
}
