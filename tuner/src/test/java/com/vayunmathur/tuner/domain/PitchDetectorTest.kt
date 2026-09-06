package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the Note tab's analyser against signals whose frequency is known exactly.
 *
 * The stage tests (YinTest, PhaseSlopeTest) each prove one half in isolation. This one runs the
 * assembled [YinPhaseSlopeAnalyzer] - band selection, noise gate, cross-check and all - because
 * that composition is what the tuner actually reports, and a stage can be accurate while the
 * pipeline around it throws the frame away or picks the wrong octave.
 *
 * Every assertion is in cents against the generator's exact frequency, and the measured errors
 * are printed so the numbers in a report are read off a run rather than estimated.
 */
class PitchDetectorTest {

    private val reference = PitchReference()

    /**
     * Runs enough frames for the band hysteresis to settle and returns the last accepted one.
     *
     * A single call is not representative: the analyser cold-starts in the low band and needs
     * three consenting frames before it moves, which is exactly the behaviour a one-shot test
     * would miss.
     */
    private fun settle(
        analyzer: YinPhaseSlopeAnalyzer,
        signal: DoubleArray,
        minHz: Double,
        maxHz: Double,
        frames: Int = 8,
    ): PitchFrame {
        var last: PitchFrame = PitchFrame.Silent(PitchRejection.TOO_QUIET)
        repeat(frames) { last = analyzer.analyse(signal, minHz, maxHz) }
        return last
    }

    private fun detected(
        signal: DoubleArray,
        minHz: Double,
        maxHz: Double,
    ): PitchEstimate {
        val analyzer = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val frame = settle(analyzer, signal, minHz, maxHz)
        assertTrue(frame is PitchFrame.Detected, "no reading, got $frame")
        return frame.estimate
    }

    /** A plucked-string-ish stack: geometric decay over eight partials. */
    private fun pluck(hz: Double, count: Int, stiffness: Double = 0.0) =
        Signals.harmonic(hz, count, List(8) { 0.45 * 0.78.pow(it) }, stiffness = stiffness)

    private fun centsError(measured: Double, trueHz: Double) = 1200.0 * log2(measured / trueHz)

    @Test
    fun everyOpenGuitarStringIsMeasuredToWithinACent() {
        // Standard tuning, E2 to E4 - the span the fast/low band switch has to cross.
        val midis = listOf(40, 45, 50, 55, 59, 64)
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (midi in midis) {
            val hz = reference.referenceHz(midi)
            val estimate = detected(pluck(hz, samples), 70.0, 1400.0)
            val cents = centsError(estimate.frequencyHz, hz)
            println(
                "guitar ${spell(midi)} true=$hz measured=${estimate.frequencyHz} " +
                    "error=$cents cents band=${estimate.band}",
            )
            assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note at $hz Hz")
            assertTrue(abs(cents) < 1.0, "at ${spell(midi)} the error was $cents cents")
            worst = maxOf(worst, abs(cents))
        }
        println("worst open-string error: $worst cents")
    }

    @Test
    fun theBassRangeIsMeasuredToWithinACent() {
        // B0 is 30.87 Hz. This is the case a MIC-sourced capture would silently destroy, and the
        // case a short window cannot resolve - so it is the one worth asserting hardest.
        val midis = listOf(23, 28, 33, 38, 43)
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (midi in midis) {
            val hz = reference.referenceHz(midi)
            val estimate = detected(pluck(hz, samples), 27.0, 400.0)
            val cents = centsError(estimate.frequencyHz, hz)
            println(
                "bass ${spell(midi)} true=$hz measured=${estimate.frequencyHz} " +
                    "error=$cents cents band=${estimate.band}",
            )
            assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note at $hz Hz")
            assertTrue(abs(cents) < 1.0, "at ${spell(midi)} the error was $cents cents")
            worst = maxOf(worst, abs(cents))
        }
        println("worst bass error: $worst cents")
        assertTrue(worst < 1.0, "worst bass error was $worst cents")
    }

    @Test
    fun aDeliberatelyDetunedStringReportsTheOffsetItWasGiven() {
        // The number the user actually reads. A tuner that finds the right note but misreports
        // how far out it is would pass a note-name test and still be useless.
        val samples = PitchBand.LOW.windowSize
        for (offset in listOf(-30.0, -12.5, -3.0, 3.0, 12.5, 30.0)) {
            val target = reference.referenceHz(45)
            val hz = target * 2.0.pow(offset / 1200.0)
            val estimate = detected(pluck(hz, samples), 70.0, 1400.0)
            val midi = reference.nearestMidi(estimate.frequencyHz)
            val reported = reference.centsFrom(estimate.frequencyHz, midi)
            println("detune wanted=$offset reported=$reported error=${reported - offset} cents")
            assertEquals(45, midi, "wrong note for a $offset cent detune")
            assertTrue(
                abs(reported - offset) < 1.0,
                "a $offset cent detune was reported as $reported cents",
            )
        }
    }

    @Test
    fun aStiffLowStringIsNotReportedSharp() {
        // Inharmonicity is the systematic error that survives averaging, so it is the one that
        // would ship as a confident wrong answer rather than as visible jitter.
        val hz = reference.referenceHz(40)
        val estimate = detected(pluck(hz, PitchBand.LOW.windowSize, stiffness = 8e-5), 70.0, 1400.0)
        val cents = centsError(estimate.frequencyHz, hz)
        println("stiff low E error=$cents cents (stiffness fit ${estimate.stiffness})")
        assertTrue(abs(cents) < 1.0, "the stiff low E read $cents cents out")
    }

    @Test
    fun aNoisyRoomStillReadsUnderTwoCents() {
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (snr in listOf(30.0, 20.0)) {
            for (midi in listOf(40, 55, 64)) {
                val hz = reference.referenceHz(midi)
                val signal = Signals.withNoise(pluck(hz, samples), snr, seed = midi + snr.toInt())
                val estimate = detected(signal, 70.0, 1400.0)
                val cents = centsError(estimate.frequencyHz, hz)
                println("noisy ${spell(midi)} snr=${snr}dB error=$cents cents")
                assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note, $snr dB")
                assertTrue(abs(cents) < 2.0, "at ${spell(midi)}, $snr dB, the error was $cents cents")
                worst = maxOf(worst, abs(cents))
            }
        }
        println("worst noisy error: $worst cents")
    }

    @Test
    fun aMissingFundamentalDoesNotProduceAnOctaveError() {
        // A phone capsule rolls off the bottom, so the low E's fundamental is largely gone. The
        // classic failure is to lock onto the second partial and report E3.
        val hz = reference.referenceHz(40)
        val signal = Signals.harmonic(
            hz,
            PitchBand.LOW.windowSize,
            listOf(0.02, 0.35, 0.30, 0.25, 0.20, 0.16, 0.13, 0.10),
        )
        val estimate = detected(signal, 70.0, 1400.0)
        val cents = centsError(estimate.frequencyHz, hz)
        println("missing-fundamental low E error=$cents cents")
        assertEquals(40, reference.nearestMidi(estimate.frequencyHz), "octave error")
        assertTrue(abs(cents) < 1.0, "the error was $cents cents")
    }

    @Test
    fun silenceAndNoiseProduceNoReading() {
        // The readout must go blank rather than hold a stale number - a frozen needle reads as a
        // stable note, which is the most misleading thing a tuner can do.
        val analyzer = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val quiet = DoubleArray(PitchBand.LOW.windowSize)
        assertTrue(
            analyzer.analyse(quiet, 70.0, 1400.0) is PitchFrame.Silent,
            "silence produced a reading",
        )

        val noisy = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val hiss = Signals.noise(PitchBand.LOW.windowSize, sigma = 0.05)
        val frame = settle(noisy, hiss, 70.0, 1400.0)
        assertTrue(frame is PitchFrame.Silent, "white noise was reported as $frame")
    }

    @Test
    fun theReportedFrequencyAndCentsAgreeWithEachOther() {
        // The two numbers on screen come from the same estimate, so they must be consistent:
        // recomputing cents from the displayed Hz has to reproduce the displayed cents.
        val hz = reference.referenceHz(50) * 2.0.pow(7.0 / 1200.0)
        val estimate = detected(pluck(hz, PitchBand.LOW.windowSize), 70.0, 1400.0)
        val midi = reference.nearestMidi(estimate.frequencyHz)
        val cents = reference.centsFrom(estimate.frequencyHz, midi)
        val recomputed = 1200.0 * log2(estimate.frequencyHz / reference.referenceHz(midi))
        assertEquals(cents, recomputed, 1e-9)
        assertTrue(abs(cents - 7.0) < 1.0, "a 7 cent sharp D3 was reported as $cents cents")
    }
}
