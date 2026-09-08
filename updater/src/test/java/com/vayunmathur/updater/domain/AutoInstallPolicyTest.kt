package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertIs

class AutoInstallPolicyTest {

    private fun conditions(
        autoInstall: Boolean = true,
        meteredAllowed: Boolean = false,
        networkMetered: Boolean = false,
        connected: Boolean = true,
    ) = AutoInstallPolicy.Conditions(autoInstall, meteredAllowed, networkMetered, connected)

    @Test
    fun `unmetered with auto-install on proceeds`() {
        assertIs<AutoInstallPolicy.Decision.Proceed>(AutoInstallPolicy.decide(conditions()))
    }

    @Test
    fun `auto-install off holds even on wifi`() {
        assertIs<AutoInstallPolicy.Decision.Hold>(
            AutoInstallPolicy.decide(conditions(autoInstall = false)),
        )
    }

    @Test
    fun `a metered connection holds by default`() {
        assertIs<AutoInstallPolicy.Decision.Hold>(
            AutoInstallPolicy.decide(conditions(networkMetered = true)),
        )
    }

    @Test
    fun `a metered connection proceeds once the user opts in`() {
        assertIs<AutoInstallPolicy.Decision.Proceed>(
            AutoInstallPolicy.decide(conditions(networkMetered = true, meteredAllowed = true)),
        )
    }

    @Test
    fun `allowing metered data does not switch auto-install back on`() {
        // The two settings are independent, and the UI disables the metered toggle when
        // auto-install is off. Nothing should resurrect the outer setting from the inner one.
        assertIs<AutoInstallPolicy.Decision.Hold>(
            AutoInstallPolicy.decide(
                conditions(autoInstall = false, meteredAllowed = true, networkMetered = true),
            ),
        )
    }

    @Test
    fun `no network holds regardless of the settings`() {
        assertIs<AutoInstallPolicy.Decision.Hold>(
            AutoInstallPolicy.decide(
                conditions(connected = false, meteredAllowed = true),
            ),
        )
    }
}
