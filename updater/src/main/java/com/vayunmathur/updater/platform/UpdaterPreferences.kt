package com.vayunmathur.updater.platform

/**
 * The DataStore keys this app persists, in one place.
 *
 * The settings are read from two directions — the settings screen writes them, the background
 * job reads them — and a key spelled slightly differently in the second place is a setting that
 * silently stops being honoured. Nothing catches that: the read just returns the default.
 */
internal object UpdaterPreferences {

    /** Whether to download and apply without asking. On by default. */
    const val AUTO_INSTALL = "updater_auto_install"

    /** Whether auto-install may use a metered connection. Off by default — an OTA is 1-2 GB. */
    const val METERED_ALLOWED = "updater_metered_allowed"

    /** When the last check finished, epoch millis, so the UI can say how stale it is. */
    const val LAST_CHECKED = "updater_last_checked"

    /**
     * The build sitting on the inactive slot, waiting for a reboot, or absent when there is
     * none.
     *
     * Persisted because the applied slot outlives this process by hours: the payload is written,
     * everything gets killed, and something has to know on the next poll that a reboot is owed.
     */
    const val PENDING_REBOOT_BUILD = "updater_pending_reboot_build"

    /**
     * Last time the device was observed interactive, epoch millis.
     *
     * See `IdleRebootPolicy`. Persisted for the same reason: the idle wait is measured in
     * tens of minutes and the process will not survive it.
     */
    const val LAST_INTERACTIVE = "updater_last_interactive"
}
