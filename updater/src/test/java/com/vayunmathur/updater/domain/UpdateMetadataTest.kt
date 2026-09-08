package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateMetadataTest {

    /** The exact line the live server serves today. */
    @Test
    fun `parses a real metadata line`() {
        val m = UpdateMetadata.parse("2026090700 1788644317 shiba stable")
        assertEquals(UpdateMetadata("2026090700", 1_788_644_317L), m)
    }

    /**
     * The trailing fields are the publisher's bookkeeping. A client that validated them would
     * break the moment a fifth was added, so extra fields must be ignored, not rejected.
     */
    @Test
    fun `ignores trailing fields it does not need`() {
        assertEquals(
            UpdateMetadata("2026090700", 1_788_644_317L),
            UpdateMetadata.parse("2026090700 1788644317 shiba stable something-new 12345"),
        )
    }

    @Test
    fun `accepts the two-field minimum`() {
        assertEquals(
            UpdateMetadata("2026090700", 1_788_644_317L),
            UpdateMetadata.parse("2026090700 1788644317"),
        )
    }

    @Test
    fun `tolerates surrounding whitespace and a trailing newline`() {
        assertEquals(
            UpdateMetadata("2026090700", 1_788_644_317L),
            UpdateMetadata.parse("  2026090700   1788644317 shiba stable \n"),
        )
    }

    /**
     * The realistic failure is a captive portal or an error page served with HTTP 200, which
     * arrives as HTML. It must parse to null rather than to a plausible-looking build.
     */
    @Test
    fun `rejects an html error page`() {
        assertNull(UpdateMetadata.parse("<!DOCTYPE html><html><head><title>404</title>"))
    }

    @Test
    fun `rejects a non-numeric build`() {
        assertNull(UpdateMetadata.parse("latest 1788644317 shiba stable"))
    }

    @Test
    fun `rejects a non-numeric date`() {
        assertNull(UpdateMetadata.parse("2026090700 yesterday shiba stable"))
    }

    @Test
    fun `rejects a zero or negative date`() {
        assertNull(UpdateMetadata.parse("2026090700 0 shiba stable"))
        assertNull(UpdateMetadata.parse("2026090700 -1 shiba stable"))
    }

    @Test
    fun `rejects too few fields`() {
        assertNull(UpdateMetadata.parse("2026090700"))
        assertNull(UpdateMetadata.parse(""))
        assertNull(UpdateMetadata.parse("   "))
    }
}

class UpdateComparisonTest {

    private val current = UpdateComparison.CurrentBuild("2026090500", 1_788_400_000L)

    @Test
    fun `a later build date is an update`() {
        assertTrue(
            UpdateComparison.isNewer(current, UpdateMetadata("2026090700", 1_788_644_317L)),
        )
    }

    @Test
    fun `the same build date is not an update`() {
        assertFalse(
            UpdateComparison.isNewer(current, UpdateMetadata("2026090500", 1_788_400_000L)),
        )
    }

    @Test
    fun `an older build date is not an update`() {
        assertFalse(
            UpdateComparison.isNewer(current, UpdateMetadata("2026090100", 1_788_000_000L)),
        )
    }

    /**
     * The comparison is on DATE, not on the build string. A republish that bumps the id without
     * moving the date is not an update, and a build id that sorts oddly must not change the
     * answer.
     */
    @Test
    fun `build id does not affect the comparison`() {
        assertFalse(
            UpdateComparison.isNewer(current, UpdateMetadata("9999999999", 1_788_400_000L)),
        )
        assertTrue(
            UpdateComparison.isNewer(current, UpdateMetadata("0000000001", 1_788_400_001L)),
        )
    }
}

class OtaArtifactsTest {

    @Test
    fun `names match what the release publishes`() {
        assertEquals(
            "shiba-ota_update-2026090700.zip",
            OtaArtifacts.full("shiba", "2026090700", streaming = false),
        )
        assertEquals(
            "shiba-incremental-2026090500-2026090700.zip",
            OtaArtifacts.incremental("shiba", "2026090500", "2026090700", streaming = false),
        )
    }

    @Test
    fun `streaming inserts the suffix before the artifact kind`() {
        assertEquals(
            "shiba-streaming-ota_update-2026090700.zip",
            OtaArtifacts.full("shiba", "2026090700", streaming = true),
        )
        assertEquals(
            "shiba-streaming-incremental-2026090500-2026090700.zip",
            OtaArtifacts.incremental("shiba", "2026090500", "2026090700", streaming = true),
        )
    }
}
