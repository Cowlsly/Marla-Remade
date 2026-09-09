package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole point of this class is that `applyPayload` reports failure through the same channel
 * as success, so "did it work" is a question about an int and nothing else. Getting it wrong in
 * the optimistic direction reboots a device onto a slot that was never written.
 */
class UpdateEngineOutcomeTest {

    @Test
    fun `zero is the only success`() {
        assertIs<UpdateEngineOutcome.Outcome.Applied>(UpdateEngineOutcome.of(0))
    }

    @Test
    fun `the generic error code is a failure`() {
        // ERROR = 1 sits immediately next to SUCCESS = 0. An off-by-one anywhere in the
        // classifier shows up here first.
        assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(1))
    }

    @Test
    fun `updated but not active is a failure, not a success`() {
        // 52 means the payload was written but the slot will NOT be switched. Rebooting boots
        // the old build. Reporting this as applied is the worst outcome available: the user is
        // told it worked, restarts, and nothing has changed.
        val outcome = assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(52))
        assertFalse(outcome.retryable)
    }

    @Test
    fun `a broken transfer is retryable`() {
        assertTrue(assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(9)).retryable)
    }

    @Test
    fun `running out of space is retryable`() {
        assertTrue(assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(60)).retryable)
    }

    @Test
    fun `a payload hash mismatch is not retryable`() {
        // The package passed the signature check before this point, so the payload disagreeing
        // with its own hash is not a flaky download. Re-fetching yields the same bytes.
        assertFalse(assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(10)).retryable)
    }

    @Test
    fun `a downgrade is not retryable`() {
        assertFalse(assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(51)).retryable)
    }

    @Test
    fun `an unknown code is a non-retryable failure that still reports its number`() {
        val outcome = assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(9999))
        assertFalse(outcome.retryable)
        assertEquals(9999, outcome.code)
        assertTrue(outcome.reason.contains("9999"))
    }

    @Test
    fun `a negative code is a failure`() {
        assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(-1))
    }

    @Test
    fun `an initialization error is flagged so a bad incremental is not retried forever`() {
        // Code 20. An incremental that cannot initialise against this slot will fail the same
        // way every time; without the flag the next run re-downloads it and never reaches the
        // full package.
        val outcome = assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(20))
        assertTrue(outcome.initializationFailure)
    }

    @Test
    fun `other failures are not initialization failures`() {
        for (code in listOf(1, 9, 10, 51, 52, 60, 61)) {
            val outcome = assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(code))
            assertFalse(outcome.initializationFailure, "code $code")
        }
    }

    @Test
    fun `every failure carries the code it was given`() {
        for (code in listOf(1, 9, 10, 11, 12, 51, 52, 60, 61)) {
            val outcome = assertIs<UpdateEngineOutcome.Outcome.Failed>(UpdateEngineOutcome.of(code))
            assertEquals(code, outcome.code)
            assertTrue(outcome.reason.isNotBlank())
        }
    }
}
