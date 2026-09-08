package com.vayunmathur.setupwizard.platform

import android.content.Context
import android.content.Intent

/**
 * The intents the wizard hands off to. Every one of them lands in another package, so each is
 * paired with a `<queries>` entry in the manifest or it would not resolve at all.
 *
 * The extras are the SetupCompat wizard-manager ones. They are plain strings here rather than
 * `WizardManagerHelper` constants because setupcompat is an AOSP-only library that is not on
 * the Gradle classpath; the values are the wire contract and have not changed since Android Q.
 *
 * `WizardManagerHelper.EXTRA_THEME` is deliberately not sent. Its value came from
 * `PartnerConfigHelper`, which is part of the same unavailable library, and a receiving screen
 * that is not told a theme resolves the platform default for itself - which is the value that
 * would have been sent.
 */
object SetupIntents {

    private const val EXTRA_IS_FIRST_RUN = "firstRun"
    private const val EXTRA_IS_SETUP_FLOW = "isSetupFlow"

    const val ACTION_ACCESSIBILITY = "android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW"
    const val ACTION_SETUP_INTERNET = "android.settings.SETUP_INTERNET"
    const val ACTION_BIOMETRIC_ENROLL = "android.settings.BIOMETRIC_ENROLL"
    const val ACTION_GESTURE_SANDBOX = "com.android.quickstep.action.GESTURE_SANDBOX"


    /**
     * The restore entry point.
     *
     * GrapheneOS pointed this at `com.stevesoltys.seedvault.RESTORE_BACKUP`. Seedvault is not
     * in this OS - `com.vayunmathur.backup` replaces it - and that app does not expose a
     * restore activity yet: its only entry point is `MainActivity`, which has no intent filter
     * and is reached through the backup transport's data-management intent.
     *
     * So this action resolves to nothing today and [restoreBackup] returns null, which makes
     * the restore step skip itself. The name mirrors the Seedvault one it replaces; if and
     * when the backup app grows a restore flow, exporting an activity under this action is the
     * whole of the wiring.
     */
    private const val ACTION_RESTORE_BACKUP = "com.vayunmathur.backup.RESTORE_BACKUP"

    /** Wi-Fi setup, told what to call itself so the screen reads as part of this flow. */
    fun setupInternet(title: String, description: String, skipText: String): Intent =
        Intent(ACTION_SETUP_INTERNET).setupFlow().apply {
            putExtra("setup_wizard_title", title)
            putExtra("setup_wizard_description", description)
            putExtra("extra_prefs_set_skip_text", skipText)
            putExtra("wifi_enable_next_on_connect", true)
            putExtra("setup_wizard_mode_wifi", true)
        }

    fun biometricEnroll(): Intent = Intent(ACTION_BIOMETRIC_ENROLL).setupFlow()

    fun gestureTutorial(): Intent = Intent(ACTION_GESTURE_SANDBOX).setupFlow()

    fun accessibilitySettings(): Intent = Intent(ACTION_ACCESSIBILITY).setupFlow()

    /** Null when nothing handles a restore, in which case the step skips itself. */
    fun restoreBackup(context: Context): Intent? =
        Intent(ACTION_RESTORE_BACKUP).setupFlow().takeIfResolvable(context)

    /**
     * Marks an intent as part of first-run setup. SetupDesign screens read these to pick the
     * wizard theme and to render a footer bar instead of an action bar.
     */
    private fun Intent.setupFlow(): Intent = apply {
        putExtra(EXTRA_IS_FIRST_RUN, true)
        putExtra(EXTRA_IS_SETUP_FLOW, true)
    }

    private fun Intent.takeIfResolvable(context: Context): Intent? =
        takeIf { it.resolveActivity(context.packageManager) != null }
}
