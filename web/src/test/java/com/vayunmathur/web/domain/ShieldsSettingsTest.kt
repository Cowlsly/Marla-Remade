package com.vayunmathur.web.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShieldsSettingsTest {

    private val global = ShieldsSettings.AGGRESSIVE_DEFAULTS

    @Test
    fun `aggressive defaults turn everything on`() {
        val shields = EffectiveShields.resolve(global)
        assertTrue(shields.blockTrackers)
        assertTrue(shields.cosmeticFiltering)
        assertTrue(shields.fingerprintProtection)
        assertTrue(shields.httpsUpgrade)
        assertTrue(shields.aggressiveFingerprinting)
        assertTrue(shields.httpsOnly)
    }

    @Test
    fun `standard keeps blocking but drops the aggressive extras`() {
        val shields = EffectiveShields.resolve(global.copy(level = ShieldLevel.STANDARD))
        assertTrue(shields.blockTrackers)
        assertTrue(shields.fingerprintProtection)
        assertTrue(shields.httpsUpgrade)
        assertFalse(shields.aggressiveFingerprinting)
        assertFalse(shields.httpsOnly)
    }

    @Test
    fun `off disables every shield`() {
        assertEquals(EffectiveShields.OFF, EffectiveShields.resolve(global.copy(level = ShieldLevel.OFF)))
    }

    @Test
    fun `a site level of off wins over an aggressive global`() {
        val shields = EffectiveShields.resolve(global, ShieldsSettings(level = ShieldLevel.OFF))
        assertEquals(EffectiveShields.OFF, shields)
        assertFalse(shields.anyEnabled)
    }

    @Test
    fun `a site override only affects the shield it names`() {
        val shields = EffectiveShields.resolve(global, ShieldsSettings(cosmeticFiltering = false))
        assertFalse(shields.cosmeticFiltering)
        assertTrue(shields.blockTrackers)
        assertTrue(shields.fingerprintProtection)
        assertTrue(shields.httpsUpgrade)
    }

    @Test
    fun `unset site fields keep tracking the global value`() {
        val site = ShieldsSettings(cosmeticFiltering = false)
        val shields = EffectiveShields.resolve(global.copy(blockTrackers = false), site)
        assertFalse(shields.blockTrackers)
        assertFalse(shields.cosmeticFiltering)
    }

    @Test
    fun `disabling fingerprinting also disables its aggressive extras`() {
        val shields = EffectiveShields.resolve(global, ShieldsSettings(fingerprintProtection = false))
        assertFalse(shields.fingerprintProtection)
        assertFalse(shields.aggressiveFingerprinting)
    }

    @Test
    fun `a site can raise its level above a standard global`() {
        val shields = EffectiveShields.resolve(
            global.copy(level = ShieldLevel.STANDARD),
            ShieldsSettings(level = ShieldLevel.AGGRESSIVE),
        )
        assertTrue(shields.aggressiveFingerprinting)
        assertTrue(shields.httpsOnly)
    }

    @Test
    fun `an empty override is reported as droppable`() {
        assertTrue(ShieldsSettings().isEmpty)
        assertFalse(ShieldsSettings(blockTrackers = true).isEmpty)
        assertFalse(ShieldsSettings(level = ShieldLevel.OFF).isEmpty)
    }
}
