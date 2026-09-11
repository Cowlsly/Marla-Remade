package com.vayunmathur.safefamily.platform

import android.os.Bundle

/**
 * The three questions Settings asks a supervision app, and this build's answers.
 *
 * Ids and Bundle shapes are fixed by `packages/apps/Settings/src/com/android/settings/supervision/
 * ipc/{PreferenceDataApi,SupportedAppsApi,IsSupervisorAccountApi}.kt`. They are restated here
 * because a Gradle-built prebuilt cannot link Settings or `SettingsLib`; see
 * `SupervisionMessengerService` for why the whole protocol is reimplemented.
 *
 * **Every answer here is deliberately empty, and that is the design rather than a stub.** Safe
 * Family exists so that the supervision feature *has a role holder*; the controls themselves -
 * the PIN, bedtime schedules, app limits, web content and app store filters - are implemented by
 * Settings and enforced by `SupervisionService`. These APIs are the channel through which a
 * supervision app injects **its own extra** rows and app lists on top of that, and this build
 * adds none. Answering with nothing is what says so; inventing rows would put UI on screen that
 * leads nowhere.
 */
object SupervisionApis {

    const val PREFERENCE_DATA = 1
    const val SUPPORTED_APPS = 2
    const val IS_SUPERVISOR_ACCOUNT = 3

    /** Key that [isSupervisorAccount]'s boolean travels under. */
    private const val IS_SUPERVISOR_ACCOUNT_KEY = "is_supervisor_account"

    /**
     * Extra preference rows to inject into the supervision dashboard, by key.
     *
     * Empty: Safe Family contributes no features of its own. Settings reads an absent key as
     * "nothing to show" and renders only what it owns, which is the whole intent.
     */
    fun preferenceData(@Suppress("UNUSED_PARAMETER") request: Bundle?): Bundle = Bundle()

    /**
     * Apps this supervision app can filter, per content-filter key.
     *
     * Empty for the same reason: the app store and web content filters are the platform's, and
     * their app lists come from the platform.
     */
    fun supportedApps(@Suppress("UNUSED_PARAMETER") request: Bundle?): Bundle = Bundle()

    /**
     * Whether a supervising *account* is linked.
     *
     * **False, honestly.** Supervision here is secured by an on-device PIN and nothing else -
     * there is no account to sign into and nothing leaves the device. Claiming true would make
     * Settings offer account-shaped affordances that could not work.
     */
    fun isSupervisorAccount(): Bundle =
        Bundle().apply { putBoolean(IS_SUPERVISOR_ACCOUNT_KEY, false) }
}
