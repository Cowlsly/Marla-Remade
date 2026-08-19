package com.vayunmathur.files.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArchiveLimitsTest {

    private val dest = File("/storage/emulated/0/out").canonicalFile

    @Test
    fun anOrdinaryEntryLandsInsideTheDestination() {
        val resolved = assertNotNull(ArchiveLimits.resolveEntry(dest, "photos/a.jpg"))
        assertEquals(File(dest, "photos/a.jpg").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun anEntryClimbingOutIsRejected() {
        assertNull(ArchiveLimits.resolveEntry(dest, "../../etc/passwd"))
        assertNull(ArchiveLimits.resolveEntry(dest, "a/../../../b"))
    }

    @Test
    fun anEntryEscapingIntoASiblingWithTheSamePrefixIsRejected() {
        // The bug this check used to have: comparing paths by raw prefix accepted a sibling whose
        // name merely starts with the destination's, so "out-evil" passed for a destination of "out".
        assertNull(ArchiveLimits.resolveEntry(dest, "../out-evil/payload"))
        assertNull(ArchiveLimits.resolveEntry(dest, "../outx"))
    }

    @Test
    fun anAbsoluteEntryNameIsPulledInsideTheDestination() {
        // File(parent, "/etc/hosts") treats the leading separator as a separator rather than a root,
        // so the entry lands under the destination instead of at the filesystem root.
        val resolved = assertNotNull(ArchiveLimits.resolveEntry(dest, "/etc/hosts"))
        assertEquals(true, resolved.canonicalPath.startsWith(dest.canonicalPath + File.separator))
    }

    @Test
    fun theDestinationItselfIsAllowed() {
        assertNotNull(ArchiveLimits.resolveEntry(dest, ""))
    }

    @Test
    fun theBudgetLeavesHeadroomFree() {
        val free = 4L * 1024 * 1024 * 1024
        val budget = ArchiveLimits.extractionBudget(free)
        assertEquals(true, budget in 1 until free, "budget $budget should be under $free but positive")
    }

    @Test
    fun anAlmostFullVolumeHasNoBudget() {
        assertEquals(0L, ArchiveLimits.extractionBudget(0))
        assertEquals(0L, ArchiveLimits.extractionBudget(1024))
    }

    @Test
    fun declaredSizesAreSummed() {
        assertEquals(600L, ArchiveLimits.declaredUncompressedSize(sequenceOf(100L, 200L, 300L)))
        assertEquals(0L, ArchiveLimits.declaredUncompressedSize(emptySequence()))
    }

    @Test
    fun anUndeclaredSizeMakesTheTotalUnknown() {
        // -1 is what ZipEntry reports when the archive does not say, and guessing would let a bomb
        // through the pre-flight check, so the caller has to fall back to the write-time budget.
        assertNull(ArchiveLimits.declaredUncompressedSize(sequenceOf(100L, -1L, 300L)))
    }
}
