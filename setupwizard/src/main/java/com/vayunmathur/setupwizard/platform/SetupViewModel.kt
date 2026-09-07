package com.vayunmathur.setupwizard.platform

import android.app.Activity
import android.app.Application
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/** How long the unlocked-bootloader step makes the user wait before it can be dismissed. */
const val OEM_UNLOCK_ACK_SECONDS = 10

/**
 * Whether the SIM's own locale is applied on entry to the welcome step. Constant rather than a
 * setting, matching the upstream wizard - a phone with a Japanese SIM should come up in
 * Japanese without anyone choosing it.
 */
private const val APPLY_SIM_LANGUAGE_ON_ENTRY = true

/** Everything the wizard's screens render from. */
data class SetupUiState(
    /** The device owner, as opposed to a secondary user setting up a profile. */
    val isPrimaryUser: Boolean = true,
    /** The current system language, in its own language. */
    val language: String = "",
    /** The bootloader will boot an image someone else signed. */
    val bootloaderUnlocked: Boolean = false,
    /** The "OEM unlocking" toggle is on, so the bootloader could be unlocked in future. */
    val oemUnlockingEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val wifiScanningEnabled: Boolean = false,
    val deviceSecure: Boolean = false,
    val date: String = "",
    val time: String = "",
    val timeZone: String = "",
    /** Seconds left before the bootloader step's "continue" button can be used. */
    val bootloaderAckSeconds: Int = OEM_UNLOCK_ACK_SECONDS,
    /** Whether the final step's OEM-unlocking checkbox starts ticked. */
    val disableOemUnlockingChecked: Boolean = true,
) {
    /**
     * Whether the final step offers to turn OEM unlocking off: only worth asking when the
     * bootloader is locked (so there is something to protect) and only the device owner can
     * answer.
     *
     * Upstream wrote this as `oemUnlocked.value?.not() ?: false && isPrimaryUser`, which Kotlin
     * parses as `?: (false && isPrimaryUser)` because elvis binds looser than `&&`. The
     * user check was therefore dead and the checkbox appeared for secondary users, who cannot
     * change OEM unlock state at all. Fixed rather than reproduced: it is a security control,
     * and one that does nothing when a secondary user ticks it.
     *
     * It is also computed here rather than fixed at class-init as it was upstream, so it
     * tracks [bootloaderUnlocked] instead of whatever that happened to be when the process
     * started.
     */
    val disableOemUnlockingVisible: Boolean
        get() = !bootloaderUnlocked && isPrimaryUser
}

/**
 * Holds the wizard's state for the life of the process.
 *
 * The upstream app spread this across eight process-global `object … : ViewModel()` singletons,
 * each with an `init {}` that touched its actions object purely to force class initialisation.
 * There is one flow and one Activity, so there is one ViewModel: the ordering that the
 * `<clinit>` chain was arranging by hand is just constructor order here.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    val system = SystemSetup(app)

    var state by mutableStateOf(SetupUiState())
        private set

    private var simLocaleApplied = false
    private var ackTimerStarted = false

    init {
        state = state.copy(
            isPrimaryUser = system.isPrimaryUser,
            disableOemUnlockingChecked = !system.isOsDebuggable,
        )
        refreshLanguage()
        refreshOemUnlockState()
        refreshLocation()
        refreshSecurity()
        refreshClock()
    }

    // ---- welcome --------------------------------------------------------------------------

    /**
     * Called when the welcome step is shown. Hides the status bar for the rest of setup and
     * takes the SIM's language if there is one, which is the closest thing to a guess at what
     * the user reads.
     */
    fun onEnterWelcome() {
        system.setStatusBarHiddenForSetup(true)
        if (!APPLY_SIM_LANGUAGE_ON_ENTRY || simLocaleApplied) return
        val simLocale = system.simLocale() ?: return
        simLocaleApplied = true
        setLanguage(simLocale)
    }

    fun availableLanguages(): List<Locale> = system.availableLocales()

    fun setLanguage(locale: Locale) {
        system.setSystemLocale(locale)
        refreshLanguage()
    }

    private fun refreshLanguage() {
        val locale = system.currentLocale()
        state = state.copy(language = locale.getDisplayName(locale))
    }

    /**
     * Where the welcome step's "next" goes. An unlocked bootloader diverts to the warning; a
     * debuggable OS build skips the diversion, since the whole point of one is to run unsigned
     * images. A secondary user is never asked - the device was provisioned before they existed.
     */
    fun welcomeLeadsToBootloaderWarning(): Boolean {
        if (system.isOsDebuggable &&
            DebugFlags.getBool(getApplication(), "enableUnlockedBootloaderHandling") != true
        ) {
            return false
        }
        if (!state.isPrimaryUser) return false
        return state.bootloaderUnlocked
    }

    // ---- bootloader -----------------------------------------------------------------------

    /**
     * Counts the acknowledgement timer down once per screen. Guarded because the upstream
     * version restarted the countdown on every rebind, so a rotation reset it.
     */
    fun startBootloaderAckTimer() {
        if (ackTimerStarted) return
        ackTimerStarted = true
        viewModelScope.launch {
            while (state.bootloaderAckSeconds > 0) {
                delay(1_000)
                state = state.copy(bootloaderAckSeconds = state.bootloaderAckSeconds - 1)
            }
        }
    }

    fun rebootToBootloader() = system.rebootToBootloader()

    private fun refreshOemUnlockState() {
        state = state.copy(
            bootloaderUnlocked = system.isDeviceOemUnlocked(),
            oemUnlockingEnabled = system.isOemUnlockAllowedByUser(),
        )
    }

    // ---- location -------------------------------------------------------------------------

    fun setLocationEnabled(enabled: Boolean) {
        system.setLocationEnabled(enabled)
        refreshLocation()
    }

    fun setWifiScanningEnabled(enabled: Boolean) {
        system.setWifiScanningAlwaysAvailable(enabled)
        refreshLocation()
    }

    private fun refreshLocation() {
        state = state.copy(
            locationEnabled = system.isLocationEnabled(),
            wifiScanningEnabled = system.isWifiScanningAlwaysAvailable(),
        )
    }

    // ---- security -------------------------------------------------------------------------

    fun refreshSecurity() {
        state = state.copy(deviceSecure = system.isDeviceSecure())
    }

    // ---- clock ----------------------------------------------------------------------------

    fun setDate(year: Int, month: Int, dayOfMonth: Int) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
        system.setTimeMillis(calendar.timeInMillis)
        refreshClock()
    }

    fun setTime(hourOfDay: Int, minute: Int) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        system.setTimeMillis(calendar.timeInMillis)
        refreshClock()
    }

    fun setTimeZone(zoneId: String) {
        system.setTimeZone(zoneId)
        refreshClock()
    }

    /** Re-reads the clock. Also driven by the minute tick while the date step is on screen. */
    fun refreshClock() {
        val app = getApplication<Application>()
        val now = Calendar.getInstance().time
        state = state.copy(
            date = DateFormat.getDateFormat(app).format(now),
            time = DateFormat.getTimeFormat(app).format(now),
            timeZone = TimeZoneCatalog.current().standardName,
        )
    }

    fun timeZones(): List<ZoneInfo> = TimeZoneCatalog.all(getApplication())

    // ---- finish ---------------------------------------------------------------------------

    /**
     * Ends setup: optionally turns OEM unlocking off, records that the device and this user are
     * provisioned, clears the wizard out of recents and disables its package so it stops
     * winning HOME resolution.
     */
    fun finishSetup(activity: Activity, disableOemUnlocking: Boolean) {
        if (disableOemUnlocking) system.setOemUnlockAllowedByUser(false)
        system.markSetupComplete(activity)
        system.finishAllTasks(activity)
        activity.finish()
        system.setStatusBarHiddenForSetup(false)
        system.disableSelf()
    }
}
