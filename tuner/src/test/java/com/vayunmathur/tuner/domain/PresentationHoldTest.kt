package com.vayunmathur.tuner.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hold both tabs share.
 *
 * The bug behind it was reported from real use twice over: the Chord tab strobed through a
 * strum's decay, and the Note tab's reading vanished while the player was still turning the
 * peg. Both are the same defect - a display bound directly to the current frame's detector
 * output - and both are fixed here rather than in two places.
 */
class PresentationHoldTest {
    @Test
    fun aFreshValueIsShownImmediately() {
        val hold = PresentationHold<String>(holdFrames = 5)
        hold.present("A")
        assertEquals("A", hold.current)
        assertFalse(hold.isStale, "a value shown on the frame it was detected is not stale")
    }

    @Test
    fun aMissingDetectionIsAbsorbedThenTheValueIsDropped() {
        val hold = PresentationHold<String>(holdFrames = 5)
        hold.present("A")
        repeat(4) {
            assertFalse(hold.idle(), "dropped after ${it + 1} idle frames, expected to hold 5")
            assertEquals("A", hold.current)
            assertTrue(hold.isStale, "a held value must report itself stale")
        }
        assertTrue(hold.idle(), "the hold never expired")
        assertNull(hold.current)
        assertFalse(hold.isStale)
    }

    @Test
    fun aNewValueDuringTheHoldReplacesItImmediately() {
        // The property that matters most on the Note tab: holding a stale reading over a
        // genuine change would have the player tuning against a note they stopped playing.
        val hold = PresentationHold<String>(holdFrames = 5)
        hold.present("A")
        hold.idle()
        hold.idle()
        assertTrue(hold.isStale)

        hold.present("B")
        assertEquals("B", hold.current)
        assertFalse(hold.isStale, "a fresh detection must clear the stale flag")

        // And the hold restarts from full, rather than carrying the earlier idle frames over.
        repeat(4) { assertFalse(hold.idle()) }
        assertTrue(hold.idle())
    }

    @Test
    fun idlingWithNothingShownDoesNothing() {
        val hold = PresentationHold<String>(holdFrames = 3)
        repeat(10) { assertFalse(hold.idle(), "reported a drop with nothing to drop") }
        assertNull(hold.current)
    }

    @Test
    fun theNoteTabHoldSpansTheGapBetweenPlucks() {
        // 512-sample hops at 48 kHz, so a frame is ~10.7 ms. The reading has to survive the
        // part of the tuning loop where the player is turning the peg and nothing is sounding.
        val frames = 1_200 * 48_000 / 1_000 / 512
        val ms = frames * 512 * 1000.0 / 48_000
        assertTrue(ms >= 1_000.0, "note hold is only $ms ms; too short to cover a peg adjustment")
        assertTrue(ms <= 1_500.0, "note hold of $ms ms outlasts the player moving to another string")
    }
}
