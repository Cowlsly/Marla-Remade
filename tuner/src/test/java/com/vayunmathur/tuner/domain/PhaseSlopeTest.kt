package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This is the test that protects the product claim.
 *
 * "Accurate to well under a cent" is only true if stage 2 is, and a phase-slope estimator that
 * is quietly a few cents off looks exactly like one that is not. Every assertion here is in
 * cents against a frequency the generator knows exactly.
 */
class PhaseSlopeTest {
    private val lowBand = PhaseSlope(Signals.SAMPLE_RATE, subWindow = 4096, separation = 4096)
    private val fastBand = PhaseSlope(Signals.SAMPLE_RATE, subWindow = 1024, separation = 1024)

    /** Stage 1 hands stage 2 a target roughly a fifth of a percent out; simulate that. */
    private fun coarse(hz: Double) = hz * 1.002

    @Test
    fun aPureSineIsMeasuredToWellUnderACent() {
        val count = lowBand.requiredSamples
        for (hz in listOf(196.0, 329.62755691287, 440.0, 880.0, 1318.5102276514797)) {
            val result = lowBand.refine(Signals.sine(hz, count), 0, coarse(hz))
            assertNotNull(result, "no estimate at $hz Hz")
            val cents = 1200.0 * log2(result.frequencyHz / hz)
            assertTrue(abs(cents) < 0.1, "at $hz Hz the error was $cents cents")
        }
    }

    @Test
    fun noiseDoesNotPushTheEstimateOverHalfACent() {
        val count = lowBand.requiredSamples
        for (snr in listOf(40.0, 30.0, 20.0)) {
            for (hz in listOf(146.83238395870379, 440.0, 987.7666025122483)) {
                val signal = Signals.withNoise(Signals.sine(hz, count), snr, seed = snr.toInt())
                val result = lowBand.refine(signal, 0, coarse(hz))
                assertNotNull(result, "no estimate at $hz Hz, $snr dB")
                val cents = 1200.0 * log2(result.frequencyHz / hz)
                assertTrue(abs(cents) < 0.5, "at $hz Hz and $snr dB the error was $cents cents")
            }
        }
    }

    @Test
    fun theHarmonicsCarryALowNoteWhoseFundamentalIsGone() {
        // A guitar's low E through a phone microphone: the capsule rolls off below ~100 Hz, so
        // the fundamental is barely there and the harmonics are the only signal.
        val hz = 82.4068892282175
        val count = lowBand.requiredSamples
        val signal = Signals.harmonic(
            hz,
            count,
            listOf(0.02, 0.35, 0.30, 0.25, 0.20, 0.16, 0.13, 0.10),
        )
        val result = lowBand.refine(Signals.withNoise(signal, 30.0), 0, coarse(hz))
        assertNotNull(result)
        val cents = 1200.0 * log2(result.frequencyHz / hz)
        assertTrue(abs(cents) < 0.5, "the error was $cents cents")
    }

    @Test
    fun theFastBandIsAccurateEnoughWhereItIsUsed() {
        // The fast band's phase scale is four times coarser, which is fine because a cent is
        // four times wider in Hz up here.
        val count = fastBand.requiredSamples
        for (hz in listOf(196.0, 440.0, 1046.5022612023945)) {
            val result = fastBand.refine(Signals.withNoise(Signals.sine(hz, count), 30.0), 0, coarse(hz))
            assertNotNull(result, "no estimate at $hz Hz")
            val cents = 1200.0 * log2(result.frequencyHz / hz)
            assertTrue(abs(cents) < 0.5, "at $hz Hz the error was $cents cents")
        }
    }

    @Test
    fun aStiffStringIsNotReportedSharp() {
        // Naively averaging f_n/n biases the answer several cents sharp on a stiff string. The
        // regression against n^2 is what removes that, and this is the assertion that proves it.
        val hz = 110.0
        val stiffness = 5e-5
        val count = lowBand.requiredSamples
        val signal = Signals.harmonic(
            hz,
            count,
            listOf(0.4, 0.35, 0.30, 0.25, 0.20, 0.16, 0.13, 0.10),
            stiffness = stiffness,
        )
        val result = lowBand.refine(signal, 0, coarse(hz))
        assertNotNull(result)
        assertTrue(result.stiffnessFitted, "the fit fell back to a plain mean")
        val cents = 1200.0 * log2(result.frequencyHz / hz)
        assertTrue(abs(cents) < 0.5, "the fundamental was off by $cents cents")
        val ratio = result.stiffness / stiffness
        assertTrue(ratio in 0.8..1.2, "recovered B was ${result.stiffness}, wanted $stiffness")
    }

    @Test
    fun aStiffStringWithoutTheFitWouldHaveBeenSharp() {
        // Sanity check on the previous test: confirm the bias it removes is real and not
        // hypothetical, by measuring the naive mean of f_n/n on the same signal.
        val hz = 110.0
        val stiffness = 5e-5
        var weighted = 0.0
        var weight = 0.0
        for (n in 1..8) {
            val partial = n * hz * (1.0 + stiffness * n * n).pow(0.5)
            val w = 1.0
            weighted += w * partial / n
            weight += w
        }
        val naive = weighted / weight
        val cents = 1200.0 * log2(naive / hz)
        assertTrue(cents > 1.0, "the naive mean was only $cents cents sharp, so this test is moot")
    }
}
