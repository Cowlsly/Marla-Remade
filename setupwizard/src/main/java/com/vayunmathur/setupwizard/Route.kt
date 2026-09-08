package com.vayunmathur.setupwizard

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The steps of first-run setup.
 *
 * The order is not declared here - it depends on who is setting up, so it lives in
 * [com.vayunmathur.setupwizard.platform.SetupFlow]. These are only the destinations.
 */
@Serializable
sealed interface Route : NavKey {

    /** Greeting, language, accessibility, and the unlocked-bootloader warning. */
    @Serializable
    data object Welcome : Route

    /**
     * The bootloader warning in full. Not part of the linear flow: the welcome step diverts
     * here when the bootloader is unlocked, and continuing from here resumes at the step that
     * would have followed Welcome.
     */
    @Serializable
    data object OemUnlock : Route

    /** Hands off to the system Wi-Fi setup screen. No UI of its own. */
    @Serializable
    data object Wifi : Route

    /** Location services, and Wi-Fi scanning for the device owner. */
    @Serializable
    data object Location : Route

    /** Hands off to lock-screen and biometric enrolment. No UI of its own. */
    @Serializable
    data object Security : Route

    /** Offers to restore apps and data from a backup. */
    @Serializable
    data object Migration : Route

    /** Offers the gesture-navigation tutorial. */
    @Serializable
    data object Gestures : Route

    /** Confirmation, the OEM-unlocking opt-out, and the write that ends setup. */
    @Serializable
    data object Finish : Route
}
