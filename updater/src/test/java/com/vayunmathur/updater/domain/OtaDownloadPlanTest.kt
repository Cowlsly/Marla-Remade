package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaDownloadPlanTest {

    @Test
    fun `both artifacts are named when the running build is known`() {
        val artifacts = OtaDownloadPlan.artifacts("shiba", "2026090500", "2026090700")
        assertEquals("shiba-incremental-2026090500-2026090700.zip", artifacts.incremental)
        assertEquals("shiba-ota_update-2026090700.zip", artifacts.full)
    }

    @Test
    fun `an unknown running build leaves no incremental`() {
        // The incremental filename embeds the source build, so without one there is nothing to
        // ask the server for.
        assertNull(OtaDownloadPlan.artifacts("shiba", "", "2026090700").incremental)
    }

    @Test
    fun `a blank running build is treated the same as an absent one`() {
        assertNull(OtaDownloadPlan.artifacts("shiba", "   ", "2026090700").incremental)
    }

    @Test
    fun `targeting the running build names no incremental`() {
        // A self-to-self incremental is not something the publisher produces, and asking for one
        // would spend a round trip to be told so.
        assertNull(OtaDownloadPlan.artifacts("shiba", "2026090700", "2026090700").incremental)
    }

    @Test
    fun `the full package is always named`() {
        assertEquals(
            "shiba-ota_update-2026090700.zip",
            OtaDownloadPlan.artifacts("shiba", "", "2026090700").full,
        )
    }

    @Test
    fun `neither artifact asks for a streaming layout`() {
        // Streaming was removed deliberately: applyPayload against an https URL never reaches
        // RecoverySystem.verifyPackage, so the signature is never checked. If a '-streaming'
        // name reappears here, that bypass has come back with it.
        val artifacts = OtaDownloadPlan.artifacts("shiba", "2026090500", "2026090700")
        assertTrue(artifacts.incremental?.contains("-streaming") == false)
        assertTrue(!artifacts.full.contains("-streaming"))
    }

    @Test
    fun `the device name is used verbatim`() {
        val artifacts = OtaDownloadPlan.artifacts("comet", "2026090500", "2026090700")
        assertTrue(artifacts.full.startsWith("comet-"))
        assertTrue(artifacts.incremental!!.startsWith("comet-"))
    }
}
