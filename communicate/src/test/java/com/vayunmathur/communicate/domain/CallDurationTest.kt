package com.vayunmathur.communicate.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CallDurationTest {
    @Test
    fun `zero shows seconds alone`() {
        assertEquals(CallDurationParts(null, null, 0), callDurationParts(0))
    }

    @Test
    fun `sub-minute shows seconds alone`() {
        assertEquals(CallDurationParts(null, null, 1), callDurationParts(1))
        assertEquals(CallDurationParts(null, null, 59), callDurationParts(59))
    }

    @Test
    fun `exact minute drops the seconds`() {
        assertEquals(CallDurationParts(null, 1, null), callDurationParts(60))
        assertEquals(CallDurationParts(null, 5, null), callDurationParts(300))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals(CallDurationParts(null, 5, 12), callDurationParts(312))
        assertEquals(CallDurationParts(null, 59, 59), callDurationParts(3599))
    }

    @Test
    fun `an hour or more drops the seconds`() {
        assertEquals(CallDurationParts(1, null, null), callDurationParts(3600))
        assertEquals(CallDurationParts(1, 3, null), callDurationParts(3600 + 3 * 60))
        assertEquals(CallDurationParts(1, 3, null), callDurationParts(3600 + 3 * 60 + 45))
        assertEquals(CallDurationParts(2, null, null), callDurationParts(7200 + 30))
    }

    @Test
    fun `negative input is clamped`() {
        assertEquals(CallDurationParts(null, null, 0), callDurationParts(-1))
    }
}
