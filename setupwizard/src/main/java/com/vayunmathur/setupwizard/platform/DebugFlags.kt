package com.vayunmathur.setupwizard.platform

import android.content.Context
import android.provider.Settings

/**
 * Overrides for states that are otherwise impossible to reach on a development device: an
 * unlocked bootloader on a locked one, an already-provisioned device that still shows the
 * wizard.
 *
 * Only honoured on a debuggable OS build - see [SystemSetup.isOsDebuggable] - so the same code
 * on a user build reads no flags at all and every caller falls through to the real state.
 *
 * ```
 * adb shell settings put global setupwizard_debug_flags flag_1=value_1,flag_2,flag_3=value_3
 * ```
 *
 * A flag given without a value is `true`.
 */
object DebugFlags {

    private const val SETTING = "setupwizard_debug_flags"

    fun get(context: Context, name: String): String? = parse(context)[name]

    fun getBool(context: Context, name: String): Boolean? =
        get(context, name)?.toBooleanStrictOrNull()

    private fun parse(context: Context): Map<String, String> {
        val raw = Settings.Global.getString(context.contentResolver, SETTING) ?: return emptyMap()
        return raw.split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val separator = entry.indexOf('=')
                if (separator < 0) entry to "true"
                else entry.substring(0, separator) to entry.substring(separator + 1)
            }
    }
}
