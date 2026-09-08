package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaDownloadPlanTest {

    @Test
    fun `the incremental is tried before the full package`() {
        val candidates = OtaDownloadPlan.candidates("shiba", "2026090500", "2026090700")
        assertEquals(
            listOf("shiba-incremental-2026090500-2026090700.zip", "shiba-ota_update-2026090700.zip"),
            candidates.map { it.fileName },
        )
        assertTrue(candidates.first().incremental)
        assertFalse(candidates.last().incremental)
    }

    @Test
    fun `an unknown running build leaves only the full package`() {
        // The incremental filename embeds the source build, so without one there is nothing to
        // ask the server for.
        val candidates = OtaDownloadPlan.candidates("shiba", "", "2026090700")
        assertEquals(listOf("shiba-ota_update-2026090700.zip"), candidates.map { it.fileName })
    }

    @Test
    fun `a blank running build is treated the same as an absent one`() {
        assertEquals(1, OtaDownloadPlan.candidates("shiba", "   ", "2026090700").size)
    }

    @Test
    fun `targeting the running build offers no incremental`() {
        // A self-to-self incremental is not a thing the publisher produces, and asking for one
        // would spend a round trip to be told so.
        val candidates = OtaDownloadPlan.candidates("shiba", "2026090700", "2026090700")
        assertEquals(listOf("shiba-ota_update-2026090700.zip"), candidates.map { it.fileName })
    }

    @Test
    fun `no candidate asks for a streaming-layout artifact`() {
        // Streaming was removed deliberately: applyPayload against an https URL never reaches
        // RecoverySystem.verifyPackage, so the signature is never checked. If a '-streaming'
        // name ever reappears here, that bypass has come back with it.
        val candidates = OtaDownloadPlan.candidates("shiba", "2026090500", "2026090700")
        assertTrue(candidates.none { it.fileName.contains("-streaming") })
    }

    @Test
    fun `the device name is used verbatim`() {
        val candidates = OtaDownloadPlan.candidates("comet", "2026090500", "2026090700")
        assertTrue(candidates.all { it.fileName.startsWith("comet-") })
    }
}
