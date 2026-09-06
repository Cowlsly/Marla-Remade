package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the cents arithmetic and note naming.
 *
 * Every other stage of this app is judged against these numbers, so an error here would be
 * invisible: a wrong reference does not throw, it produces a confident, wrong needle.
 */
class CentsTest {
    private val concert = PitchReference(DEFAULT_A4_HZ)

    @Test
    fun a4IsExactlyZeroCents() {
        assertEquals(69, concert.nearestMidi(440.0))
        assertEquals(0.0, concert.centsFrom(440.0, 69), 1e-9)
    }

    @Test
    fun referenceFrequenciesMatchTwelveToneEqualTemperament() {
        assertEquals(440.0, concert.referenceHz(69), 1e-9)
        assertEquals(220.0, concert.referenceHz(57), 1e-9)
        assertEquals(261.6255653005986, concert.referenceHz(60), 1e-9)
        assertEquals(82.4068892282175, concert.referenceHz(40), 1e-9)
        assertEquals(30.86770632850775, concert.referenceHz(23), 1e-9)
    }

    @Test
    fun centsRoundTripThroughFrequency() {
        for (midi in 21..108) {
            for (offset in listOf(-49.0, -12.5, 0.0, 7.3, 49.0)) {
                val hz = concert.referenceHz(midi) * Math.pow(2.0, offset / 1200.0)
                assertEquals(midi, concert.nearestMidi(hz), "octave lost at $midi $offset")
                assertEquals(offset, concert.centsFrom(hz, midi), 1e-6)
            }
        }
    }

    @Test
    fun nearestNoteFlipsAtTheHalfSemitoneBoundary() {
        val justUnder = concert.referenceHz(69) * Math.pow(2.0, 49.9 / 1200.0)
        val justOver = concert.referenceHz(69) * Math.pow(2.0, 50.1 / 1200.0)
        assertEquals(69, concert.nearestMidi(justUnder))
        assertEquals(70, concert.nearestMidi(justOver))
    }

    @Test
    fun aFourThirtyTwoShiftsEveryNoteByTheSameAmount() {
        val baroqueish = PitchReference(432.0)
        for (midi in 21..108) {
            val cents = 1200.0 * log2(baroqueish.referenceHz(midi) / concert.referenceHz(midi))
            assertEquals(-31.766654, cents, 1e-5)
        }
        // A 440 Hz tone read against A = 432 is the same distance the other way.
        assertEquals(31.766654, baroqueish.centsFrom(440.0, 69), 1e-5)
    }

    @Test
    fun temperamentOffsetsShiftOnlyTheirOwnPitchClass() {
        val stretched = DoubleArray(12).also { it[0] = 5.0 }
        val reference = PitchReference(DEFAULT_A4_HZ, stretched)
        assertEquals(5.0, 1200.0 * log2(reference.referenceHz(60) / concert.referenceHz(60)), 1e-9)
        assertEquals(0.0, 1200.0 * log2(reference.referenceHz(62) / concert.referenceHz(62)), 1e-9)
    }

    @Test
    fun spellingUsesScientificPitchNotation() {
        assertEquals("C4", spell(60).toString())
        assertEquals("A4", spell(69).toString())
        assertEquals("E2", spell(40).toString())
        assertEquals("C-1", spell(0).toString())
        assertEquals("D\u266F4", spell(63).toString())
        assertEquals("E\u266D4", spell(63, AccidentalStyle.FLATS).toString())
    }

    @Test
    fun bandsMatchWhatTheSystemCanActuallyResolve() {
        assertEquals(TuningBand.IN_TUNE, bandFor(0.9))
        assertEquals(TuningBand.CLOSE, bandFor(-1.1))
        assertEquals(TuningBand.CLOSE, bandFor(5.0))
        assertEquals(TuningBand.OUT, bandFor(-5.1))
    }

    @Test
    fun pitchClassIsCorrectForNegativeMidi() {
        assertTrue(abs(pitchClassOf(-1) - 11) == 0)
        assertEquals(0, pitchClassOf(-12))
    }
}
