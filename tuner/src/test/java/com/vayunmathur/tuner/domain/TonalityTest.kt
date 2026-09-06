package com.vayunmathur.tuner.domain

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tonality gate, measured rather than asserted from intuition (TUNER_SPEC C.2).
 *
 * The bug this covers was reported from real use: with nothing playing, the Chord tab named
 * chords off the room. The Note tab did not, because YIN rejects aperiodic input using its own
 * aperiodicity measure. NNLS has no such measure - it fits whatever it is given - so the gate
 * has to be added explicitly, and the only honest way to place its thresholds is to print the
 * distributions and look at the gap.
 */
class TonalityTest {
    private val constantQ = ConstantQ(Signals.SAMPLE_RATE)
    private val salience = NoteSalience()
    private val samples = constantQ.requiredSamples

    private fun tonalityOf(signal: DoubleArray): Tonality {
        val magnitudes = constantQ.magnitudes(signal)
        return tonalityOf(magnitudes, salience.fit(salience.subtractBackground(magnitudes)))
    }

    private val noiseCases = listOf(
        "white (loud)" to Signals.noise(samples, sigma = 0.3),
        "white (quiet)" to Signals.noise(samples, sigma = 0.02, seed = 3),
        "white (very quiet)" to Signals.noise(samples, sigma = 0.002, seed = 5),
        "pink (loud)" to Signals.pinkNoise(samples, sigma = 0.3),
        "pink (quiet)" to Signals.pinkNoise(samples, sigma = 0.02, seed = 13),
        "pink (very quiet)" to Signals.pinkNoise(samples, sigma = 0.002, seed = 17),
        "silence" to DoubleArray(samples),
    )

    private val chordCases = listOf(
        "E major" to listOf(40, 47, 52, 56, 59, 64),
        "C major" to listOf(48, 52, 55, 60),
        "G major" to listOf(43, 47, 50, 55),
        "A minor" to listOf(45, 48, 52, 57),
        "G7" to listOf(43, 47, 50, 53),
        "Cmaj7" to listOf(48, 52, 55, 59),
        "Bdim" to listOf(47, 50, 53),
        "single C4" to listOf(60),
    )

    @Test
    fun noiseIsRejectedAndChordsAreNotAtEveryLevelTested() {
        println("MEASURED tonality  |  explained  flatness  peakSalience  tonal?")
        var worstChordFlatness = 0.0
        var closestNoiseFlatness = 1.0
        for ((name, signal) in noiseCases) {
            val tonality = tonalityOf(signal)
            report(name, tonality)
            closestNoiseFlatness = minOf(closestNoiseFlatness, tonality.flatness)
            assertTrue(!tonality.isTonal, "$name passed the tonality gate")
        }
        for ((name, midis) in chordCases) {
            val tonality = tonalityOf(Signals.chord(midis, samples))
            report(name, tonality)
            worstChordFlatness = maxOf(worstChordFlatness, tonality.flatness)
            assertTrue(tonality.isTonal, "$name was rejected by the tonality gate")
        }
        println(
            "MEASURED margin on flatness: worst chord %.3f vs closest noise %.3f, gate at %.2f"
                .format(worstChordFlatness, closestNoiseFlatness, Tonality.MAX_FLATNESS),
        )
    }

    /**
     * The measurement behind [Tonality.explained], kept as a test so the claim stays
     * true rather than becoming a comment about how things used to be.
     *
     * NNLS residual energy is the principled gate and it does not work here. Asserting the
     * overlap is what stops it being quietly reinstated on the strength of the argument for it.
     */
    @Test
    fun residualEnergyDoesNotSeparateNoiseFromChords() {
        val noise = noiseCases.filter { it.first != "silence" }
            .map { tonalityOf(it.second).explained }
        val chords = chordCases.map { tonalityOf(Signals.chord(it.second, samples)).explained }
        val bestNoise = noise.max()
        val worstChord = chords.min()
        println(
            "MEASURED explained energy: chords %.3f..%.3f, noise %.3f..%.3f"
                .format(chords.min(), chords.max(), noise.min(), bestNoise),
        )
        assertTrue(
            bestNoise > worstChord - SEPARABLE_GAP,
            "residual energy now separates noise from chords by more than $SEPARABLE_GAP " +
                "(chords from $worstChord, noise to $bestNoise) - reconsider gating on it",
        )
    }

    /**
     * Quiet chords are the case the gate must not overreach on.
     *
     * A tenth of full level is a soft strum; the two below it are there to find where the level
     * dependence of [Tonality.peakSalience] bites, and are printed rather than asserted because
     * a signal that quiet is inaudible and refusing to name it is not a failure.
     */
    @Test
    fun quietChordsStillPassTheGate() {
        for (scale in listOf(0.3, 0.1, 0.05)) {
            val tonality = tonalityOf(scaled(scale))
            report("C major x$scale", tonality)
            assertTrue(tonality.isTonal, "a C major at $scale amplitude was gated out")
        }
        for (scale in listOf(0.01, 0.003)) report("C major x$scale", tonalityOf(scaled(scale)))
    }

    private fun scaled(scale: Double): DoubleArray {
        val chord = Signals.chord(listOf(48, 52, 55, 60), samples)
        return DoubleArray(samples) { chord[it] * scale }
    }

    /** A chord buried in noise is still a chord until the noise genuinely dominates. */
    @Test
    fun chordsUnderNoiseSurviveToAReasonableSignalToNoiseRatio() {
        for (snrDb in listOf(20.0, 10.0, 5.0, 0.0)) {
            val signal = Signals.withNoise(Signals.chord(listOf(48, 52, 55, 60), samples), snrDb)
            report("C major @ ${snrDb}dB SNR", tonalityOf(signal))
        }
    }

    /**
     * The reported bug, reproduced: what the namer says about noise when the gate is bypassed.
     *
     * Printed rather than asserted - the point is the record of what the pipeline used to do,
     * and pinning an exact wrong chord name would be asserting a bug rather than a property.
     */
    @Test
    fun withoutTheGateTheNamerNamesNoise() {
        var named = 0
        for ((name, signal) in noiseCases) {
            val magnitudes = constantQ.magnitudes(signal)
            val fit = salience.fit(salience.subtractBackground(magnitudes))
            val reading = ChordNaming.name(pickNotes(fit.salience), null, ChordTier.CONFIRMED)
            if (reading.label !is ChordLabel.Silent) named++
            println("MEASURED ungated $name -> ${reading.label} / ${reading.presentation}")
        }
        println("MEASURED ungated: $named/${noiseCases.size} noise frames produced a reading")
    }

    /** And with the gate in place: idle, every time, on every kind of noise. */
    @Test
    fun theDetectorSitsIdleOnNoise() {
        val detector = ChordDetector(Signals.SAMPLE_RATE)
        for ((name, signal) in noiseCases) {
            detector.reset()
            var reading = detector.analyse(signal, ChordTier.CONFIRMED)
            repeat(3) { reading = detector.analyse(signal, ChordTier.CONFIRMED) }
            assertEquals(ChordLabel.Silent, reading.label, "$name was named ${reading.label}")
            assertEquals(ChordPresentation.LISTENING, reading.presentation, "$name was not idle")
            assertTrue(reading.notes.isEmpty(), "$name left notes on screen")
        }
    }

    /**
     * A chord, then noise: the tab must go idle rather than leave the last chord standing.
     *
     * This is the failure the user actually sees - a stale name is indistinguishable from a
     * live one - so it is worth asserting separately from the cold-start case above.
     */
    @Test
    fun noiseAfterAChordClearsTheReading() {
        val detector = ChordDetector(Signals.SAMPLE_RATE)
        val chord = Signals.chord(listOf(48, 52, 55, 60), samples)
        repeat(4) { detector.analyse(chord, ChordTier.CONFIRMED) }
        assertTrue(
            detector.analyse(chord, ChordTier.CONFIRMED).label is ChordLabel.Chord,
            "the C major under test was not named in the first place",
        )
        var reading = detector.analyse(Signals.noise(samples), ChordTier.CONFIRMED)
        repeat(3) { reading = detector.analyse(Signals.noise(samples), ChordTier.CONFIRMED) }
        assertEquals(ChordLabel.Silent, reading.label, "a chord survived into noise")
    }

    /**
     * The gate against partial laws the dictionary does not assume - the same guard
     * [ChordAccuracyTest] applies to naming, applied to the gate in front of it.
     *
     * A fast-decaying stack is close to a set of pure tones and carries much less energy than
     * the corpus the thresholds were read off, so it is the realistic way for the gate to start
     * refusing real chords without anyone noticing.
     */
    @Test
    fun chordsSynthesisedWithUnmodelledPartialLawsStillPassTheGate() {
        val reference = PitchReference()
        for (amplitudes in listOf(
            List(8) { 0.4 * 0.55.pow(it) },
            List(12) { 0.4 * 0.90.pow(it) },
            List(16) { 0.4 / (it + 1) },
        )) {
            var worstExplained = 1.0
            var worstFlatness = 0.0
            var worstPeak = 1.0
            var rejected = 0
            for (midis in TIER_A_VOICINGS) {
                val signal = DoubleArray(samples)
                for (midi in midis) {
                    val part = Signals.harmonic(reference.referenceHz(midi), samples, amplitudes)
                    for (i in 0 until samples) signal[i] += part[i]
                }
                val tonality = tonalityOf(signal)
                worstExplained = minOf(worstExplained, tonality.explained)
                worstFlatness = maxOf(worstFlatness, tonality.flatness)
                worstPeak = minOf(worstPeak, tonality.peakSalience)
                if (!tonality.isTonal) rejected++
            }
            println(
                "MEASURED ${amplitudes.size} partials: worst explained %.3f, worst flatness %.3f, "
                    .format(worstExplained, worstFlatness) +
                    "worst peak %.4f, rejected $rejected/${TIER_A_VOICINGS.size}".format(worstPeak),
            )
            assertTrue(rejected == 0, "${amplitudes.size} partials: $rejected chords gated out")
        }
    }

    private fun report(name: String, tonality: Tonality) {
        println(
            "MEASURED %-22s %8.3f %9.3f %13.4f  %s".format(
                name,
                tonality.explained,
                tonality.flatness,
                tonality.peakSalience,
                tonality.isTonal,
            ),
        )
    }

    private companion object {
        /**
         * A gap this size between the two distributions would make residual energy usable as a
         * gate. It is not reached - see [residualEnergyDoesNotSeparateNoiseFromChords].
         */
        const val SEPARABLE_GAP = 0.10

        /** The tier-A voicings from [ChordAccuracyTest], which the gate must never reject. */
        val TIER_A_VOICINGS = listOf(
            listOf(40, 47, 52, 56, 59, 64),
            listOf(48, 52, 55, 60),
            listOf(43, 47, 50, 55),
            listOf(50, 54, 57, 62),
            listOf(45, 48, 52, 57),
            listOf(40, 47, 52, 55, 59, 64),
            listOf(50, 53, 57, 62),
            listOf(43, 47, 50, 53),
            listOf(48, 52, 55, 58),
            listOf(50, 54, 57, 60),
            listOf(48, 52, 55, 59),
            listOf(53, 57, 60, 64),
            listOf(47, 50, 53),
            listOf(50, 53, 56),
        )
    }
}
