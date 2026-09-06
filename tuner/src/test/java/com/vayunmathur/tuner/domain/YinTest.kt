package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 1 has one job it must never get wrong: the octave.
 *
 * The classic YIN failure is locking onto a sub-harmonic of the difference function and
 * reporting a note an octave low, which on a tuner is not a small error - it is the difference
 * between "in tune" and "not the string you think".
 */
class YinTest {
    private val window = 8192
    private val yin = Yin(window, Signals.SAMPLE_RATE)

    /** Thirty exact frequencies from A0 to just above B6, logarithmically spaced. */
    private val frequencies: List<Double> = (0 until 30).map {
        27.5 * Math.pow(2.0, it * 6.5 / 29.0 / 1.0)
    }.filter { it <= 2000.0 }

    @Test
    fun findsTheRightOctaveForASawtoothAcrossTheWholeRange() {
        for (hz in frequencies) {
            val signal = Signals.sawtooth(hz, window + 16)
            val result = yin.analyse(signal, 0, 20.0, 4000.0)
            assertNotNull(result, "no result at $hz Hz")
            val cents = 1200.0 * log2(result.frequencyHz / hz)
            assertTrue(abs(cents) < 20.0, "at $hz Hz got ${result.frequencyHz} Hz ($cents cents)")
        }
    }

    @Test
    fun findsTheRightOctaveForAPureSine() {
        for (hz in frequencies.filter { it > 40.0 }) {
            val signal = Signals.sine(hz, window + 16)
            val result = yin.analyse(signal, 0, 20.0, 4000.0)
            assertNotNull(result, "no result at $hz Hz")
            val cents = 1200.0 * log2(result.frequencyHz / hz)
            assertTrue(abs(cents) < 20.0, "at $hz Hz got ${result.frequencyHz} Hz ($cents cents)")
        }
    }

    @Test
    fun survivesAMissingFundamental() {
        // A phone microphone rolls off hard below ~100 Hz, so the low E of a bass arrives with
        // essentially no energy at the fundamental. YIN still has to find the period.
        val hz = 82.4068892282175
        val signal = Signals.harmonic(hz, window + 16, listOf(0.0, 0.4, 0.3, 0.25, 0.2, 0.15))
        val result = yin.analyse(signal, 0, 70.0, 400.0)
        assertNotNull(result)
        val cents = 1200.0 * log2(result.frequencyHz / hz)
        assertTrue(abs(cents) < 20.0, "got ${result.frequencyHz} Hz ($cents cents)")
    }

    @Test
    fun reportsHighAperiodicityForNoise() {
        // Pure noise has no period. Assert the gate would reject it rather than asserting a
        // particular frequency - "listening" is the correct output here, not a number.
        val noise = Signals.noise(window + 16, sigma = 0.3, seed = 7)
        val result = yin.analyse(noise, 0, 70.0, 1000.0)
        if (result != null) assertTrue(result.aperiodicity > 0.2, "noise looked periodic")
    }

    @Test
    fun theShortWindowStillTracksTheFastBand() {
        val short = Yin(2048, Signals.SAMPLE_RATE)
        for (hz in listOf(196.0, 293.66, 440.0, 987.77)) {
            val signal = Signals.sawtooth(hz, 2048 + 16)
            val result = short.analyse(signal, 0, 160.0, 2000.0)
            assertNotNull(result, "no result at $hz Hz")
            assertEquals(hz, result.frequencyHz, hz * 0.01)
        }
    }
}
