package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tuning estimator, and specifically the branch it lands on.
 *
 * This is worth its own file because the failure mode is not a degradation. Only the fraction of
 * a semitone is observable, so choosing the wrong whole semitone renames every chord the app will
 * ever display by one step - confidently, and with no other symptom. Record-3 is the live case:
 * 56.9 cents flat, which nearest-semitone reads as 43.1 cents sharp and names B major where the
 * player played C.
 */
class TuningOffsetTest {
    private val constantQ = ConstantQ(Signals.SAMPLE_RATE)
    private val samples = constantQ.requiredSamples

    /** A C major triad rendered at a reference detuned by [cents]. */
    private fun estimate(cents: Double): Double {
        val reference = PitchReference(DEFAULT_A4_HZ * 2.0.pow(cents / 1200.0))
        val signal = Signals.chord(listOf(48, 52, 55, 60), samples, reference)
        val offset = TuningOffset()
        // Several frames, because MIN_PEAKS is a count of peaks and not of frames.
        repeat(4) { offset.observe(constantQ.magnitudes(signal)) }
        assertTrue(offset.settled, "the estimator never settled at $cents cents")
        return offset.cents
    }

    @Test
    fun anInTuneInstrumentReadsAsInTune() {
        val measured = estimate(0.0)
        println("MEASURED tuning offset: 0.0 cents in -> %.1f out".format(measured))
        assertTrue(abs(measured) < TOLERANCE, "an in-tune chord measured $measured cents")
    }

    /**
     * The Record-3 case. The assertion that matters is the sign: +43 would also be a correct
     * reading of the spectrum and a wrong answer for the user.
     */
    @Test
    fun aBadlyFlatInstrumentReadsAsFlatRatherThanSharp() {
        val measured = estimate(-56.9)
        println("MEASURED tuning offset: -56.9 cents in -> %.1f out".format(measured))
        assertTrue(measured < 0.0, "a flat instrument was read as $measured cents sharp")
        assertTrue(
            abs(measured - (-56.9)) < TOLERANCE,
            "expected about -56.9 cents, measured $measured",
        )
    }

    /**
     * The other side of [TuningOffset.MAX_SHARP_CENTS]: a small genuine sharpness must be taken
     * at face value, or a freshly-tuned instrument gets folded a whole semitone flat.
     */
    @Test
    fun aSlightlySharpInstrumentIsNotFoldedToFlat() {
        val measured = estimate(12.0)
        println("MEASURED tuning offset: +12.0 cents in -> %.1f out".format(measured))
        assertTrue(measured > 0.0, "a slightly sharp instrument was folded to $measured cents")
        assertTrue(abs(measured - 12.0) < TOLERANCE, "expected about +12 cents, measured $measured")
    }

    @Test
    fun aFrameWithNothingInItLeavesTheEstimateUnsettled() {
        val offset = TuningOffset()
        offset.observe(DoubleArray(CQT_BINS))
        assertTrue(!offset.settled, "silence settled the estimator")
        assertTrue(offset.cents == 0.0, "an unsettled estimator returned ${offset.cents}")
    }

    private companion object {
        /** The CQT has three bins a semitone, so a few cents of interpolation error is expected. */
        const val TOLERANCE = 8.0
    }
}
