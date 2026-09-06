package com.vayunmathur.nowplaying.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatchingGateTest {

    private fun feed(gate: LatchingGate, score: Float, hops: Int) {
        repeat(hops) { gate.push(score) }
    }

    @Test
    fun `starts silent`() {
        assertFalse(LatchingGate().isMusic)
    }

    @Test
    fun `a single loud hop is not enough to latch on`() {
        val gate = LatchingGate()
        assertFalse(gate.push(1f))
        assertFalse(gate.isMusic)
    }

    @Test
    fun `a sustained run above the positive threshold latches on`() {
        val gate = LatchingGate()
        feed(gate, 1f, 9)
        assertTrue(gate.isMusic)
    }

    @Test
    fun `latching on takes a run of five hops above the threshold`() {
        val gate = LatchingGate()
        var hopsUntilOn = 0
        repeat(20) {
            if (!gate.isMusic) {
                gate.push(1f)
                hopsUntilOn++
            }
        }
        assertEquals(5, hopsUntilOn)
    }

    @Test
    fun `dropping below the negative threshold latches off`() {
        // Pinned rather than defaulted: this covers the drain-then-run mechanism, not the tuning.
        // The narrow band matters - the mean has to fall all the way through it before the run
        // can start, which is what makes this three hops rather than one.
        val gate = LatchingGate(
            positiveThreshold = 0.58f,
            negativeThreshold = 0.57f,
            hopsBeforeNegative = 1,
        )
        feed(gate, 1f, 9)
        assertTrue(gate.isMusic)

        var hopsUntilOff = 0
        repeat(20) {
            if (gate.isMusic) {
                gate.push(0f)
                hopsUntilOff++
            }
        }
        assertFalse(gate.isMusic)
        // Three, not one: the five-hop mean has to fall through the band before the run starts.
        assertEquals(3, hopsUntilOff)
    }

    @Test
    fun `the negative run length sets a floor on how briefly music can be believed`() {
        val gate = LatchingGate()
        feed(gate, 1f, 9)
        assertTrue(gate.isMusic)

        // One dipping hop must not drop the state - that is what produced ~2 events per second.
        gate.push(0f)
        assertTrue(gate.isMusic)

        var hopsUntilOff = 1
        repeat(200) {
            if (gate.isMusic) {
                gate.push(0f)
                hopsUntilOff++
            }
        }
        assertFalse(gate.isMusic)
        // The wide default band means the mean is already under the negative threshold on the
        // first silent hop, so the run starts at once and the dwell is the run length itself.
        assertEquals(LatchingGate.DEFAULT_HOPS_BEFORE_NEGATIVE, hopsUntilOff)
    }

    @Test
    fun `scores inside the hysteresis band hold the current verdict`() {
        // Pinned so the case survives retuning; only the band's existence is under test.
        val positive = 0.58f
        val negative = 0.57f
        val inBand = 0.575f
        fun gate() = LatchingGate(positiveThreshold = positive, negativeThreshold = negative)

        val fromSilence = gate()
        feed(fromSilence, inBand, 50)
        assertFalse(fromSilence.isMusic)

        val fromMusic = gate()
        feed(fromMusic, 1f, 9)
        feed(fromMusic, inBand, 50)
        assertTrue(fromMusic.isMusic)
    }

    @Test
    fun `push reports only the hops on which the verdict changed`() {
        val gate = LatchingGate()
        val flips = (1..20).count { gate.push(1f) }
        assertEquals(1, flips)
    }

    @Test
    fun `smoothing averages over the window rather than tracking the last hop`() {
        val gate = LatchingGate(windowHops = 4)
        gate.push(1f)
        gate.push(1f)
        gate.push(0f)
        gate.push(0f)
        assertEquals(0.5f, gate.smoothedScore, TOLERANCE)
    }

    @Test
    fun `a single quiet hop does not knock out a steady detection when smoothed`() {
        val gate = LatchingGate()
        feed(gate, 1f, 9)
        // One dropout leaves the five-hop mean at 0.8, still above the positive threshold.
        gate.push(0f)
        assertTrue(gate.isMusic)
    }

    @Test
    fun `reset returns the gate to its initial state`() {
        val gate = LatchingGate()
        feed(gate, 1f, 9)
        assertTrue(gate.isMusic)

        gate.reset()
        assertFalse(gate.isMusic)
        assertEquals(0f, gate.smoothedScore, TOLERANCE)
        // And latching on again takes the full run, rather than resuming mid-window.
        assertFalse(gate.push(1f))
    }

    private companion object {
        const val TOLERANCE = 1e-6f
    }
}
