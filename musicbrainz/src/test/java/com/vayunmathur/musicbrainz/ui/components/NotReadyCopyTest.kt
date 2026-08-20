package com.vayunmathur.musicbrainz.ui.components

import com.vayunmathur.musicbrainz.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the one rule the not-ready screen must never break.
 *
 * The user's server has 5-7 GB free and a build currently needs 8 GB, so "the catalogue can never
 * be built here" is the REAL steady state on that hardware, not a corner case. Telling that user
 * to try again in a few minutes, forever, behind a button that cannot succeed, is the same
 * dishonesty as an endless spinner. So the copy is chosen only by whether waiting could change
 * the answer, and that choice is asserted here rather than left to review.
 */
class NotReadyCopyTest {

    @Test
    fun `waiting helps, so the wait copy and a retry are offered`() {
        val copy = notReadyCopy(retryable = true)
        assertEquals(R.string.catalogue_not_ready, copy.title)
        assertEquals(R.string.catalogue_not_ready_message, copy.fallbackMessage)
        assertTrue(copy.showRetry)
    }

    /**
     * The case that matters. No retry button, because pressing it can never work, and copy that
     * does not promise the catalogue is coming.
     */
    @Test
    fun `waiting cannot help, so no retry is offered`() {
        val copy = notReadyCopy(retryable = false)
        assertFalse(copy.showRetry, "a retry that cannot succeed must not be offered")
        assertEquals(R.string.catalogue_unavailable, copy.title)
        assertEquals(R.string.catalogue_unavailable_message, copy.fallbackMessage)
    }

    /**
     * The two branches must not share copy. If they ever collapse onto the same strings, the
     * non-retryable case silently starts telling the user to try again shortly - which is the
     * defect this whole split exists to prevent, and it would not fail any other test.
     */
    @Test
    fun `the two branches never share copy`() {
        val waiting = notReadyCopy(retryable = true)
        val terminal = notReadyCopy(retryable = false)
        assertNotEquals(waiting.title, terminal.title)
        assertNotEquals(
            waiting.fallbackMessage,
            terminal.fallbackMessage,
            "the terminal state must not reuse the 'try again in a few minutes' copy",
        )
        assertNotEquals(waiting.showRetry, terminal.showRetry)
    }

    /**
     * Retryability is the ONLY input. Nothing about the reason text, the state name or progress
     * may influence it - the server has already decided, and second-guessing it here is how the
     * two questions got conflated in the first place.
     */
    @Test
    fun `retryability is the only input`() {
        assertEquals(notReadyCopy(retryable = true), notReadyCopy(retryable = true))
        assertEquals(notReadyCopy(retryable = false), notReadyCopy(retryable = false))
        assertNotEquals(notReadyCopy(retryable = true), notReadyCopy(retryable = false))
    }
}
