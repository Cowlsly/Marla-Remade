package com.vayunmathur.setupwizard.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the upstream operator-precedence bug this port deliberately fixes.
 *
 * GrapheneOS wrote the visibility rule as
 * `oemUnlocked.value?.not() ?: false && SetupWizard.isPrimaryUser`, which Kotlin parses as
 * `?: (false && isPrimaryUser)` because elvis binds looser than `&&`. The user check never ran,
 * so the OEM-unlocking checkbox was offered to secondary users, who cannot change OEM unlock
 * state. The last test here is the one that would have caught it.
 */
class SetupUiStateTest {

    @Test
    fun `offered when the bootloader is locked and the user owns the device`() {
        val state = SetupUiState(isPrimaryUser = true, bootloaderUnlocked = false)
        assertTrue(state.disableOemUnlockingVisible)
    }

    @Test
    fun `not offered while the bootloader is unlocked`() {
        val state = SetupUiState(isPrimaryUser = true, bootloaderUnlocked = true)
        assertFalse(state.disableOemUnlockingVisible)
    }

    @Test
    fun `not offered to a secondary user`() {
        val state = SetupUiState(isPrimaryUser = false, bootloaderUnlocked = false)
        assertFalse(state.disableOemUnlockingVisible)
    }

    @Test
    fun `not offered to a secondary user on an unlocked bootloader either`() {
        val state = SetupUiState(isPrimaryUser = false, bootloaderUnlocked = true)
        assertFalse(state.disableOemUnlockingVisible)
    }

    /** Tracks the current reading rather than whatever it was when the process started. */
    @Test
    fun `follows the bootloader state it is copied with`() {
        val locked = SetupUiState(isPrimaryUser = true, bootloaderUnlocked = true)
        assertFalse(locked.disableOemUnlockingVisible)
        assertTrue(locked.copy(bootloaderUnlocked = false).disableOemUnlockingVisible)
    }
}
